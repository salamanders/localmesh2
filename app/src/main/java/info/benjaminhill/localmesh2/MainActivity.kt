@file:OptIn(ExperimentalAtomicApi::class)

package info.benjaminhill.localmesh2

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            runBlocking { startMesh() }
        } else {
            Log.e(TAG, "User denied permissions.")
            Toast.makeText(
                this, "All permissions are required for LocalMesh to function.", Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val dangerousPermissions = PermissionUtils.getDangerousPermissions(this)
        val allPermissionsGranted = dangerousPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            Log.i(TAG, "Permissions already granted, starting mesh.")
            selectRole()
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

    private fun logPermissions() {
        packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_PERMISSIONS
        ).requestedPermissions?.forEach {
            Log.d(
                "PermCheck",
                "$it: ${if (checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}"
            )
        }
    }

    private fun selectRole() {
        setContent {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(onClick = {
                    NearbyConnectionsManager.role.store(Role.COMMANDER)
                    logPermissions()
                    startMesh()
                }) {
                    Text("Become Remote Control")
                }
            }
        }
        lifecycleScope.launch {
            delay(10.seconds)
            if (NearbyConnectionsManager.role.load() != Role.COMMANDER) {
                NearbyConnectionsManager.role.store(Role.LIEUTENANT)
                logPermissions()
                startMesh()
            }
        }
    }

    private fun startMesh() {
        Log.i(TAG, "Permissions granted, starting service...")
        NearbyConnectionsManager.initialize(this, lifecycleScope)
        runBlocking {
            NearbyConnectionsManager.start()
        }

        val webAppPath = when (NearbyConnectionsManager.role.load()) {
            Role.COMMANDER -> "index.html"
            else -> "client.html"
        }

        Intent(this, WebAppActivity::class.java).apply {
            putExtra(WebAppActivity.EXTRA_PATH, webAppPath)
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