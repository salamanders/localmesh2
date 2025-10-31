package info.benjaminhill.localmesh2.p2p

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback

abstract class DiscovererConnection(
    private val serviceId: String,
    appContext: Context,
) : AbstractConnection(appContext) {

    override fun start() {
        connectionsClient.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    if (info.serviceId == serviceId) {
                        Log.i(
                            TAG,
                            "DiscovererConnection onEndpointFound: Found endpoint $endpointId for service $serviceId"
                        )
                        connectionsClient.requestConnection(
                            localId,
                            endpointId,
                            object : ConnectionLifecycleCallback() {
                                override fun onConnectionInitiated(
                                    endpointId: String,
                                    connectionInfo: ConnectionInfo
                                ) {
                                    Log.i(
                                        TAG,
                                        "DiscovererConnection: onConnectionInitiated from $endpointId. Accepting."
                                    )
                                    connectionsClient.acceptConnection(endpointId, payloadToFlow())
                                }

                                override fun onConnectionResult(
                                    endpointId: String,
                                    result: ConnectionResolution
                                ) {
                                    if (result.status.isSuccess) {
                                        Log.i(
                                            TAG,
                                            "DiscovererConnection: onConnectionResult success for $endpointId."
                                        )
                                        connectedEndpoints.add(endpointId)
                                    } else {
                                        Log.w(
                                            TAG,
                                            "DiscovererConnection: onConnectionResult failure for $endpointId. Code: ${result.status.statusCode}"
                                        )
                                    }
                                }

                                override fun onDisconnected(endpointId: String) {
                                    Log.i(
                                        TAG,
                                        "DiscovererConnection: onDisconnected from $endpointId."
                                    )
                                    connectedEndpoints.remove(endpointId)
                                }
                            }
                        )
                    }
                }

                override fun onEndpointLost(endpointId: String) {
                    Log.i(TAG, "DiscovererConnection: Lost endpoint $endpointId.")
                }
            },
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        ).addOnSuccessListener {
            Log.i(TAG, "DiscovererConnection started for service $serviceId.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "DiscovererConnection failed for service $serviceId.", e)
        }
    }

}