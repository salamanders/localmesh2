package info.benjaminhill.localmesh2

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Handles index.html
        handleIntent(intent)

        setContent {
            FullScreenWebView(
                url = "file:///android_asset/${pathState.value}",
            )
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                Log.i(TAG, "Intent extra: $key = ${bundle.getString(key)}")
            }
        }
        pathState.value = intent?.getStringExtra(EXTRA_PATH) ?: "index.html"
    }

    companion object {
        private const val TAG = "WebAppActivity"

        /** Intent extra key for the content path to display. */
        const val EXTRA_PATH = "info.benjaminhill.localmesh2.EXTRA_PATH"
    }
}