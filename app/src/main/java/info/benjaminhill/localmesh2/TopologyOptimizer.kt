package info.benjaminhill.localmesh2

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The "brains" of the network. It contains all the
 * high-level logic for analyzing network health and making decisions to optimize the network
 * topology.
 */
object TopologyOptimizer {

    private const val TAG = "P2P"
    private lateinit var scope: CoroutineScope
    private lateinit var localId: String
    private lateinit var gossipJob: Job
    private lateinit var reshuffleJob: Job
    private lateinit var purgeJob: Job

    /** Reference to the low-level connection manager */
    private lateinit var nearbyConnectionsManager: NearbyConnectionsManager

    fun initialize(
        newScope: CoroutineScope,
        newNearbyConnectionsManager: NearbyConnectionsManager,
        newLocalId: String,
    ) {
        scope = newScope
        nearbyConnectionsManager = newNearbyConnectionsManager
        localId = newLocalId

        gossipJob = scope.launch {
            while (true) {
                delay(1.minutes + (0..20).random().seconds)
                broadcastGossip()
            }
        }
        reshuffleJob = scope.launch {
            while (true) {
                delay(30.seconds + (0..10).random().seconds)
                reshuffle()
            }
        }
        purgeJob = scope.launch {
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

    fun stop() {
        Log.w(TAG, "TopologyOptimizer.stop() called.")
        if (::gossipJob.isInitialized) gossipJob.cancel()
        if (::reshuffleJob.isInitialized) reshuffleJob.cancel()
        if (::purgeJob.isInitialized) purgeJob.cancel()
    }

    /** Creates and sends a message to all peers containing the current node's knowledge of the network. */
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
        nearbyConnectionsManager.broadcastInternal(message)
    }

    /** Disconnects from a redundant peer to connect to a more distant node. */
    private fun reshuffle() {
        val worstNode = findWorstDistantNode() ?: return
        val redundantPeer = findRedundantPeer() ?: return

        if (redundantPeer.id != worstNode.id) {
            Log.i(
                TAG,
                "Reshuffling: disconnecting from ${redundantPeer.id} to connect to ${worstNode.id}"
            )
            nearbyConnectionsManager.disconnectFromEndpoint(redundantPeer.id)
            nearbyConnectionsManager.requestConnection(worstNode.id)
        }
    }

    /** Finds a peer that is already well-connected to the network. */
    fun findRedundantPeer(): Endpoint? {
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

    /** Finds a node that is far away or not yet connected. */
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

    /** When a new endpoint is found, decide if we should connect to it. */
    fun onEndpointFound(endpointId: String) {
        val endpoint = EndpointRegistry.get(endpointId)
        val distance = endpoint.distance ?: Int.MAX_VALUE
        if (distance > 2) {
            nearbyConnectionsManager.requestConnection(endpointId)
        }
    }
}
