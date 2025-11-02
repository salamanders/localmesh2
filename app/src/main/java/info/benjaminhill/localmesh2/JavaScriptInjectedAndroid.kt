@file:OptIn(ExperimentalAtomicApi::class)

package info.benjaminhill.localmesh2

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import info.benjaminhill.localmesh2.p2p3.NetworkHolder
import info.benjaminhill.localmesh2.p2p3.NetworkMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@Suppress("unused")
class JavaScriptInjectedAndroid(private val context: Context) {

    private val visualizations: Set<String> by lazy {
        context.assets.list("")?.filter { asset ->
            context.assets.list(asset)?.contains("index.html") == true
        }?.toSortedSet() ?: emptySet()
    }

    @Serializable
    data class Status(
        val visualizations: Set<String>,
        val id: String,
        val timestamp: Long,
        val connectedPeerCount: Int,
    )

    @JavascriptInterface
    fun getStatus(): String {
        return Json.encodeToString(
            Status(
                visualizations = visualizations,
                id = CachedPrefs.getId(context),
                timestamp = System.currentTimeMillis(),
                connectedPeerCount = NetworkHolder.connection?.getDirectConnectionCount() ?: 0,
            )
        )
    }

    @JavascriptInterface
    fun sendPeerDisplayCommand(folder: String) {
        Log.d(TAG, "sendPeerDisplayCommand: $folder")
        val message = NetworkMessage(
            breadCrumbs = emptyList(),
            displayTarget = folder
        )
        NetworkHolder.connection?.broadcast(message)
    }

    companion object {
        const val TAG = "JSAndroid"
    }
}