package info.benjaminhill.localmesh2

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Suppress("unused")
class JavaScriptInjectedAndroid(private val context: Context) {

    private val visualizations: Set<String> by lazy {
        context.assets.list("")
            ?.filter { asset ->
                context.assets.list(asset)?.contains("index.html") == true
            }
            ?.toSortedSet()
            ?: emptySet()
    }

    @Serializable
    data class Status(
        val visualizations: Set<String>,
        val id: String,
        // All known peers and distance (if known)
        val peers: Map<String, Int?>,
        val timestamp: Long,
    )

    @JavascriptInterface
    fun getStatus(): String = Json.encodeToString(
        Status(
            visualizations = visualizations,
            id = CachedPrefs.getId(context),
            peers = EndpointRegistry.getDirectlyConnectedEndpoints()
                .associate { it.id to it.distance },
            timestamp = System.currentTimeMillis()
        )
    )

    @JavascriptInterface
    fun sendPeerDisplayCommand(folder: String) {
        Log.d(TAG, "sendPeerDisplayCommand: $folder")
        NearbyConnectionsManager.broadcastDisplayMessage(folder)
    }

    companion object {
        const val TAG = "JSAndroid"
    }
}
