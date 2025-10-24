package info.benjaminhill.localmesh2

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException


@Suppress("unused")
class JavaScriptInjectedAndroid(private val context: Context) {

    /** Top level ls */
    private fun ls(
        context: Context,
        path: String = "/",
        listFolders: Boolean = false,
    ): List<String> = context.assets.list(path)?.filter { asset ->
        val assetPath = path + asset
        val isDir = try {
            context.assets.list(assetPath)?.isNotEmpty()
        } catch (_: IOException) {
            false
        }
        listFolders == isDir
    } ?: emptyList()

    @JavascriptInterface
    fun getStatus(): String = JSONObject().apply {
        put("visualizations", JSONArray(ls(context, listFolders = true)))
        put("id", CachedPrefs.getId(context))
        val peersList = listOf(
            mapOf("id" to "abc", "hops" to 1, "age" to 1234567890),
            mapOf("id" to "def", "hops" to 1, "age" to 1234567890),
            mapOf("id" to "ghi", "hops" to 2, "age" to 1234567890),
        )
        put("peers", JSONArray(peersList.map { JSONObject(it as Map<*, *>) }))
        put("timestamp", System.currentTimeMillis())
    }.toString()

    @JavascriptInterface
    fun sendPeerDisplayCommand(folder: String) {
        // TODO: Send command to peer
    }
}
