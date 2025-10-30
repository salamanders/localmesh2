package info.benjaminhill.localmesh2.p2p

import android.content.Context

class ClientConnection(
    appContext: Context,
) : DiscovererConnection(LieutenantConnection.L2C_CHANNEL, appContext)