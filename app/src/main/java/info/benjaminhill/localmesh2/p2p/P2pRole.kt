package info.benjaminhill.localmesh2.p2p

interface P2pRole {
    fun start()
    fun stop()
    fun broadcast(message: NetworkMessage)
    fun getConnectedPeerCount(): Int
}