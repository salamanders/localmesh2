package info.benjaminhill.localmesh2.p2p3

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import timber.log.Timber
import kotlin.random.Random
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
/**
 * Relies on local knowledge and graceful repair, not global consensus.
 * Desired State: Each node attempts to maintain 2-3 connections.
 * Discovery: Each node advertises and discovers simultaneously (the API supports this).
 *
 * Connection Logic:
 * * If I have < 2 connections, I will try to connect to the first peer I discover that isn't already connected to me *through any amount of hops*
 * * If there is no found peers, I will try to connect from farthest distance (N) down to nearest (2).
 * * I will accept any incoming connection request as long as I have < 4 total connections.
 * * I will reject any incoming connection request if I have >= 4 total connections.
 * * I will keep my connection list updated - if I haven't heard from a peer in X seconds, I will remove them.
 *
 * Resilience (The Gossip):
 * * Every 15 + rand(10) seconds, **if I haven't passed on any other messages to a given peer**, I will start a "gossip"
 * * A gossip is a NetworkMessage witout a command.
 * * All NetworkMessage have a "breadCrumbs" - an ordered list of the trail they have taken.  Each step is "id to timestamp (as a string)"
 * * When I receive any NetworkMessage, I refresh my known network list, adjusting the distance up or now, and refreshing the lastUpdate.
 * * All peers - both those discovered through Discovery, and those through a Breadcrumb, are candidates for Connection Logic.
 *
 * Healing (The "Weakest Link"):
 * * If one of my connections drops (e.g., Phone B crashes) and it puts me at < 2 connections, I immediately try to connect to someone from my "potential connections" list.
 * * This creates a random graph that is "self-healing." It's not a perfect ring, but it will be robust, connected, and won't suffer from a thundering herd. Data can be routed via the gossip-discovered paths.
 */

class HealingMeshConnection(appContext: Context) {

    val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    val localHumanReadableName: String = randomString(5)

    fun start() {
        connectionsClient.startAdvertising(
            localHumanReadableName,
            MESH_SERVICE_ID,
            object : ConnectionLifecycleCallback() {
                override fun onConnectionInitiated(
                    endpointId: String,
                    connectionInfo: ConnectionInfo
                ) {
                    Timber.i(endpointId)
                    val remoteName = connectionInfo.endpointName
                    EndpointRegistry[endpointId].apply {
                        refreshLastUpdate()
                        setName(remoteName)
                        // Don't set the distance
                    }
                    if (EndpointRegistry.numConnectedPeers() < MAX_PEERS) {
                        Timber.i("Advertising onConnectionInitiated from $endpointId ($remoteName, incoming=${connectionInfo.isIncomingConnection}). Accepting.")
                        connectionsClient.acceptConnection(endpointId, object : PayloadCallback() {
                            override fun onPayloadReceived(
                                endpointId: String, payload: Payload
                            ) {
                                EndpointRegistry[endpointId].setIsPeer()
                                TODO("Not yet implemented")

                                /*
                                *            Log.i(TAG, "onPayloadReceived message from $endpointId")
                if (payload.type == Payload.Type.BYTES) {
                    val message = NetworkMessage.Companion.fromByteArray(payload.asBytes()!!)
                    Log.i(TAG, "Received message: $message")
                    handleReceivedMessage(message)
                }
                                * */
                            }

                            override fun onPayloadTransferUpdate(
                                endpointId: String,
                                p: PayloadTransferUpdate
                            ) {
                                EndpointRegistry[endpointId].setIsPeer()
                            }
                        })
                    } else {
                        Timber.i("Advertising onConnectionInitiated from $endpointId ($remoteName, incoming=${connectionInfo.isIncomingConnection}). Full, rejecting.")
                        EndpointRegistry[endpointId].setIsNotPeer()
                    }
                }

                override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                    Timber.i("$endpointId, $result")
                    if (result.status.isSuccess) {
                       Timber.i("Advertising: onConnectionResult success for $endpointId.")
                        EndpointRegistry[endpointId].setIsPeer()
                    } else {
                        Timber.i("Advertising: onConnectionResult failure for $endpointId. Code: ${result.status.statusCode}")
                        EndpointRegistry[endpointId].setIsNotPeer()
                    }
                }

                override fun onDisconnected(endpointId: String) {
                    Timber.i(endpointId)
                    EndpointRegistry[endpointId].setIsNotPeer()
                }
            },
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnSuccessListener {
            Timber.i("Advertising started for service $localHumanReadableName.")
        }.addOnFailureListener { e ->
            Timber.i(e, "Advertising failed for service $localHumanReadableName.")
        }
    }

    fun stop() {
        Timber.i("Manually stopped")
        connectionsClient.stopAllEndpoints()
        EndpointRegistry.clear()
    }

    companion object {
        const val MESH_SERVICE_ID = "info.benjaminhill.localmesh2.MESH"
        val STRATEGY = Strategy.P2P_CLUSTER
        const val MAX_PEERS = 4

    }
}