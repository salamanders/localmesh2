package info.benjaminhill.localmesh2

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject


@Suppress("unused")
class JavaScriptInjectedAndroid(private val context: Context) {

//    /** Top level ls */
//    private fun ls(
//        path: String = "/",
//        listFolders: Boolean = false,
//    ): List<String> {
//
//        return context.assets.list(path.removePrefix("/"))?.filter { asset ->
//            Log.d(TAG, "ls $path found: $asset")
//            val assetPath = path + asset
//            val isDir = try {
//                context.assets.list(assetPath)?.isNotEmpty()
//            } catch (_: IOException) {
//                false
//            }
//            listFolders == isDir
//        } ?: emptyList()
//    }

    private val visualizations: Set<String> by lazy {
        (context.assets.list("")?.filter { asset ->
            context.assets.list(asset)?.contains("index.html") ?: false
        } ?: emptyList()).toSortedSet()
    }

    @JavascriptInterface
    fun getStatus(): String = JSONObject().apply {
        put("visualizations", JSONArray(visualizations))
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
        Log.w(TAG, "sendPeerDisplayCommand: $folder")
        // TODO: Send command to peer
    }

    companion object {
        const val TAG = "JavaScriptInjectedAndroid"
    }
}
