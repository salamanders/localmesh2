package info.benjaminhill.localmesh2

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Contains the high-level logic for maintaining the desired mesh topology.
 * This class is intentionally isolated from the Nearby Connections API and operates on pure data
 * (endpoint IDs, connection counts). It acts as the "brain" for the mesh, telling the
 * [NearbyConnectionsManager] when to connect, disconnect, or reshuffle peers to optimize
 * the network structure. The strategy is passed in via function references in the constructor.
 */
class TopologyOptimizer(
    private val scope: CoroutineScope,
    // A function reference to tell the manager to disconnect from a peer.
    private val disconnectFromEndpoint: (String) -> Unit,
    // A function reference to tell the manager to request a connection to a peer.
    private val requestConnection: (String) -> Unit
) {
    private var reshuffleJob: Job? = null

    /** Kicks off the periodic reshuffling job. */
    fun start(connectedEndpoints: Set<String>, discoveredEndpoints: Map<String, Int>) {
        reshuffleJob = scope.launch {
            while (true) {
                delay(1.minutes)
                val myConnectionCount = connectedEndpoints.size
                if (myConnectionCount < MIN_CONNECTIONS) {
                    // Not enough connections to reshuffle.
                    continue
                }

                // If all my peers are well-connected, drop one to find a new, less-connected peer.
                val allPeersWellConnected = connectedEndpoints.all { endpointId ->
                    (discoveredEndpoints[endpointId] ?: 0) >= 3
                }

                if (allPeersWellConnected) {
                    val randomEndpoint = connectedEndpoints.randomOrNull()
                    if (randomEndpoint != null) {
                        Log.i(
                            TAG,
                            "Reshuffling: all peers are well-connected. Dropping $randomEndpoint."
                        )
                        disconnectFromEndpoint(randomEndpoint)
                    }
                }
            }
        }
    }

    fun stop() {
        reshuffleJob?.cancel()
    }

    fun shouldConnectTo(
        endpointId: String,
        theirConnectionCount: Int,
        myConnectionCount: Int,
        connectedEndpoints: Set<String>
    ): Boolean {
        if (connectedEndpoints.contains(endpointId)) {
            return false // Already connected
        }

        if (myConnectionCount >= MAX_CONNECTIONS) {
            return false // I'm full
        }
        if (theirConnectionCount >= MAX_CONNECTIONS) {
            return false // They're full
        }

        // Connect if either of us is below the minimum threshold.
        return myConnectionCount < MIN_CONNECTIONS || theirConnectionCount < MIN_CONNECTIONS
    }

    fun onDisconnected(connectedEndpoints: Set<String>, discoveredEndpoints: Map<String, Int>) {
        if (connectedEndpoints.size < MIN_CONNECTIONS) {
            Log.w(
                TAG,
                "Below minimum connections (${connectedEndpoints.size}), trying to find a new peer."
            )
            // Find the best candidate to connect to.
            val bestCandidate = discoveredEndpoints.entries
                .filter { !connectedEndpoints.contains(it.key) } // Not already connected
                .filter { it.value < MAX_CONNECTIONS }           // Not full
                .minByOrNull { it.value }                        // Least connected

            if (bestCandidate != null) {
                Log.i(TAG, "Aggressively reconnecting to ${bestCandidate.key}")
                requestConnection(bestCandidate.key)
            } else {
                Log.w(TAG, "No suitable candidate for reconnection found.")
            }
        }
    }

    companion object {
        private const val TAG = "TopologyOptimizer"
        private const val MIN_CONNECTIONS = 2
        private const val MAX_CONNECTIONS = 4
    }
}
