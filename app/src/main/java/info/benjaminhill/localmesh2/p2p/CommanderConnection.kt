package info.benjaminhill.localmesh2.p2p

import android.content.Context
import android.util.Log

class CommanderConnection(
    appContext: Context,
) : AdvertiserConnection(COMMAND_CHANNEL, appContext) {
    override fun handleReceivedMessage(message: NetworkMessage) {
        Log.e(TAG, "Commanders do not get messages, they give them.")
    }

    companion object {
        const val COMMAND_CHANNEL = "info.benjaminhill.localmesh2.COMMAND"
    }
}