@file:OptIn(ExperimentalAtomicApi::class)

package info.benjaminhill.localmesh2

import android.content.Context
import android.webkit.JavascriptInterface
import info.benjaminhill.localmesh2.p2p.MeshConnection
import info.benjaminhill.localmesh2.p2p.NetworkMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.ExperimentalTime

/**
 * Provides a bridge for the WebView's JavaScript to call native Kotlin functions.
 * This class is injected into the WebView and its methods are exposed to the JavaScript code.
 *
 * It is responsible for:
 * - Providing status information about the device and the mesh network to the web UI.
 * - Receiving commands from the web UI and broadcasting them to the mesh network.
 */
@Suppress("unused")
@OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
class JavaScriptInjectedAndroid(private val context: Context) {

    private val visualizations: Set<String> by lazy {
        context.assets.list("")?.filter { asset ->
            context.assets.list(asset)?.contains("index.html") == true
        }?.toSortedSet() ?: emptySet()
    }

    @OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
    @Serializable
    data class Status(
        val visualizations: Set<String>,
        val id: String,
        val discoveredPeerCount: Int,
        val pendingPeerCount: Int,
        val connectedPeerCount: Int,
    )

    @JavascriptInterface
    fun getStatus(): String {
        return Json.encodeToString(
            Status(
                visualizations = visualizations,
                id = MeshConnection.localHumanReadableName,
                discoveredPeerCount = MeshConnection.getDiscoveredEndpoints().size,
                pendingPeerCount = MeshConnection.getPendingConnections().size,
                connectedPeerCount = MeshConnection.getEstablishedConnections().size,
            )
        )
    }

    @JavascriptInterface
    fun sendPeerDisplayCommand(folder: String) {
        Timber.d("sendPeerDisplayCommand: $folder")
        val message = NetworkMessage(
            breadCrumbs = emptyList(),
            displayScreen = folder
        )
        MeshConnection.broadcast(message)
    }
}
