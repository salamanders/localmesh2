package info.benjaminhill.localmesh2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    lateinit var nearbyConnectionsManager: NearbyConnectionsManager

    private val REQUEST_CODE_REQUIRED_PERMISSIONS = 1

    // Modern Android API for requesting permissions and handling the user's response.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Not granted ACCESS_FINE_LOCATION.")
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_CODE_REQUIRED_PERMISSIONS
            )
        }

        if (permissions.all { it.value }) {
            Log.i(TAG, "Permissions granted, starting service...")
            nearbyConnectionsManager = NearbyConnectionsManager(this, lifecycleScope)
            nearbyConnectionsManager.startDiscovery()
            nearbyConnectionsManager.advertiseWithAccuratePeerCount()

            // Launch the DisplayActivity to show the main UI. Due to its 'singleTop' launchMode,
            // future P2P display commands will reuse this Activity instance by sending it a new Intent
            // via the onNewIntent() callback.
            Intent(this, WebAppActivity::class.java).apply {
                putExtra(WebAppActivity.EXTRA_PATH, "index.html")
            }.also {
                startActivity(it)
            }
            // Close the MainActivity so the user can't navigate back to the start button.
            finish()

        } else {
            Log.e(TAG, "User denied permissions.")
            Toast.makeText(
                this, "All permissions are required for LocalMesh to function.", Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val context = LocalContext.current
                Button(onClick = {
                    requestPermissionLauncher.launch(
                        PermissionUtils.getDangerousPermissions(
                            context
                        )
                    )
                }) {
                    Text("Authorize Mesh")
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}