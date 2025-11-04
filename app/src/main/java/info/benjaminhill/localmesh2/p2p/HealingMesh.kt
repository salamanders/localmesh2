package info.benjaminhill.localmesh2.p2p

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.Payload
import kotlinx.serialization.ExperimentalSerializationApi
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Implements the high-level logic for a self-healing mesh network.
 * This class sits on top of `MeshConnection` and is responsible for:
 * - Maintaining a target number of connections (a "regular graph" of degree `K_DEGREE`).
 * - Pruning excess connections if the number of peers exceeds `K_DEGREE`.
 * - Proactively seeking new connections if the number of peers drops below `K_DEGREE`.
 * - Handling message de-duplication to prevent infinite gossip loops.
 * - Forwarding messages to the rest of the mesh.
 */
@OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
class HealingMesh(
    appContext: Context,
) : MeshConnection.MeshConnectionListener {

    /** Handles the low-level connection mechanics (discovery, connection, payload transfer). */
    private val meshConnection = MeshConnection(
        appContext = appContext,
        listener = this
    )

    /** Handler to run the periodic graph maintenance task. */
    private val graphManagerHandler = Handler(Looper.getMainLooper())

    /**
     * Callback from `MeshConnection` when a connection attempt resolves.
     * If successful, checks if the mesh is over-capacity and prunes a connection if necessary.
     */
    override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
        when (result.status.statusCode) {
            ConnectionsStatusCodes.STATUS_OK -> {
                Timber.i("SUCCESS for $endpointId")

                // Pruning logic: If we are over-capacity, prune a random connection.
                if (meshConnection.getEstablishedConnections().size > K_DEGREE) {
                    val nodeToPrune = meshConnection.getEstablishedConnections().keys
                        .filterNot { it == endpointId } // Don't prune the one we just added
                        .randomOrNull()

                    if (nodeToPrune != null) {
                        Timber.i("PRUNE: Over capacity (${meshConnection.getEstablishedConnections().size}/$K_DEGREE). Pruning a random endpoint $nodeToPrune")
                        meshConnection.disconnectFromEndpoint(nodeToPrune)
                    } else {
                        // This can happen in a race condition where another connection is dropped
                        // between the size check and now, or if we only have 1 connection (the new one).
                        Timber.w("PRUNE: Over capacity (${meshConnection.getEstablishedConnections().size}/$K_DEGREE), but no viable connection to prune.")
                    }
                } else {
                    Timber.d("Not over capacity (${meshConnection.getEstablishedConnections().size}/$K_DEGREE).")
                }
            }

            ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                Timber.w("REJECTED for $endpointId")
            }

            else -> {
                Timber.w("FAILURE for $endpointId, status: ${result.status.statusMessage} (${result.status.statusCode})")
            }
        }
    }

    /**
     * Callback from `MeshConnection` when a payload is received.
     * It de-duplicates the message, and if it's new, broadcasts it to the rest of the mesh.
     * Also handles any commands included in the message.
     */
    override fun onPayloadReceived(endpointId: String, payload: Payload) {
        Timber.d("onPayloadReceived from $endpointId")
        if (payload.type != Payload.Type.BYTES) {
            Timber.w("Received non-bytes payload from $endpointId, ignoring.")
            return
        }
        val message = NetworkMessage.fromByteArray(payload.asBytes()!!)

        if (!NetworkMessageRegistry.isFirstSeen(message)) {
            Timber.d("Ignoring duplicate message ${message.id} from $endpointId")
            return
        }
        meshConnection.broadcast(message)

        message.displayScreen?.let {
            Timber.i("COMMAND: Received command: $message")
            // TODO: Actually do something with the command
        }
    }

    /** Starts the `HealingMesh` service, which starts the underlying `MeshConnection` and the graph maintenance loop. */
    fun start() {
        meshConnection.start()
        startGraphManagerLoop()
    }

    /** Stops the `HealingMesh` service, including the maintenance loop and the underlying `MeshConnection`. */
    fun stop() {
        stopGraphManagerLoop()
        meshConnection.stop()
        NetworkMessageRegistry.clear()
    }

    /** Initiates a broadcast of a new message to the entire mesh. */
    fun broadcast(message: NetworkMessage) {
        meshConnection.broadcast(message)
    }

    fun getEstablishedConnectionsCount(): Int = meshConnection.getEstablishedConnections().size
    fun getDiscoveredEndpointsCount(): Int = meshConnection.getDiscoveredEndpoints().size
    fun getPendingConnectionsCount(): Int = meshConnection.getPendingConnections().size

    /** The `Runnable` task that executes the periodic graph maintenance logic. */
    private val graphManagerRunnable = Runnable {
        proactivelyMaintainGraph()
        startGraphManagerLoop() // Re-schedule itself
    }

    /** Schedules the next run of the graph maintenance task. */
    private fun startGraphManagerLoop() {
        graphManagerHandler.postDelayed(
            graphManagerRunnable,
            MANAGER_LOOP_DELAY.inWholeMilliseconds
        )
    }

    /** Cancels the scheduled graph maintenance task. */
    private fun stopGraphManagerLoop() {
        graphManagerHandler.removeCallbacks(graphManagerRunnable)
    }

    /**
     * The core "healing" logic. If the node is under-connected, it finds a random, viable
     * candidate from the list of discovered endpoints and attempts to establish a new connection.
     */
    private fun proactivelyMaintainGraph() {
        if (meshConnection.getEstablishedConnections().size < K_DEGREE) {
            val candidates = meshConnection.getDiscoveredEndpoints().keys
                .filterNot { meshConnection.getEstablishedConnections().containsKey(it) }
                .filterNot { meshConnection.getPendingConnections().contains(it) }

            if (candidates.isNotEmpty()) {
                val candidateId = candidates.random()
                Timber.i("Current size is ${meshConnection.getEstablishedConnections().size} so attempting to connect to random $candidateId")
                meshConnection.requestConnection(candidateId)
            } else {
                Timber.w("Current size is ${meshConnection.getEstablishedConnections().size} but no candidates to connect to.")
            }
        } else {
            Timber.d("At capacity (${meshConnection.getEstablishedConnections().size}/$K_DEGREE), not seeking new connections.")
        }
    }

    companion object {
        // The degree of the regular graph. 3 is recommended for stability.
        private const val K_DEGREE = 3

        // Proactive connection management loop delay.
        private val MANAGER_LOOP_DELAY = 5.seconds // Check every 5 seconds
    }
}
