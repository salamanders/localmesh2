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
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

class NearbyConnectionsManager(
    context: Context,
) {
    private val serviceId = "info.benjaminhill.localmesh2"
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val localName = CachedPrefs.getId(context)
    private val connectedEndpoints = mutableSetOf<String>()

    private val maxPeers = 2

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

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Log.i(TAG, "onPayloadReceived: from $endpointId, type: ${payload.type}")
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes()!!
                    Log.i(
                        TAG,
                        "Emitting ${bytes.size} bytes from $endpointId to incomingPayloads flow."
                    )
                    // TODO: Got payload, do stuff.
//                        scope.launch {
//                            incomingPayloads.emit(endpointId to bytes)
//                        }
                }
                // File or Stream not supported
                else -> {
                    Log.w(TAG, "Ignoring non-BYTES payload from $endpointId, type: ${payload.type}")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS ->
                    Log.i(
                        TAG,
                        "SUCCESS: Payload ${update.payloadId} transfer to $endpointId complete."
                    )

                PayloadTransferUpdate.Status.FAILURE ->
                    Log.e(
                        TAG,
                        "FAILURE: Payload ${update.payloadId} transfer to $endpointId failed.",
                        Exception("PayloadTransferUpdate Failure")
                    )

                PayloadTransferUpdate.Status.CANCELED ->
                    Log.w(
                        TAG,
                        "CANCELED: Payload ${update.payloadId} transfer to $endpointId was canceled."
                    )

                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // Ignoring for now to keep logs clean. This is where you'd update a progress bar.
                }
            }

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
            if (connectedEndpoints.size >= maxPeers) {
                Log.i(
                    TAG,
                    "Won't attempt to connect to $endpointId, already have ${connectedEndpoints.size} connections"
                )
                return
            }
            if (endpointId > localName) {
                Log.i(TAG, "Won't attempt to connect to $endpointId, it is a higher id than me")
                return
            }
            Log.i(
                TAG,
                "Attempting connection to $endpointId (don't know yet if it will allow me to connect!)"
            )
            connectionsClient.requestConnection(localName, endpointId, connectionLifecycleCallback)
                .addOnSuccessListener {
                    Log.d(TAG, "Successfully requested connection to $endpointId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to request connection to $endpointId", e)
                }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "Endpoint lost: $endpointId")
            // TODO: Handle lost endpoints.
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
                // Don't need to check here for higher/lower peer IDs, already covered in endpointDiscoveryCallback
                if (connectedEndpoints.size < maxPeers) {
                    Log.i(TAG, "Accepting connection from $endpointId")
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                } else {
                    Log.i(
                        TAG,
                        "Rejecting connection from $endpointId, already have ${connectedEndpoints.size} connections"
                    )
                    connectionsClient.rejectConnection(endpointId)
                }
            }

            override fun onConnectionResult(
                endpointId: String, resolution: ConnectionResolution
            ) {
                Log.i(
                    TAG,
                    "ConnectionLifecycleCallback onConnectionResult $endpointId: $resolution"
                )
                if (resolution.status.isSuccess) {
                    Log.i(TAG, "Successfully connected to $endpointId")
                    connectedEndpoints.add(endpointId)
                } else {
                    Log.w(
                        TAG,
                        "Failed to connect to $endpointId: ${resolution.status} (did it already have enough peers?)"
                    )
                    // TODO: If you keep getting rejected, consider ban-listing.  Connections.disconnectFromEndpoint(GoogleApiClient, String).
                }
            }

            override fun onDisconnected(endpointId: String) {
                Log.i(TAG, "ConnectionLifecycleCallback onDisconnected $endpointId")
                connectedEndpoints.remove(endpointId)
            }

        }

    companion object {
        private const val TAG = "NCM"
    }
}