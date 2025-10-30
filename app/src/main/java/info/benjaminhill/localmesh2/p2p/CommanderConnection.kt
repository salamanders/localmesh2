package info.benjaminhill.localmesh2.p2p

import android.content.Context

class CommanderConnection(
    appContext: Context,
) : AdvertiserConnection(COMMAND_CHANNEL, appContext) {

    companion object {
        const val COMMAND_CHANNEL = "LM_COMMANDER"
    }
}