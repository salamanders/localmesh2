package info.benjaminhill.localmesh2.p2p

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution

abstract class AdvertiserConnection(
    private val serviceId: String,
    appContext: Context,
) : AbstractConnection(appContext) {

    override fun start() {
        connectionsClient.startAdvertising(
            localId,
            serviceId,
            object : ConnectionLifecycleCallback() {
                override fun onConnectionInitiated(
                    endpointId: String,
                    connectionInfo: ConnectionInfo
                ) {
                    Log.i(TAG, "Advertising: onConnectionInitiated from $endpointId. Accepting.")
                    connectionsClient.acceptConnection(endpointId, payloadToFlow())
                }

                override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                    Log.i(
                        TAG,
                        "onConnectionResult for $endpointId, success: ${result.status.isSuccess}"
                    )
                    if (result.status.isSuccess) {
                        Log.i(TAG, "Advertising: onConnectionResult success for $endpointId.")
                        Log.i(TAG, "endpointSet before add: $connectedEndpoints")
                        connectedEndpoints.add(endpointId)
                        Log.i(TAG, "endpointSet after add: $connectedEndpoints")
                    } else {
                        Log.w(
                            TAG,
                            "Advertising: onConnectionResult failure for $endpointId. Code: ${result.status.statusCode}"
                        )
                    }
                }

                override fun onDisconnected(endpointId: String) {
                    Log.i(TAG, "Advertising: onDisconnected from $endpointId.")
                    connectedEndpoints.remove(endpointId)
                }
            },
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnSuccessListener {
            Log.i(TAG, "Advertising started for service $serviceId.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed for service $serviceId.", e)
        }
    }

}