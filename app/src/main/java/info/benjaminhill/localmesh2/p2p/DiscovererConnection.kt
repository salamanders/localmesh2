package info.benjaminhill.localmesh2.p2p

import android.content.Context

open class DiscovererConnection(
    private val serviceId: String,
    appContext: Context,
) : AbstractConnection(appContext) {

    override fun start() {
        startDiscovery(serviceId)
    }

    override fun broadcast(message: NetworkMessage) {
        // Clients do not broadcast
    }
}