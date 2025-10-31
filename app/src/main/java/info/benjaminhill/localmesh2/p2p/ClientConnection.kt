package info.benjaminhill.localmesh2.p2p

import android.content.Context
import android.util.Log
import info.benjaminhill.localmesh2.WebAppActivity

class ClientConnection(
    appContext: Context,
) : DiscovererConnection(LieutenantConnection.L2C_CHANNEL, appContext) {
    override fun handleReceivedMessage(message: NetworkMessage) {
        Log.i(TAG, "Received message: ${message.displayTarget}")
        WebAppActivity.navigateTo(message.displayTarget)
    }
}