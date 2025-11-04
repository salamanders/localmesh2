package info.benjaminhill.localmesh2.p2p

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.Payload
import kotlinx.serialization.ExperimentalSerializationApi
import timber.log.Timber
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
class HealingMesh(
    appContext: Context,
) : MeshConnection.MeshConnectionListener {

    private val meshConnection = MeshConnection(
        appContext,
        NetworkHolder.localHumanReadableName,
        this
    )

    // Handler for proactive connection management
    private val graphManagerHandler = Handler(Looper.getMainLooper())

    override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
        when (result.status.statusCode) {
            ConnectionsStatusCodes.STATUS_OK -> {
                Timber.i("onConnectionResult: SUCCESS for $endpointId")

                // Pruning logic: If we are over-capacity, prune a random, tenured connection.
                if (meshConnection.getEstablishedConnections().size > K_DEGREE) {
                    val nodeToPrune = meshConnection.getEstablishedConnections().entries
                        .filterNot { it.key == endpointId } // Don't prune the one we just added
                        .filter { it.value < Clock.System.now().minus(PRUNE_COOLDOWN) }
                        .randomOrNull()

                    if (nodeToPrune != null) {
                        Timber.i("PRUNE: Over capacity. Pruning ${nodeToPrune.key}")
                        meshConnection.disconnectFromEndpoint(nodeToPrune.key)
                    } else {
                        Timber.i("PRUNE: Over capacity, but no connections to prune.")
                    }
                } else {
                    Timber.d("onConnectionResult: Not over capacity (${meshConnection.getEstablishedConnections().size}/$K_DEGREE).")
                }
            }
            ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                Timber.w("onConnectionResult: REJECTED for $endpointId")
            }
            else -> {
                Timber.w("onConnectionResult: FAILURE for $endpointId, status: ${result.status.statusMessage} (${result.status.statusCode})")
            }
        }
    }

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


    fun start() {
        meshConnection.start()
        startGraphManagerLoop()
    }

    fun stop() {
        stopGraphManagerLoop()
        meshConnection.stop()
        NetworkMessageRegistry.clear()
    }

    fun broadcast(message: NetworkMessage) {
        meshConnection.broadcast(message)
    }

    fun getEstablishedConnectionsCount(): Int = meshConnection.getEstablishedConnections().size

    private val graphManagerRunnable = Runnable {
        proactivelyMaintainGraph()
        startGraphManagerLoop() // Re-schedule itself
    }

    private fun startGraphManagerLoop() {
        graphManagerHandler.postDelayed(
            graphManagerRunnable,
            MANAGER_LOOP_DELAY.inWholeMilliseconds
        )
    }

    private fun stopGraphManagerLoop() {
        graphManagerHandler.removeCallbacks(graphManagerRunnable)
    }

    private fun proactivelyMaintainGraph() {
        if (meshConnection.getEstablishedConnections().size < K_DEGREE) {
            val candidates = meshConnection.getDiscoveredEndpoints().keys
                .filterNot { meshConnection.getEstablishedConnections().containsKey(it) }
                .filterNot { meshConnection.getPendingConnections().contains(it) }

            if (candidates.isNotEmpty()) {
                val candidateId = candidates.random()
                Timber.i("proactivelyMaintainGraph: Current size is ${meshConnection.getEstablishedConnections().size} so attempting to connect to random $candidateId")
                meshConnection.requestConnection(candidateId)
            } else {
                Timber.w("proactivelyMaintainGraph: Current size is ${meshConnection.getEstablishedConnections().size} but no candidates to connect to.")
            }
        } else {
            Timber.d("proactivelyMaintainGraph: At capacity (${meshConnection.getEstablishedConnections().size}/$K_DEGREE), not seeking new connections.")
        }
    }

    companion object {
        // The degree of the regular graph. 3 is recommended for stability.
        private const val K_DEGREE = 3

        // Cooldown before a connection is eligible for pruning.
        private val PRUNE_COOLDOWN = 30.seconds

        // Proactive connection management loop delay.
        private val MANAGER_LOOP_DELAY = 5.seconds // Check every 5 seconds
    }
}
