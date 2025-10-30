package info.benjaminhill.localmesh2.p2p

import android.content.Context

open class AdvertiserConnection(
    private val serviceId: String,
    appContext: Context,
) : AbstractConnection(appContext) {

    override fun start() {
        startAdvertising(serviceId)
    }

    override fun broadcast(message: NetworkMessage) {
        broadcastToAdvertiserEndpoints(message)
    }
}