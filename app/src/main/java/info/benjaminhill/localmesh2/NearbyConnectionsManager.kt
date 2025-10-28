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
import kotlin.time.Duration.Companion.seconds

/**
 * Manages all low-level interactions with the Google Nearby Connections API.
 * This class is responsible for advertising, discovering, and managing the lifecycle of connections.
 * It decides when to connect, disconnect, or reshuffle.
 */
object NearbyConnectionsManager {
    private const val TAG = "P2P"
    private const val SERVICE_ID = "info.benjaminhill.localmesh2"

    private const val MAX_CONNECTIONS_HARDWARE_LIMIT = 7

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
    private lateinit var gossipJob: Job
    private lateinit var reshuffleJob: Job
    private lateinit var purgeJob: Job
    private lateinit var connectionsClient: ConnectionsClient
    fun initialize(newContext: Context, newScope: CoroutineScope) {
        this.appContext = newContext.applicationContext
        this.scope = newScope
        this.localId = CachedPrefs.getId(this.appContext)
        this.connectionsClient = Nearby.getConnectionsClient(this.appContext)
        this.messageCleanupJob = this.scope.launch {
            while (true) {
                delay(1.minutes)
                val now = System.currentTimeMillis()
                val thirtyMinutesAgo = now - 30.minutes.inWholeMilliseconds
                seenMessageIds.entries.removeAll { it.value < thirtyMinutesAgo }
            }
        }
        this.gossipJob = this.scope.launch {
            while (true) {
                delay(1.minutes + (0..20).random().seconds)
                broadcastGossip()
            }
        }
        this.reshuffleJob = this.scope.launch {
            while (true) {
                delay(30.seconds  + (0..10).random().seconds)
                reshuffle()
            }
        }
        this.purgeJob = this.scope.launch {
            while (true) {
                delay(1.minutes)
                val fiveMinutesAgo = System.currentTimeMillis() - 5.minutes.inWholeMilliseconds
                EndpointRegistry.getAllKnownEndpoints()
                    .filter { it.lastUpdatedTs < fiveMinutesAgo }
                    .forEach {
                        Log.i(TAG, "Purging stale endpoint: ${it.id}")
                        EndpointRegistry.remove(it.id)
                    }
            }
        }
    }

    fun startAdvertising() {
        Log.i(TAG, "startAdvertising()...")
        connectionsClient.stopAdvertising()

        val advertisingOptions =
            AdvertisingOptions.Builder().setStrategy(
                // Match the discovery strategy
                DISCOVERY_OPTIONS.strategy
            ).build()

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
            // TopologyOptimizer.initialize(this.scope, ::disconnectFromEndpoint, ::requestConnection)
        }.addOnFailureListener { e ->
            Log.e(TAG, "$localId failed to start discovery", e)
        }
    }

    fun stop() {
        Log.w(TAG, "NearbyConnectionsManager.stop() called.")
        messageCleanupJob.cancel()
        gossipJob.cancel()
        reshuffleJob.cancel()
        purgeJob.cancel()
        connectionsClient.stopAllEndpoints()
    }

    private fun broadcastGossip() {
        Log.d(TAG, "Time to gossip")
        val connectedEndpoints = EndpointRegistry.getDirectlyConnectedEndpoints()
        if (connectedEndpoints.isEmpty()) {
            return
        }
        val message = NetworkMessage(
            sendingNodeId = localId,
            messageType = NetworkMessage.Companion.Types.GOSSIP,
            peers = connectedEndpoints.map { it.id }.toSet(),
            distance = mapOf(localId to 0),
        )
        broadcastInternal(message)
    }


    private fun reshuffle() {
        val worstNode = findWorstDistantNode() ?: return
        val redundantPeer = findRedundantPeer() ?: return

        if (redundantPeer.id != worstNode.id) {
            Log.i(
                TAG,
                "Reshuffling: disconnecting from ${redundantPeer.id} to connect to ${worstNode.id}"
            )
            disconnectFromEndpoint(redundantPeer.id)
            requestConnection(worstNode.id)
        }
    }

    private fun findRedundantPeer(): Endpoint? {
        val immediatePeers = EndpointRegistry.getDirectlyConnectedEndpoints()
        if (immediatePeers.size < 3) {
            return null
        }

        // Calculate redundancy scores for each peer
        val redundancyScores = immediatePeers.associateWith { peerToScore ->
            immediatePeers
                .filter { it != peerToScore }
                .count { otherPeer ->
                    otherPeer.immediatePeerIds?.contains(peerToScore.id) ?: false
                }
        }

        // Find the peer with the highest score, using immediateConnections as a tie-breaker
        return immediatePeers.maxWithOrNull(
            compareBy(
                { redundancyScores[it] ?: 0 }, // Primary criteria: redundancy score
            { it.immediateConnections ?: 0 }  // Secondary criteria: connection count
        ))
    }

    private fun findWorstDistantNode(): Endpoint? {
        val fiveMinutesAgo = System.currentTimeMillis() - 5.minutes.inWholeMilliseconds
        return EndpointRegistry.getAllKnownEndpoints()
            .filter { it.lastUpdatedTs > fiveMinutesAgo }
            .filter {
                val dist = it.distance
                dist == null || dist > 2
            }
            .maxByOrNull { it.distance ?: Int.MAX_VALUE }
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

    private fun broadcastInternal(message: NetworkMessage) {
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
                        receivedMessage.peers?.let {
                            Log.d(
                                TAG,
                                "Received gossip from ${receivedMessage.sendingNodeId} via $endpointId with peers: $it"
                            )
                            EndpointRegistry.get(
                                receivedMessage.sendingNodeId,
                                autoUpdateTs = false
                            ).immediatePeerIds = it
                        }

                        // Update distances based on the received message
                        receivedMessage.distance.forEach { (endpointId, distance) ->
                            val endpoint =
                                EndpointRegistry.get(endpointId, autoUpdateTs = false)
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

    private fun requestConnection(endpointId: String) {
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
    private fun disconnectFromEndpoint(endpointId: String) {
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
            val endpoint = EndpointRegistry.get(endpointId)
            val distance = endpoint.distance ?: Int.MAX_VALUE
            if (distance > 2) {
                requestConnection(endpointId)
            }
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
                    val redundantPeer = findRedundantPeer()
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