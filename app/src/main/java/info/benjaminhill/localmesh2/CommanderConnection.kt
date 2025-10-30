package info.benjaminhill.localmesh2

import android.content.Context
import android.util.Log

import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution

import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate

import kotlinx.coroutines.CoroutineScope


class CommanderConnection(
    appContext: Context,
    scope: CoroutineScope,
) : AbstractConnection(appContext = appContext, scope = scope) {
    private val lieutenantEndpointIds = mutableListOf<String>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Log.e(TAG, "Why did commander just get told something?")
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No special handling needed for this topology
        }
    }
    private val lieutenandToCommanderConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
                Log.i(TAG, "onConnectionInitiated: Accepting connection from $endpointId")
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.isSuccess) {
                    Log.i(TAG, "onConnectionResult: Successfully connected to $endpointId")
                    lieutenantEndpointIds.add(endpointId)
                } else {
                    Log.w(TAG, "onConnectionResult: Failed to connect to $endpointId: ${result.status.statusCode}")
                }
            }

            override fun onDisconnected(endpointId: String) {
                Log.i(TAG, "onDisconnected: $endpointId")
                lieutenantEndpointIds.remove(endpointId)
            }
        }

    override fun start() {
        Log.i(TAG, "Starting Commander...")
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            localId,
            COMMAND_CHANNEL,
            lieutenandToCommanderConnectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Commander advertising started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Commander advertising failed.", e)
        }
    }

    override fun broadcastInternal(message: NetworkMessage) {
        Log.i(TAG, "Commander broadcasting to ${lieutenantEndpointIds.size} lieutenants")
        val outboundPayload = Payload.fromBytes(message.toByteArray())
        connectionsClient.sendPayload(lieutenantEndpointIds, outboundPayload)
    }

    companion object {
        const val COMMAND_CHANNEL = "LM_COMMANDER"
    }
}