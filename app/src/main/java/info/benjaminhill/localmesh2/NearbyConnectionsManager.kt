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
 */
object NearbyConnectionsManager {
    private const val TAG = "P2P"
    private const val SERVICE_ID = "info.benjaminhill.localmesh2"

    internal const val MAX_CONNECTIONS_HARDWARE_LIMIT = 7

    // Strategy.P2P_CLUSTER is used as it supports M-to-N connections,
    // which is suitable for a dynamic snake topology where multiple
    // endpoints can be discovering or advertising simultaneously.
    private val DISCOVERY_OPTIONS =
        DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

    /**
     * A map of recently seen message UIDs to the timestamp of their arrival.
     * Used to prevent message loops and to re-broadcast messages to new peers.
     * uid -> timestamp
     */
    private val seenMessageIds = ConcurrentHashMap<String, Long>()
    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope
    private lateinit var localId: String
    private lateinit var messageCleanupJob: Job

    private lateinit var connectionsClient: ConnectionsClient
    fun initialize(newContext: Context, newScope: CoroutineScope) {
        appContext = newContext.applicationContext
        scope = newScope
        localId = CachedPrefs.getId(appContext)
        connectionsClient = Nearby.getConnectionsClient(appContext)

        messageCleanupJob = scope.launch {
            while (true) {
                delay(1.minutes)
                val now = System.currentTimeMillis()
                val thirtyMinutesAgo = now - 30.minutes.inWholeMilliseconds
                seenMessageIds.entries.removeAll { it.value < thirtyMinutesAgo }
            }
        }
    }

    fun startAdvertising() {
        Log.i(TAG, "startAdvertising()...")
        connectionsClient.stopAdvertising()

        val advertisingOptions =
            AdvertisingOptions.Builder().setStrategy(DISCOVERY_OPTIONS.strategy).build()

        connectionsClient.startAdvertising(
            localId, SERVICE_ID, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "$localId started Advertising.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "$localId failed to start advertising", e)
        }
    }

    fun startDiscovery() {
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, DISCOVERY_OPTIONS
        ).addOnSuccessListener {
            Log.i(TAG, "$localId started discovery.")
            // The "brain" of the mesh, making all strategic decisions.
            TopologyOptimizer.initialize(scope, this, localId)
        }.addOnFailureListener { e ->
            Log.e(TAG, "$localId failed to start discovery", e)
        }
    }

    fun stop() {
        Log.w(TAG, "NearbyConnectionsManager.stop() called.")
        messageCleanupJob.cancel()
        TopologyOptimizer.stop()
        connectionsClient.stopAllEndpoints()
    }


    fun broadcastDisplayMessage(displayTarget: String) {
        val message = NetworkMessage(
            sendingNodeId = localId,
            messageType = NetworkMessage.Companion.Types.DISPLAY,
            displayTarget = displayTarget,
            peers = EndpointRegistry.getDirectlyConnectedEndpoints().map { it.id }.toSet(),
            distance = mapOf(localId to 0),
        )
        broadcastInternal(message)
    }

    internal fun broadcastInternal(message: NetworkMessage) {
        seenMessageIds[message.messageId] = System.currentTimeMillis()
        Log.i(
            TAG,
            "Broadcasting ${message.toByteArray().size} bytes with uid ${message.messageId}"
        )
        sendToAll(message)
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            EndpointRegistry.get(endpointId, autoUpdateTs = true)
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
                    "Received message from $endpointId with uid ${receivedMessage.messageId}."
                )

                when (receivedMessage.messageType) {
                    NetworkMessage.Companion.Types.GOSSIP -> {
                        receivedMessage.peers.let {
                            Log.d(
                                TAG,
                                "Received gossip from ${receivedMessage.sendingNodeId} via $endpointId with peers: $it"
                            )
                            EndpointRegistry.get(
                                receivedMessage.sendingNodeId,
                                autoUpdateTs = false
                            ).immediatePeerIds = it
                        }

                        // Update distances based on the received message.  Trustworthy, so ok to update TS.
                        receivedMessage.distance.forEach { (endpointId, distance) ->
                            val endpoint = EndpointRegistry.get(endpointId, autoUpdateTs = true)
                            val newDistance = distance + 1
                            if ((endpoint.distance ?: Int.MAX_VALUE) > newDistance) {
                                endpoint.distance = newDistance
                            }
                        }

                        // Re-broadcast to other connected endpoints
                        val nextMessage = receivedMessage.copy(
                            hopCount = receivedMessage.hopCount + 1,
                            distance = receivedMessage.distance + (localId to receivedMessage.hopCount + 1)
                        )
                        sendToAll(nextMessage, excludeEndpointId = endpointId)
                    }

                    NetworkMessage.Companion.Types.DISPLAY -> {
                        if (receivedMessage.sendingNodeId != localId) {
                            Log.i(
                                TAG,
                                "Received display command from ${receivedMessage.sendingNodeId} to display ${receivedMessage.displayTarget}"
                            )
                            WebAppActivity.navigateTo(receivedMessage.displayTarget!!)
                        }
                    }
                }
                // Re-broadcast to other connected endpoints
                sendToAll(
                    receivedMessage.copy(hopCount = receivedMessage.hopCount + 1),
                    excludeEndpointId = endpointId
                )
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
                        "FAILURE: Payload ${update.payloadId} transfer to $endpointId failed."
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

    internal fun requestConnection(endpointId: String) {
        val endpoint = EndpointRegistry.get(endpointId)
        Log.i(TAG, "Requesting connection to $endpointId")
        connectionsClient.requestConnection(localId, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Log.d(TAG, "Successfully requested connection to $endpointId")
                endpoint.distance = 1
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to request connection to $endpointId", e)
                if (e.toString().contains("STATUS_ALREADY_CONNECTED_TO_ENDPOINT")) {
                    Log.w(TAG, "... but we are already connected?")
                    endpoint.distance = 1
                } else {
                    endpoint.distance = null
                }
            }
    }

    // We have decided to disconnect from this endpoing to make the mesh better
    internal fun disconnectFromEndpoint(endpointId: String) {
        Log.i(TAG, "Choosing to disconnect from $endpointId")
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

    /**
     * Helper to send a message to all connected endpoints, with an optional exclusion.
     * @param message The message to send.
     * @param excludeEndpointId Optional endpoint ID to exclude from the broadcast.
     */
    private fun sendToAll(
        message: NetworkMessage,
        excludeEndpointId: String? = null
    ) {
        val allEndpointIds = EndpointRegistry.getDirectlyConnectedEndpoints().map { it.id }
        val recipientEndpointIds = if (excludeEndpointId != null) {
            allEndpointIds.filter { it != excludeEndpointId }
        } else {
            allEndpointIds
        }

        if (recipientEndpointIds.isNotEmpty()) {
            connectionsClient.sendPayload(
                recipientEndpointIds,
                Payload.fromBytes(message.toByteArray())
            )
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo
        ) {
            TopologyOptimizer.onEndpointFound(endpointId)
        }


        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "Endpoint lost: $endpointId")
            EndpointRegistry.remove(endpointId)
        }
    }

    private val connectionLifecycleCallback: ConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(
                endpointId: String, connectionInfo: ConnectionInfo
            ) {
                val endpoint = EndpointRegistry.get(endpointId)
                Log.d(TAG, "onConnectionInitiated from ${endpoint.id}")
                if (EndpointRegistry.getDirectlyConnectedEndpoints().size >= MAX_CONNECTIONS_HARDWARE_LIMIT) {
                    Log.w(
                        TAG,
                        "At connection limit. Finding a redundant peer to make room for $endpointId."
                    )
                    val redundantPeer = TopologyOptimizer.findRedundantPeer()
                    if (redundantPeer != null) {
                        Log.i(
                            TAG,
                            "Making room for $endpointId by disconnecting from redundant peer ${redundantPeer.id}"
                        )
                        disconnectFromEndpoint(redundantPeer.id)
                        connectionsClient.acceptConnection(endpointId, payloadCallback)
                    } else {
                        Log.e(
                            TAG,
                            "At connection limit but couldn't find a redundant peer. Rejecting $endpointId."
                        )
                        connectionsClient.rejectConnection(endpointId)
                    }
                    return
                }
                Log.d(TAG, "Accepting connection from $endpointId")
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                // Don't update the distance yet.
            }

            override fun onConnectionResult(
                endpointId: String, resolution: ConnectionResolution
            ) {
                val endpoint = EndpointRegistry.get(endpointId)
                if (resolution.status.isSuccess) {
                    Log.i(TAG, "Successfully connected to $endpointId")
                    endpoint.distance = 1
                } else {
                    Log.w(
                        TAG,
                        "onConnectionResult failed to connect to $endpointId: ${resolution.status.statusMessage}"
                    )
                    endpoint.distance?.let { dist ->
                        if (dist < 2) {
                            endpoint.distance = null
                        }
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                Log.w(TAG, "onDisconnected from $endpointId")
                EndpointRegistry.remove(endpointId)
            }
        }
}
