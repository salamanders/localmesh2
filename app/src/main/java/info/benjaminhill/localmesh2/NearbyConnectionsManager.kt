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
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

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
                delay(10_000)
                broadcastGossip()
            }
        }
        this.reshuffleJob = this.scope.launch {
            while (true) {
                delay(30_000)
                reshuffle()
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
        connectionsClient.stopAllEndpoints()
    }

    private fun broadcastGossip() {
        Log.d(TAG, "Time to gossip")
        val connectedEndpoints = EndpointRegistry.getDirectlyConnectedEndpoints()
        if (connectedEndpoints.isEmpty()) {
            return
        }
        val gossip = Gossip(connectedEndpoints.map { it.id }.toSet())
        val message = NetworkMessage(
            sendingNodeId = localId,
            messageType = NetworkMessage.Companion.Types.GOSSIP,
            messageContent = Json.encodeToString(Gossip.serializer(), gossip),
        )
        val payloadBytes = message.toByteArray()
        seenMessageIds[message.messageId] = System.currentTimeMillis()
        Log.i(
            TAG,
            "Broadcasting gossip ${payloadBytes.size} bytes with uid ${message.messageId} to ${connectedEndpoints.map { it.id }}"
        )

        connectionsClient.sendPayload(
            connectedEndpoints.map { it.id },
            Payload.fromBytes(payloadBytes)
        )
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
        val connectedPeers = EndpointRegistry.getDirectlyConnectedEndpoints()
        if (connectedPeers.size < 3) {
            return null
        }
        return connectedPeers.maxByOrNull { it.immediateConnections ?: 0 }
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

    fun broadcast(type: NetworkMessage.Companion.Types, content: String) {
        val message = NetworkMessage(
            sendingNodeId = localId,
            messageType = type,
            messageContent = content,
        )
        val payloadBytes = message.toByteArray()
        seenMessageIds[message.messageId] = System.currentTimeMillis()
        Log.i(TAG, "Broadcasting ${payloadBytes.size} bytes with uid ${message.messageId}")

        connectionsClient.sendPayload(
            EndpointRegistry.getDirectlyConnectedEndpoints().map { it.id }.toList(),
            Payload.fromBytes(payloadBytes)
        )
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
                    "Received message from $endpointId with uid ${receivedMessage.messageId}."
                )

                if (receivedMessage.messageType == NetworkMessage.Companion.Types.GOSSIP) {
                    try {
                        val gossip = Json.decodeFromString(
                            Gossip.serializer(),
                            receivedMessage.messageContent!!
                        )
                        Log.d(TAG, "Received gossip from $endpointId with peers: ${gossip.peers}")

                        val senderEndpoint = EndpointRegistry.get(endpointId)
                        senderEndpoint.immediateConnections = gossip.peers.size

                        val newDistance = receivedMessage.hopCount + 1
                        gossip.peers.forEach { peerId ->
                            if (peerId != localId) {
                                val endpoint = EndpointRegistry.get(peerId)
                                if ((endpoint.distance ?: Int.MAX_VALUE) > newDistance) {
                                    endpoint.distance = newDistance
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse gossip message", e)
                    }
                }

                // Re-broadcast to other connected endpoints with incremented hop count.
                val otherEndpoints =
                    EndpointRegistry.getDirectlyConnectedEndpoints().filter { it.id != endpointId }
                if (otherEndpoints.isNotEmpty()) {
                    val messageToRebroadcast =
                        receivedMessage.copy(hopCount = receivedMessage.hopCount + 1)
                    connectionsClient.sendPayload(
                        otherEndpoints.map { it.id },
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
            // Keep the knowledge of the endpoint, but set its distance to null so it isn't in the list of immediate peers.
            // Don't update the TS, this doesn't count as "hearing from it"
            EndpointRegistry.get(endpointId, autoUpdateTs = false).distance = null
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
                    Log.i(
                        TAG,
                        "Rejecting connection from $endpointId, already at max connections (7+)."
                    )
                    connectionsClient.rejectConnection(endpointId)
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
                val endpoint = EndpointRegistry.get(endpointId, autoUpdateTs = false)
                Log.w(TAG, "onDisconnected from $endpointId, endpoint: $endpoint")
                endpoint.distance?.let { dist ->
                    if (dist < 2) {
                        endpoint.distance = null
                    }
                }
                // TopologyOptimizer.onDisconnected(endpointId)
            }
        }
}