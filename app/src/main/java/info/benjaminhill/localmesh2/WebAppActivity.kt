package info.benjaminhill.localmesh2

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class WebAppActivity : ComponentActivity() {
    /** Holds the current content path to be displayed in the WebView. */
    private val pathState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate with intent: $intent")

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
            screenBrightness = 0.8f
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
                Log.i(TAG, "Intent extra: $key = ${bundle.getString(key)}")
            }
        }
        pathState.value = intent?.getStringExtra(EXTRA_PATH) ?: "index.html"
    }

    /** Should only be called from the static navigateTo */
    private fun navigateTo(path: String) {
        path.takeIf { it.isNotBlank() }?.let { nonBlankPath ->
            Log.i(TAG, "Navigating to $nonBlankPath")
            pathState.value = "$nonBlankPath/index.html"
        } ?: Log.w(TAG, "navigateTo path was blank.")
    }


    companion object {
        private const val TAG = "WebAppActivity"

        /** Intent extra key for the content path to display. */
        const val EXTRA_PATH = "info.benjaminhill.localmesh2.EXTRA_PATH"
        private var instance: WebAppActivity? = null
        fun navigateTo(path: String) {
            instance?.let { activity ->
                activity.runOnUiThread {
                    activity.navigateTo(path)
                }
            } ?: Log.w(TAG, "navigateTo called but no WebAppActivity instance is available.")
        }
    }
}