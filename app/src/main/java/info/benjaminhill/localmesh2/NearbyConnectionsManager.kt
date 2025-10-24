package info.benjaminhill.localmesh2

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Strategy

class NearbyConnectionsManager(
    context: Context,
) {
    val serviceId = "info.benjaminhill.localmesh2"
    val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    val localName = CachedPrefs.getId(context)

    fun startAdvertising() {
        // Strategy.P2P_CLUSTER is used as it supports M-to-N connections,
        // which is suitable for a dynamic snake topology where multiple
        // endpoints can be discovering or advertising simultaneously.
        val advertisingOptions =
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        connectionsClient.startAdvertising(
            localName, serviceId, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "$localName started Advertising.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "$localName failed to start advertising", e)
        }
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "$localName started discovery.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "$localName failed to start discovery", e)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo
        ) {
            Log.i(
                TAG,
                "EndpointDiscoveryCallback onEndpointFound $endpointId: $discoveredEndpointInfo"
            )
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "Endpoint lost: $endpointId")
        }

    }

    private val connectionLifecycleCallback: ConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(
                endpointId: String, connectionInfo: ConnectionInfo
            ) {
                Log.i(
                    TAG,
                    "ConnectionLifecycleCallback onConnectionInitiated $endpointId: $connectionInfo"
                )
            }

            override fun onConnectionResult(
                endpointId: String, resolution: ConnectionResolution
            ) {
                Log.i(
                    TAG,
                    "ConnectionLifecycleCallback onConnectionResult $endpointId: $resolution"
                )
            }

            override fun onDisconnected(endpointId: String) {
                Log.i(TAG, "ConnectionLifecycleCallback onDisconnected $endpointId")
            }

        }

    companion object {
        private const val TAG = "NCM"
    }
}