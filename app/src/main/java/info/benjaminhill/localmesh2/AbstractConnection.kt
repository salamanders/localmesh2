package info.benjaminhill.localmesh2

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

abstract class AbstractConnection(
    protected val appContext: Context,
    protected val scope: CoroutineScope,
) {
    protected val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    protected val localId: String = CachedPrefs.getId(appContext)
    private lateinit var messageCleanupJob: Job

    abstract fun start()

    protected fun restart() {
        scope.launch {
            Log.i(TAG, "Restarting connection process.")
            connectionsClient.stopAllEndpoints()
            delay(15.seconds)
            start()
        }
    }

    fun stop() {
        Log.w(TAG, "AbstractConnection.stop() called.")
        connectionsClient.stopAllEndpoints()
        if (::messageCleanupJob.isInitialized) {
            messageCleanupJob.cancel()
        }
    }

    fun broadcastDisplayMessage(displayTarget: String) {
        val message = NetworkMessage(
            displayTarget = displayTarget
        )
        broadcastInternal(message)
    }

    companion object {
        const val TAG = "P2P"
        val STRATEGY = Strategy.P2P_CLUSTER
    }

    abstract fun broadcastInternal(message: NetworkMessage)
}