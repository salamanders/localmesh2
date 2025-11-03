package info.benjaminhill.localmesh2

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import timber.log.Timber

/**
 * An activity dedicated to hosting the application's `WebView`.
 * This activity is responsible for loading the web-based UI and injecting the
 * `JavaScriptInjectedAndroid` bridge to enable communication between the Kotlin backend
 * and the JavaScript frontend.
 */
class WebAppActivity : ComponentActivity() {
    /** Holds the current content path to be displayed in the WebView. */
    private val pathState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("onCreate with intent: $intent")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // includes enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.let {
            // Hide the navigation bars
            it.hide(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.statusBars())
            // Allow swiping from the edge to show the system bars again
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.attributes = window.attributes.apply {
            screenBrightness = 1.0f
        }

        // Handles index.html
        handleIntent(intent)

        setContent {
            FullScreenWebView(
                url = "file:///android_asset/${pathState.value}",
            )
        }
    }

    override fun onResume() {
        super.onResume()
        instance = this
    }

    override fun onPause() {
        super.onPause()
        if (instance == this) {
            instance = null
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.extras?.let { bundle ->
            bundle.keySet().forEach { key ->
                Timber.i("Intent extra: $key = ${bundle.getString(key)}")
            }
        }
        pathState.value = intent?.getStringExtra(EXTRA_PATH) ?: "index.html"
    }

    /** Should only be called from the static navigateTo */
    private fun navigateTo(path: String) {
        path.takeIf { it.isNotBlank() }?.let { nonBlankPath ->
            Timber.i("Navigating to $nonBlankPath")
            pathState.value = "$nonBlankPath/index.html"
        } ?: Timber.w("navigateTo path was blank.")
    }


    companion object {
        /** Intent extra key for the content path to display. */
        const val EXTRA_PATH = "info.benjaminhill.localmesh2.EXTRA_PATH"
        private var instance: WebAppActivity? = null
        fun navigateTo(path: String) {
            instance?.let { activity ->
                activity.runOnUiThread {
                    activity.navigateTo(path)
                }
            } ?: Timber.w("navigateTo called but no WebAppActivity instance is available.")
        }
    }
}