package info.benjaminhill.localmesh2

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import timber.log.Timber

/**
 * A self-contained, full-screen Composable for displaying a web page.
 * This is a Jetpack Compose UI component, not a complete Android Activity.
 * It wraps a WebView to render web content passed via the [url] parameter.
 *
 * Auto-grants all permissions, logs all errors.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FullScreenWebView(url: String) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.displayZoomControls = false

                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mediaPlaybackRequiresUserGesture = false
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                // TODO(someday): Use the modern WebViewAssetLoader method.
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = true

                // Enables chrome://inspect
                WebView.setWebContentsDebuggingEnabled(true)

                // also to handle navigation within the WebView without opening a browser
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Timber.i("WebViewClient onPageFinished: $url")
                        // Check if the magic function exists, and if so, call it.
                        view?.evaluateJavascript("typeof autoStartInWebView === 'function'") { result ->
                            if ("true" == result) {
                                Timber.i("Found autoStartInWebView in $url, executing.")
                                view.evaluateJavascript("autoStartInWebView();", null)
                            } else {
                                Timber.i("No autoStartInWebView in $url, skipping.")
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        val requestUrl = request?.url?.toString() ?: ""
                        val description = error?.description ?: ""
                        Timber.e("WebView onReceivedError: '$description' on $requestUrl")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        Timber.i("Granting permission for ${request.resources.joinToString()}")
                        request.grant(request.resources)
                    }
                }

                addJavascriptInterface(JavaScriptInjectedAndroid(context), "Android")
            }
        },
        update = {
            Timber.i("Updating URL to: $url")
            it.loadUrl(url)
        },
        modifier = Modifier.fillMaxSize()
    )
}
