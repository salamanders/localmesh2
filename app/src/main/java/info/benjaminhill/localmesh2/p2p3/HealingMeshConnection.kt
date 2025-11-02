package info.benjaminhill.localmesh2.p2p3

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import timber.log.Timber
import kotlin.random.Random
import kotlin.time.ExperimentalTime

/**
 * Manages a self-healing mesh network using the Google Nearby Connections API.
 *
 * Core Principles:
 * 1.  **Simplicity:** Logic is kept straightforward. Nodes only care about their direct connections.
 * 2.  **Resilience:** The network automatically heals by forming new connections when existing ones are lost.
 * 3.  **Decentralization:** No central authority. All nodes are equal peers.
 *
 * Connection Logic:
 * - Each node aims to maintain between `MIN_CONNECTIONS` and `MAX_CONNECTIONS`.
 * - It continuously discovers nearby peers.
 * - If below `MIN_CONNECTIONS`, it attempts to connect to a discovered peer it isn't already connected to.
 * - It accepts incoming connections if it's below `MAX_CONNECTIONS`.
 *
 * Communication:
 * - Uses a gossip protocol to share network topology information (`EndpointRegistry`).
 * - Prevents broadcast storms by de-duplicating messages and using breadcrumbs to avoid loops.
 */
class HealingMeshConnection(appContext: Context) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val localHumanReadableName: String = randomString(5)
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private var maintenanceTicker: Job? = null
    private var gossipTicker: Job? = null

    /** Map of MessageUuids to the timestamp we first saw them. Used for de-duplication. */
    private val seenMessageIds = mutableMapOf<String, Long>()

    /** The set of peers we are currently trying to connect to. */
    private val pendingConnections = mutableSetOf<String>()

    /** The set of peers we are physically connected to. */
    private val directConnections = mutableSetOf<String>()

    /** The set of peers we can see right now. */
    private val discoverablePeers = mutableSetOf<String>()

    /** Handles all connection lifecycle events, both incoming and outgoing. */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Timber.i("onConnectionInitiated from ${connectionInfo.endpointName} ($endpointId)")
            EndpointRegistry[endpointId].apply {
                refreshLastUpdate()
                setName(connectionInfo.endpointName)
            }
            if (directConnections.size < MAX_CONNECTIONS) {
                Timber.i("Accepting connection from $endpointId")
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            } else {
                Timber.i("Rejecting connection from $endpointId, already have ${directConnections.size} connections.")
                connectionsClient.rejectConnection(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingConnections.remove(endpointId)
            if (result.status.isSuccess) {
                Timber.i("onConnectionResult: SUCCESS for $endpointId")
                directConnections.add(endpointId)
                EndpointRegistry[endpointId].apply {
                    setIsPeer() // Sets distance to 1
                    refreshLastUpdate()
                }
            } else {
                Timber.w("onConnectionResult: FAILURE for $endpointId, status: ${result.status.statusMessage} (${result.status.statusCode})")
                directConnections.remove(endpointId)
                // Don't mark as not-peer, they might still be reachable through gossip
            }
        }

        override fun onDisconnected(endpointId: String) {
            Timber.i("onDisconnected from $endpointId")
            directConnections.remove(endpointId)
            EndpointRegistry[endpointId].setIsNotPeer() // Sets distance to null
        }
    }

    /** Handles incoming data from connected peers. */
    @OptIn(ExperimentalSerializationApi::class)
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Timber.d("onPayloadReceived from $endpointId")
            if (payload.type != Payload.Type.BYTES) {
                Timber.w("Received non-bytes payload from $endpointId, ignoring.")
                return
            }
            val message = Cbor.decodeFromByteArray<NetworkMessage>(payload.asBytes()!!)

            // De-duplication
            if (message.id in seenMessageIds) {
                Timber.d("Ignoring duplicate message ${message.id} from $endpointId")
                return
            }
            seenMessageIds[message.id] = System.currentTimeMillis()

            processBreadcrumbs(message.breadCrumbs)

            if (message.displayTarget == localHumanReadableName) {
                Timber.i("COMMAND: Received command: $message")
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
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    if (info.serviceId == MESH_SERVICE_ID) {
                        Timber.i("Discovery: Found endpoint $endpointId (${info.endpointName})")
                        discoverablePeers.add(endpointId)
                    }
                }

                override fun onEndpointLost(endpointId: String) {
                    Timber.i("Discovery: Lost endpoint $endpointId")
                    discoverablePeers.remove(endpointId)
                }
            },
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        ).addOnSuccessListener {
            Timber.i("Discovery started.")
        }.addOnFailureListener { e ->
            Timber.e(e, "Discovery failed.")
        }

        maintenanceTicker = scope.launch {
            while (isActive) {
                runMaintenance()
                delay(5_000)
            }
        }

        gossipTicker = scope.launch {
            while (isActive) {
                delay(15_000L + Random.nextLong(10_000L))
                sendGossip()
            }
        }
    }

    private fun runMaintenance() {
        pruneMessageCache()
        pruneEndpointRegistry()
        healConnections()
    }

    private fun healConnections() {
        if (directConnections.size < MIN_CONNECTIONS) {
            val target = discoverablePeers.firstOrNull { it !in directConnections && it !in pendingConnections }
            if (target != null) {
                Timber.i("HEAL: Attempting to connect to $target")
                pendingConnections.add(target)
                connectionsClient.requestConnection(
                    localHumanReadableName,
                    target,
                    connectionLifecycleCallback
                ).addOnFailureListener { e ->
                    Timber.w(e, "HEAL: Failed to request connection to $target")
                    pendingConnections.remove(target)
                }
            }
        }
    }

    private fun pruneMessageCache() {
        val now = System.currentTimeMillis()
        val iterator = seenMessageIds.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > MESSAGE_CACHE_EXPIRY_MS) {
                iterator.remove()
            }
        }
    }

    private fun sendGossip() {
        Timber.d("GOSSIP: Sending gossip")
        val gossip = NetworkMessage(
            breadCrumbs = listOf(localHumanReadableName to System.currentTimeMillis()),
            displayTarget = null
        )
        seenMessageIds[gossip.id] = System.currentTimeMillis()
        forwardMessage(gossip, null)
    }

    private fun pruneEndpointRegistry() {
        EndpointRegistry.prune()
    }

    private fun processBreadcrumbs(crumbs: List<Pair<String, Long>>) {
        crumbs.forEachIndexed { i, (peerId, timestamp) ->
            val hopCount = i + 1
            val existing = EndpointRegistry[peerId]
            if (hopCount < existing.distance ?: Int.MAX_VALUE) {
                Timber.d("Updating $peerId to distance $hopCount")
                existing.apply {
                    setDistance(hopCount)
                    setLastSeen(timestamp)
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun forwardMessage(message: NetworkMessage, fromEndpointId: String?) {
        val newCrumbs = message.breadCrumbs + (localHumanReadableName to System.currentTimeMillis())
        val msgToForward = message.copy(breadCrumbs = newCrumbs)
        val payload = Payload.fromBytes(Cbor.encodeToByteArray(msgToForward))

        val pathIds = newCrumbs.map { it.first }
        val targets = directConnections.filter { it != fromEndpointId && it !in pathIds }

        if (targets.isNotEmpty()) {
            Timber.d("Forwarding message ${message.id} from $fromEndpointId to $targets")
            connectionsClient.sendPayload(targets, payload)
        }
    }

    fun getDirectConnectionCount(): Int = directConnections.size

    fun broadcast(message: NetworkMessage) {
        Timber.d("BROADCAST: $message")
        seenMessageIds[message.id] = System.currentTimeMillis()
        forwardMessage(message, null)
    }

    fun stopNetworking() {
        maintenanceTicker?.cancel()
        gossipTicker?.cancel()
        Timber.i("Manually stopped")
        connectionsClient.stopAllEndpoints()
        EndpointRegistry.clear()
        seenMessageIds.clear()
        pendingConnections.clear()
        directConnections.clear()
        discoverablePeers.clear()
    }

    companion object {
        private const val MESH_SERVICE_ID = "info.benjaminhill.localmesh2.MESH"
        private val STRATEGY = Strategy.P2P_CLUSTER
        private const val MIN_CONNECTIONS = 2
        private const val MAX_CONNECTIONS = 4
        private const val MESSAGE_CACHE_EXPIRY_MS = 2 * 60 * 1000 // 2 minutes
    }
}