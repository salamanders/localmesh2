package info.benjaminhill.localmesh2.p2p

import android.content.Context
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
import kotlin.time.ExperimentalTime
import kotlin.time.Instant


@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
class MeshConnection(
    appContext: Context,
    private val localHumanReadableName: String,
    private val listener: MeshConnectionListener
) {

    interface MeshConnectionListener {
        fun onConnectionResult(endpointId: String, result: ConnectionResolution)
        fun onPayloadReceived(endpointId: String, payload: Payload)
    }

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    // STATE 1: All potential neighbors we can see
    private val discoveredEndpoints = ConcurrentHashMap<String, DiscoveredEndpointInfo>()

    // STATE 2: Neighbors we are actively trying to connect to (or are connecting to us)
    private val pendingConnections = CopyOnWriteArraySet<String>()

    // STATE 3: Neighbors we are successfully connected to (endpointId -> connection timestamp)
    private val establishedConnections = ConcurrentHashMap<String, Instant>()

    fun getDiscoveredEndpoints(): Map<String, DiscoveredEndpointInfo> = discoveredEndpoints
    fun getPendingConnections(): Set<String> = pendingConnections
    fun getEstablishedConnections(): Map<String, Instant> = establishedConnections

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
            if (!pendingConnections.remove(endpointId)) {
                Timber.w("Tried to remove lost endpoint $endpointId from pendingConnections, but it was not present.")
            }
            if (establishedConnections.remove(endpointId) != null) {
                Timber.d("Removed lost endpoint $endpointId from establishedConnections.")
            }
        }
    }

    /** Handles all connection lifecycle events, both incoming and outgoing. */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Timber.i("onConnectionInitiated from ${connectionInfo.endpointName} ($endpointId)")
            if (pendingConnections.add(endpointId)) {
                acceptConnection(endpointId)
            } else {
                Timber.w("Duplicate onConnectionInitiated for $endpointId, ignoring.")
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!pendingConnections.remove(endpointId)) {
                Timber.w("onConnectionResult for $endpointId, but it was not in pendingConnections.")
            }
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                establishedConnections[endpointId] = Clock.System.now()
            }
            listener.onConnectionResult(endpointId, result)
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
            listener.onPayloadReceived(endpointId, payload)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Nothing to do here for now
        }
    }

    fun start() {
        connectionsClient.startAdvertising(
            localHumanReadableName,
            MESH_SERVICE_ID,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        ).addOnSuccessListener {
            Timber.i("Advertising started for service $localHumanReadableName.")
        }.addOnFailureListener { e ->
            Timber.i(e, "Advertising failed for service $localHumanReadableName.")
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
    }

    fun stop() {
        Timber.i("Manually stopped")
        connectionsClient.stopAllEndpoints()
        discoveredEndpoints.clear()
        pendingConnections.clear()
        establishedConnections.clear()
    }

    private fun acceptConnection(endpointId: String) {
        connectionsClient.acceptConnection(endpointId, payloadCallback)
    }

    fun requestConnection(endpointId: String) {
        if (pendingConnections.add(endpointId)) {
            connectionsClient.requestConnection(
                localHumanReadableName,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener {
                Timber.w("Failed to request connection to $endpointId")
                if (!pendingConnections.remove(endpointId)) {
                    Timber.e("Failed to remove $endpointId from pendingConnections after a failed connection request.")
                }
            }
        } else {
            Timber.w("Tried to connect to $endpointId, but it was already in pendingConnections.")
        }
    }

    fun disconnectFromEndpoint(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

    fun sendPayload(endpointIds: List<String>, payload: Payload) {
        connectionsClient.sendPayload(endpointIds, payload)
    }

    private fun forwardMessage(message: NetworkMessage, fromEndpointId: String?) {
        val msgToForward =
            message.copy(breadCrumbs = message.breadCrumbs + (NetworkHolder.localHumanReadableName to Clock.System.now()))
        val payload = Payload.fromBytes(msgToForward.toByteArray())

        val pathIds = msgToForward.breadCrumbs.map { it.first }
        // Forwarding the message has some benefit (reaches targets beyond the ones it already encountered)?
        val targets =
            establishedConnections.keys.toList().filter { it != fromEndpointId && it !in pathIds }

        if (targets.isNotEmpty()) {
            Timber.d("Forwarding message ${message.id} from $fromEndpointId to $targets")
            sendPayload(targets, payload)
        } else {
            Timber.d("No targets for message ${message.id}, dropping.")
        }
    }

    fun broadcast(message: NetworkMessage) {
        Timber.d("BROADCAST: $message")
        NetworkMessageRegistry.isFirstSeen(message)
        forwardMessage(message, null)
    }


    companion object {
        private const val MESH_SERVICE_ID = "info.benjaminhill.localmesh2.MESH"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }
}
