package info.benjaminhill.localmesh2.p2p

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import info.benjaminhill.localmesh2.CachedPrefs

abstract class AbstractConnection(
    appContext: Context,
) : P2pRole {
    protected val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    protected val localId: String = CachedPrefs.getId(appContext)

    /** Endpoints that have connected to us while we were advertising. (Our clients) */
    protected val connectedEndpoints = mutableSetOf<String>()
    // Not part of the interface because some may want to handle it internally
    abstract fun handleReceivedMessage(message: NetworkMessage)

    internal fun payloadToFlow(): PayloadCallback =
        object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                Log.i(TAG, "onPayloadReceived message from $endpointId")
                if (payload.type == Payload.Type.BYTES) {
                    val message = NetworkMessage.Companion.fromByteArray(payload.asBytes()!!)
                    Log.i(TAG, "Received message: $message")
                    handleReceivedMessage(message)
                }
            }

            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate
            ) {
                // No-op
            }
        }


    override fun stop() {
        Log.w(TAG, "AbstractConnection.stop() called.")
        connectionsClient.stopAllEndpoints()
    }

    override fun broadcast(message: NetworkMessage) {
        if (connectedEndpoints.isNotEmpty()) {
            Log.i(TAG, "Broadcasting to ${connectedEndpoints.size} connected peers")
            val outboundPayload = Payload.fromBytes(message.toByteArray())
            connectionsClient.sendPayload(connectedEndpoints.toList(), outboundPayload)
        } else {
            Log.w(TAG, "No connected peers to broadcast to.")
        }
    }

    override fun getConnectedPeerCount(): Int = connectedEndpoints.size

    companion object {
        const val TAG = "P2P"
        val STRATEGY = Strategy.P2P_CLUSTER
    }
}