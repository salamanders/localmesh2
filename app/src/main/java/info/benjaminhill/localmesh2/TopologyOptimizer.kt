package info.benjaminhill.localmesh2

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * The "brains" of the network. It contains all the
 * high-level logic for analyzing network health and making decisions to optimize the network
 * topology.
 */
object TopologyOptimizer {

    private const val TAG = "P2P_Opt"
    private lateinit var scope: CoroutineScope
    private lateinit var localId: String

    /** Reference to the low-level connection manager */
    private lateinit var nearbyConnectionsManager: NearbyConnectionsManager

    private lateinit var ensureConnectionsJob: Job

    /** A set of peers that are available for connection. */
    private val availablePeers = ConcurrentHashMap.newKeySet<String>()

    fun initialize(
        newScope: CoroutineScope,
        newNearbyConnectionsManager: NearbyConnectionsManager,
        newLocalId: String,
    ) {
        scope = newScope
        nearbyConnectionsManager = newNearbyConnectionsManager
        localId = newLocalId

        ensureConnectionsJob = scope.launch {
            while (true) {
                val connectedPeers = EndpointRegistry.getDirectlyConnectedEndpoints()
                if (connectedPeers.size < 3) {
                    var connected = false
                    while (!connected) {
                        val peerToConnect = availablePeers.filter { candidate ->
                            connectedPeers.none { it.id == candidate }
                        }.randomOrNull()

                        if (peerToConnect != null) {
                            Log.i(
                                TAG,
                                "Below connection threshold, attempting to connect to $peerToConnect"
                            )
                            connected = try {
                                nearbyConnectionsManager.requestConnection(peerToConnect)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to connect to $peerToConnect", e)
                                delay(3.seconds)
                                false
                            }
                        }
                    }
                }
                delay(30.seconds)
            }
        }
    }

    fun stop() {
        Log.w(TAG, "TopologyOptimizer.stop() called.")
        if (::ensureConnectionsJob.isInitialized) ensureConnectionsJob.cancel()
    }

    fun onEndpointFound(endpointId: String) {
        availablePeers.add(endpointId)
    }
}
