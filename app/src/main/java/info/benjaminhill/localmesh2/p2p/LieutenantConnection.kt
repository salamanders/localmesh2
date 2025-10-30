package info.benjaminhill.localmesh2.p2p

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

class LieutenantConnection(
    appContext: Context,
    private val scope: CoroutineScope,
) : P2pRole {

    private val commanderClient = DiscovererConnection(
        serviceId = CommanderConnection.COMMAND_CHANNEL,
        appContext = appContext,
    )

    private val clientServer = AdvertiserConnection(
        serviceId = L2C_CHANNEL,
        appContext = appContext,
    )

    override val messages: SharedFlow<NetworkMessage> =
        merge(commanderClient.messages, clientServer.messages).shareIn(
            scope,
            SharingStarted.Eagerly
        )

    override fun start() {
        commanderClient.start()
        clientServer.start()

        // Relay messages from commander to clients
        scope.launch {
            commanderClient.messages.collect {
                clientServer.broadcast(it)
            }
        }
    }

    override fun stop() {
        commanderClient.stop()
        clientServer.stop()
    }

    override fun broadcast(message: NetworkMessage) {
        // Lieutenants only broadcast to their clients (advertiser endpoints)
        clientServer.broadcast(message)
    }

    companion object {
        const val L2C_CHANNEL = "LM_LIEUTENANT"
    }
}