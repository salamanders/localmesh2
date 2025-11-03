package info.benjaminhill.localmesh2.p2p

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.serialization.ExperimentalSerializationApi
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Manages a k-regular graph topology using the Android Nearby Connections API.
 *
 * This class implements a state-driven architecture to maintain a graph where each node has a
 * degree of *k*. It uses a proactive "Graph Manager" to seek new connections when the node's
 * degree is below *k*, and an "accept-and-prune" strategy to maintain the graph's randomness
 * and handle new inbound connections when the node is at capacity.
 *
 * State Management:
 * - `discoveredEndpoints`: All potential neighbors seen by the discovery callback.
 * - `pendingConnections`: Endpoints for which a connection is currently in progress.
 * - `establishedConnections`: Active, confirmed connections that form the node's current degree.
 *
 * The implementation is based on the architectural guide for a state-driven, k-regular graph.
 */
@OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
class HealingMeshConnection(appContext: Context) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    // STATE 1: All potential neighbors we can see
    private val discoveredEndpoints = ConcurrentHashMap<String, DiscoveredEndpointInfo>()

    // STATE 2: Neighbors we are actively trying to connect to (or are connecting to us)
    private val pendingConnections = CopyOnWriteArraySet<String>()

    // STATE 3: Neighbors we are successfully connected to (endpointId -> connection timestamp)

    private val establishedConnections = ConcurrentHashMap<String, Instant>()

    // Handler for proactive connection management
    private val graphManagerHandler = Handler(Looper.getMainLooper())


    /** Handles populating the `discoveredEndpoints` map. */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == MESH_SERVICE_ID) {
                Timber.i("Discovery: Found endpoint $endpointId (${info.endpointName})")
                discoveredEndpoints[endpointId] = info
            } else {
                Timber.w("Discovery: Ignoring endpoint on different service ID: $endpointId (${info.endpointName}) on ${info.serviceId}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.i("Discovery: Lost endpoint $endpointId")
            if (discoveredEndpoints.remove(endpointId) == null) {
                Timber.w("Tried to remove lost endpoint $endpointId from discoveredEndpoints, but it was not present.")
            }
            // Robustness: Clean up any state if a lost endpoint was pending or established.
            if (!pendingConnections.remove(endpointId)) {
                Timber.w("Tried to remove lost endpoint $endpointId from pendingConnections, but it was not present.")
            }
            if (establishedConnections.remove(endpointId) != null) {
                // The Graph Manager loop will handle finding a new connection.
                Timber.d("Removed lost endpoint $endpointId from establishedConnections.")
            } else {
                Timber.d("Discovery: Lost endpoint $endpointId was not an established connection.")
            }
        }
    }

    /** Handles all connection lifecycle events, both incoming and outgoing. */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Timber.i("onConnectionInitiated from ${connectionInfo.endpointName} ($endpointId)")
            if (pendingConnections.add(endpointId)) {
                // Always accept the connection. We will prune in onConnectionResult.
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            } else {
                Timber.w("Duplicate onConnectionInitiated for $endpointId, ignoring.")
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!pendingConnections.remove(endpointId)) {
                Timber.w("onConnectionResult for $endpointId, but it was not in pendingConnections.")
            }

            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Timber.i("onConnectionResult: SUCCESS for $endpointId")
                    establishedConnections[endpointId] = Clock.System.now()

                    // Pruning logic: If we are over-capacity, prune a random, tenured connection.
                    if (establishedConnections.size > K_DEGREE) {
                        val nodeToPrune = establishedConnections.entries
                            .filterNot { it.key == endpointId } // Don't prune the one we just added
                            .filter { it.value < Clock.System.now().minus(PRUNE_COOLDOWN) }
                            .randomOrNull()

                        if (nodeToPrune != null) {
                            Timber.i("PRUNE: Over capacity. Pruning ${nodeToPrune.key}")
                            connectionsClient.disconnectFromEndpoint(nodeToPrune.key)
                            // onDisconnected will handle removal from establishedConnections
                        } else {
                            Timber.i("PRUNE: Over capacity, but no connections to prune.")
                        }
                    } else {
                        Timber.d("onConnectionResult: Not over capacity (${establishedConnections.size}/$K_DEGREE).")
                    }
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Timber.w("onConnectionResult: REJECTED for $endpointId")
                }

                else -> {
                    Timber.w("onConnectionResult: FAILURE for $endpointId, status: ${result.status.statusMessage} (${result.status.statusCode})")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Timber.i("onDisconnected from $endpointId")
            if (establishedConnections.remove(endpointId) == null) {
                Timber.w("onDisconnected from $endpointId, but it was not in establishedConnections.")
            }
        }
    }

    /** Handles incoming data from connected peers. */

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Timber.d("onPayloadReceived from $endpointId")
            if (payload.type != Payload.Type.BYTES) {
                Timber.w("Received non-bytes payload from $endpointId, ignoring.")
                return
            }
            val message = NetworkMessage.fromByteArray(payload.asBytes()!!)

            if (!NetworkMessageRegistry.isFirstSeen(message)) {
                Timber.d("Ignoring duplicate message ${message.id} from $endpointId")
                return
            }

            if (message.displayTarget == EndpointRegistry.localHumanReadableName) {
                Timber.i("COMMAND: Received command: $message")
                // TODO: Actually do something with the command
            } else {
                Timber.d("Message not for me, just forwarding.")
            }

            forwardMessage(message, fromEndpointId = endpointId)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Nothing to do here for now
        }
    }

    fun startNetworking() {
        connectionsClient.startAdvertising(
            EndpointRegistry.localHumanReadableName,
            MESH_SERVICE_ID,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        ).addOnSuccessListener {
            Timber.i("Advertising started for service ${EndpointRegistry.localHumanReadableName}.")
        }.addOnFailureListener { e ->
            Timber.i(
                e,
                "Advertising failed for service ${EndpointRegistry.localHumanReadableName}."
            )
        }

        connectionsClient.startDiscovery(
            MESH_SERVICE_ID,
            endpointDiscoveryCallback, // Use the new callback
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        ).addOnSuccessListener {
            Timber.i("Discovery started.")
        }.addOnFailureListener { e ->
            Timber.e(e, "Discovery failed.")
        }
        startGraphManagerLoop()
    }

    private fun forwardMessage(message: NetworkMessage, fromEndpointId: String?) {
        val msgToForward =
            message.copy(breadCrumbs = message.breadCrumbs + (EndpointRegistry.localHumanReadableName to Clock.System.now()))
        val payload = Payload.fromBytes(msgToForward.toByteArray())

        val pathIds = msgToForward.breadCrumbs.map { it.first }
        // Forwarding the message has some benefit (reaches targets beyond the ones it already encountered)?
        val targets =
            establishedConnections.keys.toList().filter { it != fromEndpointId && it !in pathIds }

        if (targets.isNotEmpty()) {
            Timber.d("Forwarding message ${message.id} from $fromEndpointId to $targets")
            connectionsClient.sendPayload(targets, payload)
        } else {
            Timber.d("No targets for message ${message.id}, dropping.")
        }
    }

    fun getEstablishedConnectionsCount(): Int = establishedConnections.size

    fun broadcast(message: NetworkMessage) {
        Timber.d("BROADCAST: $message")
        NetworkMessageRegistry.isFirstSeen(message)
        forwardMessage(message, null)
    }

    fun stopNetworking() {
        stopGraphManagerLoop()
        Timber.i("Manually stopped")
        connectionsClient.stopAllEndpoints()
        NetworkMessageRegistry.clear()
        discoveredEndpoints.clear()
        pendingConnections.clear()
        establishedConnections.clear()
    }

    private val graphManagerRunnable = Runnable {
        proactivelyMaintainGraph()
        startGraphManagerLoop() // Re-schedule itself
    }

    private fun startGraphManagerLoop() {
        graphManagerHandler.postDelayed(
            graphManagerRunnable,
            MANAGER_LOOP_DELAY.inWholeMilliseconds
        )
    }

    private fun stopGraphManagerLoop() {
        graphManagerHandler.removeCallbacks(graphManagerRunnable)
    }

    private fun proactivelyMaintainGraph() {
        if (establishedConnections.size < K_DEGREE) {
            val candidates = discoveredEndpoints.keys
                .filterNot { establishedConnections.containsKey(it) }
                .filterNot { pendingConnections.contains(it) }

            if (candidates.isNotEmpty()) {
                val candidateId = candidates.random()
                Timber.i("GRAPH_MANAGER: Current size is ${establishedConnections.size} so attempting to connect to random $candidateId")
                if (pendingConnections.add(candidateId)) {
                    connectionsClient.requestConnection(
                        EndpointRegistry.localHumanReadableName,
                        candidateId,
                        connectionLifecycleCallback
                    ).addOnFailureListener {
                        Timber.w("GRAPH_MANAGER: Failed to request connection to $candidateId")
                        if (!pendingConnections.remove(candidateId)) {
                            Timber.e("Failed to remove $candidateId from pendingConnections after a failed connection request.")
                        }
                    }
                } else {
                    Timber.w("GRAPH_MANAGER: Tried to connect to $candidateId, but it was already in pendingConnections.")
                }
            } else {
                Timber.w("GRAPH_MANAGER: Current size is ${establishedConnections.size} but no candidates to connect to.")
            }
        } else {
            Timber.d("GRAPH_MANAGER: At capacity (${establishedConnections.size}/$K_DEGREE), not seeking new connections.")
        }
    }


    companion object {
        private const val MESH_SERVICE_ID = "info.benjaminhill.localmesh2.MESH"
        private val STRATEGY = Strategy.P2P_CLUSTER

        // The degree of the regular graph. 3 is recommended for stability.
        private const val K_DEGREE = 3

        // Cooldown before a connection is eligible for pruning.
        private val PRUNE_COOLDOWN = 30.seconds

        // Proactive connection management loop delay.
        private val MANAGER_LOOP_DELAY = 5.seconds // Check every 5 seconds
    }
}
