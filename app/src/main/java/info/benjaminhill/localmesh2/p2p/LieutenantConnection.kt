package info.benjaminhill.localmesh2.p2p

import android.content.Context
import android.util.Log
import info.benjaminhill.localmesh2.WebAppActivity

class LieutenantConnection(
    appContext: Context,
) : P2pRole {

    private val connectionToCommander = object : DiscovererConnection(
        serviceId = CommanderConnection.Companion.COMMAND_CHANNEL,
        appContext = appContext,
    ) {
        override fun handleReceivedMessage(message: NetworkMessage) {
            Log.i(TAG, "Received message: ${message.displayTarget}")
            WebAppActivity.navigateTo(message.displayTarget)
            // Rebroadcast to all clients
            connectionToClients.broadcast(message)
        }
    }


    private val connectionToClients = object : AdvertiserConnection(
        serviceId = L2C_CHANNEL,
        appContext = appContext,
    ) {
        override fun handleReceivedMessage(message: NetworkMessage) {
            Log.e(Companion.TAG, "Lieutenants do not appreciate messsages from Clients.")
        }
    }

    override fun start() {
        Log.w(TAG, "Starting LieutenantConnection")
        connectionToCommander.start()
        connectionToClients.start()
    }

    override fun stop() {
        connectionToCommander.stop()
        connectionToClients.stop()
    }

    override fun broadcast(message: NetworkMessage) {
        Log.e(TAG, "Why is a LieutenantConnection broadcasting, it should only relay!")
        connectionToClients.broadcast(message)
    }

    override fun getConnectedPeerCount(): Int =
        connectionToCommander.getConnectedPeerCount() + connectionToClients.getConnectedPeerCount()

    companion object {
        const val L2C_CHANNEL = "info.benjaminhill.localmesh2.LIEUTENANT"
        const val TAG = "LieutenantConnection"
    }
}