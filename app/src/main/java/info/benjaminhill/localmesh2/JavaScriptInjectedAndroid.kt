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
        val role: String,
        val peers: Set<String>,
        val timestamp: Long,
    )

    @JavascriptInterface
    fun getStatus(): String = Json.encodeToString(
        Status(
            visualizations = visualizations,
            id = CachedPrefs.getId(context),
            role = RoleManager.role.value.name,
            peers = EndpointRegistry.getDirectlyConnectedEndpoints().map { it.id }.toSet(),
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
