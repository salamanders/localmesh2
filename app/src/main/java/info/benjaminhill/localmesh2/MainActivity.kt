@file:OptIn(ExperimentalAtomicApi::class)

package info.benjaminhill.localmesh2

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import info.benjaminhill.localmesh2.p2p.ClientConnection
import info.benjaminhill.localmesh2.p2p.CommanderConnection
import info.benjaminhill.localmesh2.p2p.LieutenantConnection
import info.benjaminhill.localmesh2.p2p.NetworkHolder
import info.benjaminhill.localmesh2.p2p3.HealingMeshConnection
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            selectRole()
        } else {
            Timber.e("User denied permissions.")
            Toast.makeText(
                this, "All permissions are required for LocalMesh to function.", Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val dangerousPermissions = PermissionUtils.getDangerousPermissions(this)
        val allPermissionsGranted = dangerousPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            Timber.i("Permissions already granted, starting mesh.")
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
        ).requestedPermissions?.forEach { permission ->
            Timber.d("$permission: ${if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}")
        }
    }

    private fun selectRole() {
        logPermissions()
        setContent {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(onClick = {
                    NetworkHolder.connection = CommanderConnection(applicationContext)
                    collectMessages()
                    display("commander.html")
                }) {
                    Text("Commander")
                }
                Button(onClick = {
                    NetworkHolder.connection =
                        LieutenantConnection(applicationContext)
                    collectMessages()
                    display("lieutenant.html")
                }) {
                    Text("Lieutenant")
                }
                Button(onClick = {
                    NetworkHolder.connection = ClientConnection(applicationContext)
                    collectMessages()
                    display("client.html")
                }) {
                    Text("Client")
                }

                Button(onClick = {
                    Timber.i("Starting Healing Mesh")
                    val hmc = HealingMeshConnection(applicationContext)
                    hmc.start()
                }) {
                    Text("Healing Mesh")
                }
                Button(onClick = {
                    display("display.html")
                }) {
                    Text("Display Locally")
                }
            }
        }
    }

    private fun collectMessages() {
        lifecycleScope.launch {
            val connection = NetworkHolder.connection
            if (connection == null) {
                Timber.e("Null NetworkHolder.connection")
                return@launch
            }
            Timber.w("Starting connection")
            connection.start()
        }

    }

    private fun display(webAppPath: String) {
        Intent(this, WebAppActivity::class.java).apply {
            putExtra(WebAppActivity.EXTRA_PATH, webAppPath)
        }.also {
            startActivity(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.w("MainActivity.onDestroy() called.")
        NetworkHolder.connection?.stop()
    }
}
