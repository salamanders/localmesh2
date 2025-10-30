package info.benjaminhill.localmesh2.p2p

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
import info.benjaminhill.localmesh2.CachedPrefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

abstract class AbstractConnection(
    appContext: Context,
) : P2pRole {
    protected val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    protected val localId: String = CachedPrefs.getId(appContext)

    /** Endpoints that have connected to us while we were advertising. (Our clients) */
    protected val ourClients = mutableSetOf<String>()

    /** Endpoints that we have discovered and connected to. (Our servers) */
    protected val ourDiscoveredControllers = mutableSetOf<String>()

    private val _messages = MutableSharedFlow<NetworkMessage>()
    override val messages = _messages.asSharedFlow()

    fun startAdvertising(serviceId: String) {
        val payloadCallback = createPayloadCallback()
        val connectionLifecycleCallback =
            createAdvertisingConnectionLifecycleCallback(payloadCallback, ourClients)

        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            localId,
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Advertising started for service $serviceId.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed for service $serviceId.", e)
        }
    }

    fun startDiscovery(serviceId: String) {
        val payloadCallback = createPayloadCallback()
        val connectionLifecycleCallback =
            createDiscoveryConnectionLifecycleCallback(payloadCallback, ourDiscoveredControllers)

        val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (info.serviceId == serviceId) {
                    Log.i(
                        TAG,
                        "Discovery onEndpointFound: Found endpoint $endpointId for service $serviceId"
                    )
                    connectionsClient.requestConnection(
                        localId,
                        endpointId,
                        connectionLifecycleCallback
                    )
                }
            }

            override fun onEndpointLost(endpointId: String) {
                Log.i(TAG, "Discovery: Lost endpoint $endpointId.")
            }
        }

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Discovery started for service $serviceId.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Discovery failed for service $serviceId.", e)
        }
    }

    private fun createPayloadCallback(): PayloadCallback =
        object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                if (payload.type == Payload.Type.BYTES) {
                    val message = NetworkMessage.fromByteArray(payload.asBytes()!!)
                    _messages.tryEmit(message)
                }
            }

            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate
            ) {
                // No-op
            }
        }

    private fun createAdvertisingConnectionLifecycleCallback(
        payloadCallback: PayloadCallback,
        endpointSet: MutableSet<String>
    ) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.i(TAG, "Advertising: onConnectionInitiated from $endpointId. Accepting.")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.i(TAG, "Advertising: onConnectionResult success for $endpointId.")
                endpointSet.add(endpointId)
            } else {
                Log.w(
                    TAG,
                    "Advertising: onConnectionResult failure for $endpointId. Code: ${result.status.statusCode}"
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "Advertising: onDisconnected from $endpointId.")
            endpointSet.remove(endpointId)
        }
    }

    private fun createDiscoveryConnectionLifecycleCallback(
        payloadCallback: PayloadCallback,
        endpointSet: MutableSet<String>
    ) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.i(TAG, "Discovery: onConnectionInitiated from $endpointId. Accepting.")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.i(TAG, "Discovery: onConnectionResult success for $endpointId.")
                endpointSet.add(endpointId)
            } else {
                Log.w(
                    TAG,
                    "Discovery: onConnectionResult failure for $endpointId. Code: ${result.status.statusCode}"
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "Discovery: onDisconnected from $endpointId.")
            endpointSet.remove(endpointId)
        }
    }

    protected fun broadcastToAdvertiserEndpoints(message: NetworkMessage) {
        if (ourClients.isNotEmpty()) {
            Log.i(TAG, "Broadcasting to ${ourClients.size} downstream endpoints")
            val outboundPayload = Payload.fromBytes(message.toByteArray())
            connectionsClient.sendPayload(ourClients.toList(), outboundPayload)
        } else {
            Log.w(TAG, "No downstream endpoints to broadcast to.")
        }
    }

    override fun stop() {
        Log.w(TAG, "AbstractConnection.stop() called.")
        connectionsClient.stopAllEndpoints()
    }

    abstract override fun broadcast(message: NetworkMessage)

    abstract override fun start()

    companion object {
        const val TAG = "P2P"
        val STRATEGY = Strategy.P2P_CLUSTER
    }
}