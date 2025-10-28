package info.benjaminhill.localmesh2

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * The "brains" of the network. It contains all the
 * high-level logic for analyzing network health and making decisions to optimize the network
 * topology.
 */
object TopologyOptimizer {

    private const val TAG = "P2P_TO"
    private lateinit var gossipJob: Job
    private lateinit var reshuffleJob: Job
    private lateinit var localId: String
    private lateinit var broadcastInternal: (NetworkMessage) -> Unit
    private lateinit var disconnectFromEndpoint: (String) -> Unit
    private lateinit var requestConnection: (String) -> Unit

    fun initialize(
        scope: CoroutineScope,
        localId: String,
        broadcastInternal: (NetworkMessage) -> Unit,
        disconnectFromEndpoint: (String) -> Unit,
        requestConnection: (String) -> Unit
    ) {
        this.localId = localId
        this.broadcastInternal = broadcastInternal
        this.disconnectFromEndpoint = disconnectFromEndpoint
        this.requestConnection = requestConnection

        this.gossipJob = scope.launch {
            while (true) {
                delay(10_000)
                broadcastGossip()
            }
        }
        this.reshuffleJob = scope.launch {
            while (true) {
                delay(30_000)
                reshuffle()
            }
        }
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
        )
        broadcastInternal(message)
    }

    /**
     * Called when a new endpoint is discovered.
     * Decides whether to connect to the new endpoint.
     */
    fun onEndpointFound(endpointId: String) {
        val endpoint = EndpointRegistry.get(endpointId)
        val distance = endpoint.distance ?: Int.MAX_VALUE
        if (distance > 2) {
            requestConnection(endpointId)
        }
    }

    /**
     * Called when a connection is initiated from a remote endpoint.
     * Decides whether to accept the connection.
     */
    fun onConnectionInitiated(endpointId: String, acceptConnection: () -> Unit, rejectConnection: () -> Unit) {
        if (EndpointRegistry.getDirectlyConnectedEndpoints().size >= NearbyConnectionsManager.MAX_CONNECTIONS_HARDWARE_LIMIT) {
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
                acceptConnection()
            } else {
                Log.e(
                    TAG,
                    "At connection limit but couldn't find a redundant peer. Rejecting $endpointId."
                )
                rejectConnection()
            }
            return
        }
        Log.d(TAG, "Accepting connection from $endpointId")
        acceptConnection()
    }

    fun onDisconnected(endpointId: String) {
        // No action needed here, the reshuffle logic will take care of it.
    }

    fun stop() {
        if (::gossipJob.isInitialized) {
            gossipJob.cancel()
        }
        if (::reshuffleJob.isInitialized) {
            reshuffleJob.cancel()
        }
    }

    /**
     * Periodically re-evaluates the network topology and makes changes to improve it.
     * For example, it will disconnect from a redundant peer to connect to a more distant node.
     */
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
            )
        )
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
}
