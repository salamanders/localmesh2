package info.benjaminhill.localmesh2

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.connection.AdvertisingOptions
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

class LieutenantConnection(
    appContext: Context,
    scope: CoroutineScope,
) : AbstractConnection(appContext = appContext, scope = scope) {

    private var mainCommanderEndpointId: String? = null
    private var connectionTimeout: Job? = null
    val clientEndpointIds = mutableListOf<String>()


    private val payloadFromCommanderCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedMessage = NetworkMessage.fromByteArray(payload.asBytes()!!)
                Log.i(
                    TAG,
                    "Lieutenant received message from Commander: ${receivedMessage.displayTarget}, relaying to ${clientEndpointIds.size} clients"
                )
                broadcastInternal(receivedMessage)
                WebAppActivity.navigateTo(receivedMessage.displayTarget)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No special handling needed for this topology
        }
    }

    private val payloadFromClientCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Log.e(TAG, "Clients may not give commands.")
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No special handling needed for this topology
        }
    }


    private val clientToLieutenantConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
                Log.i(TAG, "Accepting connection from client $endpointId")
                connectionsClient.acceptConnection(endpointId, payloadFromClientCallback)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.isSuccess) {
                    Log.i(TAG, "Client connected to this Lieutenant: $endpointId")
                    clientEndpointIds.add(endpointId)
                } else {
                    Log.w(
                        TAG,
                        "Client failed to connect to this Lieutenant. This is not our problem."
                    )
                }
            }

            override fun onDisconnected(endpointId: String) {
                clientEndpointIds.remove(endpointId)
                Log.i(TAG, "Client disconnected from this Lieutenant: $endpointId")
            }
        }

    private val lieutenantToCommanderConnectionLifecycleCallback: ConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(
                endpointId: String,
                connectionInfo: ConnectionInfo
            ) {
                Log.i(TAG, "onConnectionInitiated: Lieutenant to Commander connection initiated from $endpointId. Waiting for Commander to accept.")
                // The Commander accepts the connection, not the Lieutenant.
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                connectionTimeout?.cancel()
                if (result.status.isSuccess) {
                    Log.i(TAG, "onConnectionResult: Lieutenant connected to Commander: $endpointId")
                    mainCommanderEndpointId = endpointId
                    connectionsClient.stopDiscovery() // Found our commander.

                    // Now start advertising to clients
                    val advertisingOptions =
                        AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
                    connectionsClient.startAdvertising(
                        localId,
                        L2C_CHANNEL,
                        clientToLieutenantConnectionLifecycleCallback,
                        advertisingOptions
                    ).addOnSuccessListener {
                        Log.i(TAG, "Lieutenant advertising for clients started.")
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "Lieutenant advertising for clients failed.", e)
                        restart()
                    }
                } else {
                    Log.w(
                        TAG,
                        "onConnectionResult: Commander rejected connection. Restarting discovery. ${result.status.statusCode}"
                    )
                    mainCommanderEndpointId = null
                    restart()
                }
            }

            override fun onDisconnected(endpointId: String) {
                Log.w(TAG, "onDisconnected: Lieutenant disconnected from Commander. Restarting discovery.")
                mainCommanderEndpointId = null
                restart()
            }
        }

    private val commandertEndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i(TAG, "onEndpointFound: Discovered Commander: $endpointId")
            if (mainCommanderEndpointId == null) {
                Log.i(TAG, "onEndpointFound: Requesting connection to Commander: $endpointId")
                connectionsClient.requestConnection(
                    localId, endpointId, lieutenantToCommanderConnectionLifecycleCallback
                )
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Don't care
        }
    }


    override fun start() {
        Log.i(TAG, "Starting Lieutenant...")
        mainCommanderEndpointId = null
        connectionsClient.stopDiscovery()

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        connectionTimeout?.cancel()
        connectionTimeout = scope.launch {
            Log.i(TAG, "Lieutenant connection timed search started.")
            delay(60.seconds)
            Log.w(TAG, "Lieutenant connection search timed out. Restarting.")
            restart()
        }

        connectionsClient.startDiscovery(
            CommanderConnection.COMMAND_CHANNEL,
            commandertEndpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Lieutenant discovery for Commander started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Lieutenant discovery for Commander failed.", e)
            restart()
        }
    }

    override fun broadcastInternal(message: NetworkMessage) {
        Log.i(TAG, "Lieutenant broadcasting to ${clientEndpointIds.size} clients")
        val outboundPayload = Payload.fromBytes(message.toByteArray())
        connectionsClient.sendPayload(clientEndpointIds, outboundPayload)
    }

    companion object {
        const val L2C_CHANNEL = "LM_LIEUTENANT"
    }
}