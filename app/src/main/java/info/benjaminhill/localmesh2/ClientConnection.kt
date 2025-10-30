package info.benjaminhill.localmesh2

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class ClientConnection(
    appContext: Context,
    scope: CoroutineScope,
) : AbstractConnection(appContext = appContext, scope = scope) {
    var connectedLieutenantEndpointId: String? = null
    private var connectionTimeout: Job? = null


    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedMessage = NetworkMessage.fromByteArray(payload.asBytes()!!)

                Log.i(
                    TAG,
                    "Client received message from Lieutenant: ${receivedMessage.displayTarget}"
                )
                WebAppActivity.navigateTo(receivedMessage.displayTarget)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No special handling needed for this topology
        }
    }

    private val clientConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            // This is called on both Lieutenant and Client.
            Log.i(TAG, "Accepting connection from $endpointId")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {

                Log.i(TAG, "Client connected to Lieutenant: $endpointId")
                connectionTimeout?.cancel()
                connectionsClient.stopDiscovery()
                connectedLieutenantEndpointId = endpointId

            } else {
                Log.w(TAG, "Client failed to connect to Lieutenant. Restarting discovery.")
                connectedLieutenantEndpointId = null
                restart()
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.w(TAG, "Client disconnected from Lieutenant. Restarting discovery.")
            connectedLieutenantEndpointId = null
            restart()
        }
    }

    private val clientToLieutenantEndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (connectedLieutenantEndpointId == null) {
                connectionsClient.requestConnection(
                    localId, endpointId, clientConnectionLifecycleCallback
                )
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Don't care
        }
    }

    override fun start() {
        Log.i(TAG, "Starting Client...")
        connectedLieutenantEndpointId = null
        connectionsClient.stopDiscovery()

        connectionTimeout?.cancel()
        connectionTimeout = scope.launch {
            Log.i(TAG, "Client connection timed search started.")
            delay(60.seconds)
            Log.w(TAG, "Client connection search timed out. Restarting.")
            restart()
        }

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            LieutenantConnection.L2C_CHANNEL,
            clientToLieutenantEndpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Client discovery for Lieutenants started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Client discovery for Lieutenants failed.", e)
            restart()
        }
    }

    override fun broadcastInternal(message: NetworkMessage) {
        Log.e(TAG, "Why is a client trying to broadcast?")
    }
}