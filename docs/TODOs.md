// TODO: Get the connection count from the endpoint, use that to decide.
//            val theirConnectionCount = discoveredEndpoints[endpointId] ?: return
//
//            if (topologyOptimizer.shouldConnectTo(
//                    endpointId,
//                    theirConnectionCount,
//                    connectedEndpoints.size,
//                    connectedEndpoints
//                )
//            ) {
//                Log.i(
//                    TAG,
//                    "TopologyOptimizer decided to connect to $endpointId. Requesting connection."
//                )
//
//            }`



//
//import android.util.Log
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//import kotlin.time.Duration.Companion.minutes
//
///**
// * Contains the high-level logic for maintaining the desired mesh topology.
// * This class is intentionally isolated from the Nearby Connections API and operates on pure data
// * (endpoint IDs, connection counts). It acts as the "brain" for the mesh, telling the
// * [NearbyConnectionsManager] when to connect, disconnect, or reshuffle peers to optimize
// * the network structure. The strategy is passed in via function references in the constructor.
// */
//object TopologyOptimizer {
//    private lateinit var scope: CoroutineScope
//    private lateinit var reshuffleJob: Job
//
//    // Tells the manager to disconnect from a peer.
//    private lateinit var disconnectFromEndpoint: (String) -> Unit
//
//    // Tells the manager to request a connection to a peer.
//    private lateinit var requestConnection: (String) -> Unit
//
//    fun initialize(
//        newScope: CoroutineScope,
//        newDisconnectFromEndpoint: (String) -> Unit,
//        newRequestConnection: (String) -> Unit
//    ) {
//        Log.i(TAG, "TopologyOptimizer.initialize()")
//        scope = newScope
//        disconnectFromEndpoint = newDisconnectFromEndpoint
//        requestConnection = newRequestConnection
//
//        reshuffleJob = scope.launch {
//            while (true) {
//                delay(1.minutes)
//                val myConnectionCount = EndpointRegistry.getDirectlyConnectedEndpoints().size
//                if (myConnectionCount < MIN_CONNECTIONS) {
//                    // Not enough connections to reshuffle.
//                    continue
//                }
//
//                // If all my peers are well-connected, drop one to find a new, less-connected peer.
//                // TODO: Pick a peer that if you disconnect will still reach you through another route
//                val immediatePeers = EndpointRegistry.getDirectlyConnectedEndpoints()
//
//                // Are all peers "well-connected"?  If not, keep what you have.
//                if (immediatePeers.any { (it.immediateConnections ?: 0) <= MIN_CONNECTIONS }) {
//                    continue
//                }
//
//                immediatePeers.randomOrNull()?.let { disconnectCandidate ->
//                    Log.i(
//                        TAG,
//                        "Reshuffling: all peers are well-connected. Dropping $disconnectCandidate."
//                    )
//                    disconnectFromEndpoint(disconnectCandidate.id)
//                }
//
//            }
//        }
//    }
//
//    fun shouldConnectTo(
//        endpointId: String,
//        theirConnectionCount: Int,
//        myConnectionCount: Int,
//        connectedEndpoints: Set<String>
//    ): Boolean {
//        Log.i(TAG, "shouldConnectTo()")
//        if (connectedEndpoints.contains(endpointId)) {
//            return false // Already connected
//        }
//
//        if (myConnectionCount >= MAX_CONNECTIONS) {
//            return false // I'm full
//        }
//        if (theirConnectionCount >= MAX_CONNECTIONS) {
//            return false // They're full
//        }
//
//        // Connect if either of us is below the minimum threshold.
//        return (myConnectionCount < MIN_CONNECTIONS || theirConnectionCount < MIN_CONNECTIONS).also {
//            Log.i(TAG, "shouldConnectTo() returning $it")
//        }
//    }
//
//    fun onDisconnected(disconnectedEndpointId: String) {
//        Log.i(TAG, "onDisconnected($disconnectedEndpointId)")
//        if (NearbyConnectionsManager.getDirectlyConnectedEndpoints().size < MIN_CONNECTIONS) {
//            Log.w(
//                TAG,
//                "Below minimum connections (${NearbyConnectionsManager.getDirectlyConnectedEndpoints().size}), trying to find a new peer."
//            )
//            // Find the best candidate to connect to.
//            val bestCandidate = NearbyConnectionsManager.getAllKnownEndpoints().filter {
//                    // Far (more than 2 hops or unknown
//                    (it.distance ?: 100) > 2
//                }.filter {
//                    // Not full
//                    (it.immediateConnections ?: 0) < MAX_CONNECTIONS
//                }
//
//                .sortedWith(compareByDescending<Endpoint> {
//                    it.distance ?: 100
//                } // Primary sort by distance desc
//                    .thenByDescending {
//                        it.immediateConnections ?: 0
//                    } // Secondary sort by immediate connections desc
//                ).firstOrNull()
//
//            if (bestCandidate != null) {
//                Log.i(TAG, "Aggressively reconnecting to ${bestCandidate.id}")
//                requestConnection(bestCandidate.id)
//            } else {
//                Log.w(TAG, "No suitable candidate for reconnection found.")
//            }
//        }
//    }
//
//
//    private const val TAG = "P2PTopo"
//    private const val MIN_CONNECTIONS = 2
//    private const val MAX_CONNECTIONS = 5
//
//}
