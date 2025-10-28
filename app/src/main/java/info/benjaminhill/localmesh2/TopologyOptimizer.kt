package info.benjaminhill.localmesh2

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
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

    /** Reference to the low-level connection manager */
    private lateinit var nearbyConnectionsManager: NearbyConnectionsManager

    private lateinit var purgeJob: Job
    private lateinit var ensureConnectionsJob: Job

    /** A set of peers that are available for connection. */
    private val availablePeers = ConcurrentHashMap.newKeySet<String>()
    private var clearedAfterFirstConnection = false

    fun initialize(
        newScope: CoroutineScope,
        newNearbyConnectionsManager: NearbyConnectionsManager,
        newLocalId: String,
    ) {
        scope = newScope
        nearbyConnectionsManager = newNearbyConnectionsManager
        localId = newLocalId

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

        ensureConnectionsJob = scope.launch {
            while (true) {
                val connectedPeers = EndpointRegistry.getDirectlyConnectedEndpoints()

                when (connectedPeers.size) {
                    0 -> {
                        // Lost connection, reset the flag so we clear peers again after we get one.
                        clearedAfterFirstConnection = false
                        val peerToConnect = availablePeers.firstOrNull()
                        if (peerToConnect != null) {
                            Log.i(TAG, "Attempting mandatory first connection to $peerToConnect")
                            nearbyConnectionsManager.requestConnection(peerToConnect)
                            availablePeers.remove(peerToConnect)
                        }
                    }

                    1 -> {
                        if (!clearedAfterFirstConnection) {
                            Log.i(TAG, "First connection established. Clearing available peers for a fresh list.")
                            availablePeers.clear()
                            clearedAfterFirstConnection = true
                        }
                        // Now look for a second connection from the fresh list.
                        val peerToConnect =
                            availablePeers.firstOrNull { candidate -> connectedPeers.none { it.id == candidate } }
                        if (peerToConnect != null) {
                            Log.i(TAG, "Attempting best-effort second connection to $peerToConnect")
                            nearbyConnectionsManager.requestConnection(peerToConnect)
                            availablePeers.remove(peerToConnect)
                        }
                    }

                    else -> { // >= 2 connections
                        // We are sufficiently connected. Reset the flag for the future.
                        clearedAfterFirstConnection = false
                    }
                }
                delay(30.seconds + (0..15).random().seconds)
            }
        }
    }

    fun stop() {
        Log.w(TAG, "TopologyOptimizer.stop() called.")
        if (::purgeJob.isInitialized) purgeJob.cancel()
        if (::ensureConnectionsJob.isInitialized) ensureConnectionsJob.cancel()
    }

    fun onEndpointFound(endpointId: String) {
        availablePeers.add(endpointId)
    }
}
