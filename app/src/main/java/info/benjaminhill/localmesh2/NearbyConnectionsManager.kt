package info.benjaminhill.localmesh2

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/**
 * Manages all low-level interactions with the Google Nearby Connections API.
 * This class is responsible for advertising, discovering, and managing the lifecycle of connections.
 * It holds a reference to a [TopologyOptimizer] and delegates all strategic decisions
 * (like when to connect, disconnect, or reshuffle) to it, acting as the "muscle" to the
 * optimizer's "brain".
 */
class NearbyConnectionsManager(
    context: Context,
    private val scope: CoroutineScope,
) {

    private var messageCleanupJob: Job? = null
    private val serviceId = "info.benjaminhill.localmesh2"
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val localName = CachedPrefs.getId(context)

    // The "brain" of the mesh, making all strategic decisions.
    private val topologyOptimizer: TopologyOptimizer =
        TopologyOptimizer(scope, ::disconnectFromEndpoint, ::requestConnection)

    private val connectedEndpoints = mutableSetOf<String>()

    /**
     * Discovered endpoints and their reported number of connections.
     * endpointId -> connectionCount
     */
    private val discoveredEndpoints = ConcurrentHashMap<String, Int>()

    /**
     * A map of recently seen message UIDs to the timestamp of their arrival.
     * Used to prevent message loops and to re-broadcast messages to new peers.
     * uid -> timestamp
     */
    private val seenMessageIds = ConcurrentHashMap<String, Long>()

    fun advertiseWithAccuratePeerCount() {
        connectionsClient.stopAdvertising()
        // Strategy.P2P_CLUSTER is used as it supports M-to-N connections,
        // which is suitable for a dynamic snake topology where multiple
        // endpoints can be discovering or advertising simultaneously.
        val advertisingOptions =
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        // The local endpoint name is advertised with the number of connections it has.
        // e.g. "MyDeviceName:2"
        val advertisingName = "$localName:${connectedEndpoints.size}"

        connectionsClient.startAdvertising(
            advertisingName, serviceId, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "$advertisingName started Advertising.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "$advertisingName failed to start advertising", e)
        }
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "$localName started discovery.")
            topologyOptimizer.start(connectedEndpoints, discoveredEndpoints)
        }.addOnFailureListener { e ->
            Log.e(TAG, "$localName failed to start discovery", e)
        }
        startMessageCleanup()
    }

    fun stop() {
        Log.i(TAG, "Stopping nearby connections.")
        topologyOptimizer.stop()
        messageCleanupJob?.cancel()
        connectionsClient.stopAllEndpoints()
    }

    // TODO: Broadcasting a message to the network.
    fun broadcast(type: NetworkMessage.Companion.Types, content: String) {
        val message = NetworkMessage(
            sendingNodeId = localName,
            messageType = type,
            messageContent = content,
        )
        val payloadBytes = message.toByteArray()
        seenMessageIds[message.messageId] = System.currentTimeMillis()
        Log.i(TAG, "Broadcasting ${payloadBytes.size} bytes with uid ${message.messageId}")
        connectionsClient.sendPayload(
            connectedEndpoints.toList(),
            Payload.fromBytes(payloadBytes)
        )
    }

    private fun startMessageCleanup() {
        messageCleanupJob = scope.launch {
            while (true) {
                delay(1.minutes)
                val now = System.currentTimeMillis()
                val thirtyMinutesAgo = now - 30.minutes.inWholeMilliseconds
                seenMessageIds.entries.removeAll { it.value < thirtyMinutesAgo }
            }
        }
    }


    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedMessage = NetworkMessage.fromByteArray(payload.asBytes()!!)

                if (seenMessageIds.containsKey(receivedMessage.messageId)) {
                    Log.i(
                        TAG,
                        "Ignoring duplicate message from $endpointId with uid ${receivedMessage.messageId}"
                    )
                    return
                }

                seenMessageIds[receivedMessage.messageId] = System.currentTimeMillis()
                Log.i(
                    TAG,
                    "Received message from $endpointId with uid ${receivedMessage.messageId}. Re-broadcasting."
                )

                // TODO: Do something with the message

                // Re-broadcast to other connected endpoints with incremented hop count.
                val otherEndpoints = connectedEndpoints.filter { it != endpointId }
                if (otherEndpoints.isNotEmpty()) {
                    val messageToRebroadcast =
                        receivedMessage.copy(hopCount = receivedMessage.hopCount + 1)
                    connectionsClient.sendPayload(
                        otherEndpoints,
                        Payload.fromBytes(messageToRebroadcast.toByteArray())
                    )
                }
            } else {
                Log.w(TAG, "Ignoring non-BYTES payload from $endpointId, type: ${payload.type}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS ->
                    Log.i(
                        TAG,
                        "SUCCESS: Payload ${update.payloadId} transfer to $endpointId complete."
                    )

                PayloadTransferUpdate.Status.FAILURE ->
                    Log.e(
                        TAG,
                        "FAILURE: Payload ${update.payloadId} transfer to $endpointId failed.",
                        Exception("PayloadTransferUpdate Failure")
                    )

                PayloadTransferUpdate.Status.CANCELED ->
                    Log.w(
                        TAG,
                        "CANCELED: Payload ${update.payloadId} transfer to $endpointId was canceled."
                    )

                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // Ignoring for now to keep logs clean. This is where you'd update a progress bar.
                }
            }

        }
    }

    private fun requestConnection(endpointId: String) {
        Log.i(TAG, "Requesting connection to $endpointId")
        connectionsClient.requestConnection(localName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Log.d(TAG, "Successfully requested connection to $endpointId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to request connection to $endpointId", e)
            }
    }

    private fun disconnectFromEndpoint(endpointId: String) {
        Log.i(TAG, "Disconnecting from $endpointId")
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo
        ) {
            Log.d(TAG, "onEndpointFound: $endpointId, ${discoveredEndpointInfo.endpointName}")
            val parts = discoveredEndpointInfo.endpointName.split(":")
            if (parts.size == 2) {
                val connectionCount = parts[1].toIntOrNull()
                if (connectionCount != null) {
                    discoveredEndpoints[endpointId] = connectionCount
                } else {
                    Log.w(
                        TAG,
                        "Could not parse connection count: ${discoveredEndpointInfo.endpointName}"
                    )
                    return
                }
            } else {
                Log.w(
                    TAG,
                    "Unexpected endpoint name format: ${discoveredEndpointInfo.endpointName}"
                )
                return
            }

            val theirConnectionCount = discoveredEndpoints[endpointId] ?: return

            if (topologyOptimizer.shouldConnectTo(
                    endpointId,
                    theirConnectionCount,
                    connectedEndpoints.size,
                    connectedEndpoints
                )
            ) {
                Log.i(
                    TAG,
                    "TopologyOptimizer decided to connect to $endpointId. Requesting connection."
                )
                requestConnection(endpointId)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "Endpoint lost: $endpointId")
            discoveredEndpoints.remove(endpointId)
        }
    }

    private val connectionLifecycleCallback: ConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(
                endpointId: String, connectionInfo: ConnectionInfo
            ) {
                Log.i(TAG, "onConnectionInitiated from $endpointId")
                if (connectedEndpoints.size < 7) {
                    Log.i(TAG, "Accepting connection from $endpointId")
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                } else {
                    Log.i(
                        TAG,
                        "Rejecting connection from $endpointId, already at max connections (7+)."
                    )
                    connectionsClient.rejectConnection(endpointId)
                }
            }

            override fun onConnectionResult(
                endpointId: String, resolution: ConnectionResolution
            ) {
                if (resolution.status.isSuccess) {
                    Log.i(TAG, "Successfully connected to $endpointId")
                    connectedEndpoints.add(endpointId)
                    advertiseWithAccuratePeerCount()
                } else {
                    Log.w(
                        TAG,
                        "Failed to connect to $endpointId: ${resolution.status.statusMessage}"
                    )
                }
            }

            override fun onDisconnected(endpointId: String) {
                Log.i(TAG, "onDisconnected from $endpointId")
                connectedEndpoints.remove(endpointId)
                advertiseWithAccuratePeerCount()
                topologyOptimizer.onDisconnected(connectedEndpoints, discoveredEndpoints)
            }
        }

    companion object {
        private const val TAG = "NCM"
    }
}