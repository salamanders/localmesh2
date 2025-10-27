package info.benjaminhill.localmesh2

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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startMesh()
        } else {
            Log.e(TAG, "User denied permissions.")
            Toast.makeText(
                this, "All permissions are required for LocalMesh to function.", Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dangerousPermissions = PermissionUtils.getDangerousPermissions(this)
        val allPermissionsGranted = dangerousPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            Log.i(TAG, "Permissions already granted, starting mesh.")
            startMesh()
        } else {
            setContent {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(onClick = {
                        requestPermissionLauncher.launch(dangerousPermissions)
                    }) {
                        Text("Authorize Mesh")
                    }
                }
            }
        }
    }

    private fun startMesh() {
        Log.i(TAG, "Permissions granted, starting service...")
        NearbyConnectionsManager.initialize(this, lifecycleScope)
        NearbyConnectionsManager.startDiscovery()
        NearbyConnectionsManager.startAdvertising()

        Intent(this, WebAppActivity::class.java).apply {
            putExtra(WebAppActivity.EXTRA_PATH, "index.html")
        }.also {
            startActivity(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "MainActivity.onDestroy() called.")
        NearbyConnectionsManager.stop()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}