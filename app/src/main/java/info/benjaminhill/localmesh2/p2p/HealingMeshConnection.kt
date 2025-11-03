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
class HealingMeshConnection(appContext: Context) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    // STATE 1: All potential neighbors we can see
    private val discoveredEndpoints = ConcurrentHashMap<String, DiscoveredEndpointInfo>()

    // STATE 2: Neighbors we are actively trying to connect to (or are connecting to us)
    private val pendingConnections = CopyOnWriteArraySet<String>()

    // STATE 3: Neighbors we are successfully connected to (endpointId -> connection timestamp)
    private val establishedConnections = ConcurrentHashMap<String, Long>()

    // Handler for proactive connection management
    private val graphManagerHandler = Handler(Looper.getMainLooper())


    /** Handles populating the `discoveredEndpoints` map. */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == MESH_SERVICE_ID) {
                Timber.i("Discovery: Found endpoint ${'$'}endpointId (${'$'}{info.endpointName})")
                discoveredEndpoints[endpointId] = info
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.i("Discovery: Lost endpoint ${'$'}endpointId")
            discoveredEndpoints.remove(endpointId)
            // Robustness: Clean up any state if a lost endpoint was pending or established.
            pendingConnections.remove(endpointId)
            if (establishedConnections.containsKey(endpointId)) {
                establishedConnections.remove(endpointId)
                // The Graph Manager loop will handle finding a new connection.
            }
        }
    }

    /** Handles all connection lifecycle events, both incoming and outgoing. */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Timber.i("onConnectionInitiated from ${'$'}{connectionInfo.endpointName} (${'$'}endpointId)")
            if (pendingConnections.contains(endpointId)) {
                Timber.w("Duplicate onConnectionInitiated for ${'$'}endpointId, ignoring.")
                return
            }
            // Always accept the connection. We will prune in onConnectionResult.
            pendingConnections.add(endpointId)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingConnections.remove(endpointId) // No longer pending

            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Timber.i("onConnectionResult: SUCCESS for ${'$'}endpointId")
                    establishedConnections[endpointId] = System.currentTimeMillis()

                    // Pruning logic: If we are over-capacity, prune a random, tenured connection.
                    if (establishedConnections.size > K_DEGREE) {
                        val nodeToPrune = establishedConnections.entries
                            .filterNot { it.key == endpointId } // Don't prune the one we just added
                            .filter { (System.currentTimeMillis() - it.value) > PRUNE_COOLDOWN_MS }
                            .randomOrNull()

                        if (nodeToPrune != null) {
                            Timber.i("PRUNE: Over capacity. Pruning ${'$'}{nodeToPrune.key}")
                            connectionsClient.disconnectFromEndpoint(nodeToPrune.key)
                            // onDisconnected will handle removal from establishedConnections
                        }
                    }
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Timber.w("onConnectionResult: REJECTED for ${'$'}endpointId")
                }

                else -> {
                    Timber.w("onConnectionResult: FAILURE for ${'$'}endpointId, status: ${'$'}{result.status.statusMessage} (${'$'}{result.status.statusCode})")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Timber.i("onDisconnected from ${'$'}endpointId")
            establishedConnections.remove(endpointId)
        }
    }

    /** Handles incoming data from connected peers. */
    @OptIn(ExperimentalSerializationApi::class)
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Timber.d("onPayloadReceived from ${'$'}endpointId")
            if (payload.type != Payload.Type.BYTES) {
                Timber.w("Received non-bytes payload from ${'$'}endpointId, ignoring.")
                return
            }
            val message = NetworkMessage.fromByteArray(payload.asBytes()!!)

            if (!NetworkMessageRegistry.isFirstSeen(message)) {
                Timber.d("Ignoring duplicate message ${'$'}{message.id} from ${'$'}endpointId")
                return
            }

            if (message.displayTarget == EndpointRegistry.localHumanReadableName) {
                Timber.i("COMMAND: Received command: ${'$'}message")
                // TODO: Actually do something with the command
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
            Timber.i("Advertising started for service ${'$'}{EndpointRegistry.localHumanReadableName}.")
        }.addOnFailureListener { e ->
            Timber.i(e, "Advertising failed for service ${'$'}{EndpointRegistry.localHumanReadableName}.")
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

    @OptIn(ExperimentalSerializationApi::class)
    private fun forwardMessage(message: NetworkMessage, fromEndpointId: String?) {
        val newCrumbs = message.breadCrumbs + (EndpointRegistry.localHumanReadableName to System.currentTimeMillis())
        val msgToForward = message.copy(breadCrumbs = newCrumbs)
        val payload = Payload.fromBytes(msgToForward.toByteArray())

        val pathIds = newCrumbs.map { it.first }
        val targets = establishedConnections.keys.toList().filter { it != fromEndpointId && it !in pathIds }

        if (targets.isNotEmpty()) {
            Timber.d("Forwarding message ${'$'}{message.id} from ${'$'}fromEndpointId to ${'$'}targets")
            connectionsClient.sendPayload(targets, payload)
        }
    }

    fun getEstablishedConnectionsCount(): Int = establishedConnections.size

    fun broadcast(message: NetworkMessage) {
        Timber.d("BROADCAST: ${'$'}message")
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
        graphManagerHandler.postDelayed(graphManagerRunnable, MANAGER_LOOP_DELAY_MS)
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
                Timber.i("GRAPH_MANAGER: Attempting to connect to ${'$'}candidateId")
                pendingConnections.add(candidateId)
                connectionsClient.requestConnection(
                    EndpointRegistry.localHumanReadableName,
                    candidateId,
                    connectionLifecycleCallback
                ).addOnFailureListener {
                    Timber.w("GRAPH_MANAGER: Failed to request connection to ${'$'}candidateId")
                    pendingConnections.remove(candidateId)
                }
            }
        }
    }


    companion object {
        private const val MESH_SERVICE_ID = "info.benjaminhill.localmesh2.MESH"
        private val STRATEGY = Strategy.P2P_CLUSTER
        // The degree of the regular graph. 3 is recommended for stability.
        private const val K_DEGREE = 3
        // Cooldown before a connection is eligible for pruning.
        private const val PRUNE_COOLDOWN_MS = 30_000L
        // Proactive connection management loop delay.
        private const val MANAGER_LOOP_DELAY_MS: Long = 5000 // Check every 5 seconds
    }
}
