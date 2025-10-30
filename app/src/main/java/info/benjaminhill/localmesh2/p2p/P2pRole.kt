package info.benjaminhill.localmesh2.p2p

import kotlinx.coroutines.flow.SharedFlow

interface P2pRole {
    fun start()
    fun stop()
    val messages: SharedFlow<NetworkMessage>
    fun broadcast(message: NetworkMessage)
}