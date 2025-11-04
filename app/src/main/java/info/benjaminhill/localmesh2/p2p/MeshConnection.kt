package info.benjaminhill.localmesh2.p2p

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import info.benjaminhill.localmesh2.p2p.NetworkHolder.localHumanReadableName
import kotlinx.serialization.ExperimentalSerializationApi
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant


/**
 * A low-level wrapper for the Google Nearby Connections API.
 * This class is responsible for the direct mechanics of the peer-to-peer network, including:
 * - Advertising the local device.
 * - Discovering remote devices.
 * - Managing the lifecycle of connections (requesting, accepting, disconnecting).
 * - Tracking the state of endpoints (discovered, pending, established).
 * - Sending and receiving raw `Payload` objects.
 * It abstracts the direct API calls and provides a cleaner interface for higher-level logic,
 * which is handled by `HealingMesh`.
 */
@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
class MeshConnection(
    appContext: Context,
    private val listener: MeshConnectionListener
) {

    /**
     * Callback interface for `MeshConnection` events.
     * Implemented by `HealingMesh` to receive notifications about connection results and incoming data.
     */
    interface MeshConnectionListener {
        /** Called when a connection attempt is resolved (succeeded or failed). */
        fun onConnectionResult(endpointId: String, result: ConnectionResolution)

        /** Called when a data `Payload` is received from a connected endpoint. */
        fun onPayloadReceived(endpointId: String, payload: Payload)
    }

    /** The main entry point for interacting with the Nearby Connections API. */
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    /** STATE 1: Endpoints that have been found through discovery but are not yet connected. */
    private val discoveredEndpoints = ConcurrentHashMap<String, DiscoveredEndpointInfo>()

    /** STATE 2: Endpoints for which a connection has been initiated but not yet resolved. */
    private val pendingConnections = CopyOnWriteArraySet<String>()

    /** STATE 3: Endpoints that are fully connected and can exchange data. */
    private val establishedConnections = ConcurrentHashMap<String, Instant>()

    fun getDiscoveredEndpoints(): Map<String, DiscoveredEndpointInfo> = discoveredEndpoints
    fun getPendingConnections(): Set<String> = pendingConnections
    fun getEstablishedConnections(): Map<String, Instant> = establishedConnections

    /** Handles populating the `discoveredEndpoints` map. */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == MESH_SERVICE_ID) {
                Timber.i("Discovery: Found endpoint $endpointId (${info.endpointName})")
                discoveredEndpoints[endpointId] = info
            } else {
                Timber.w("Discovery: Ignoring endpoint on different service ID: $endpointId (${info.endpointName}) on ${info.serviceId}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.i("Discovery: Lost endpoint $endpointId")
            if (discoveredEndpoints.remove(endpointId) == null) {
                Timber.w("Tried to remove lost endpoint $endpointId from discoveredEndpoints, but it was not present.")
            }
            if (!pendingConnections.remove(endpointId)) {
                Timber.w("Tried to remove lost endpoint $endpointId from pendingConnections, but it was not present.")
            }
            if (establishedConnections.remove(endpointId) != null) {
                Timber.d("Removed lost endpoint $endpointId from establishedConnections.")
            }
        }
    }

    /** Handles all connection lifecycle events, both incoming and outgoing. */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Timber.i("onConnectionInitiated from ${connectionInfo.endpointName} ($endpointId)")
            if (pendingConnections.add(endpointId)) {
                acceptConnection(endpointId)
            } else {
                Timber.w("Duplicate onConnectionInitiated for $endpointId, ignoring.")
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!pendingConnections.remove(endpointId)) {
                Timber.w("onConnectionResult for $endpointId, but it was not in pendingConnections.")
            }
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                establishedConnections[endpointId] = Clock.System.now()
            }
            listener.onConnectionResult(endpointId, result)
        }

        override fun onDisconnected(endpointId: String) {
            Timber.i("onDisconnected from $endpointId")
            if (establishedConnections.remove(endpointId) == null) {
                Timber.w("onDisconnected from $endpointId, but it was not in establishedConnections.")
            }
        }
    }

    /** Handles incoming data from connected peers and forwards it to the listener. */
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            listener.onPayloadReceived(endpointId, payload)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Nothing to do here for now
        }
    }

    /** Starts the mesh connection, beginning both advertising and discovery. */
    fun start() {
        connectionsClient.startAdvertising(
            localHumanReadableName,
            MESH_SERVICE_ID,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        ).addOnSuccessListener {
            Timber.i("Advertising started for service ${localHumanReadableName}.")
        }.addOnFailureListener { e ->
            Timber.i(e, "Advertising failed for service $localHumanReadableName.")
        }

        connectionsClient.startDiscovery(
            MESH_SERVICE_ID,
            endpointDiscoveryCallback, // Use the new callback
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        ).addOnSuccessListener {
            Timber.i("Discovery started.")
        }.addOnFailureListener { e ->
            Timber.e(e, "Discovery failed.")
        }
    }

    /** Stops all Nearby Connections activity, disconnects from all endpoints, and clears state. */
    fun stop() {
        Timber.i("Manually stopped")
        connectionsClient.stopAllEndpoints()
        discoveredEndpoints.clear()
        pendingConnections.clear()
        establishedConnections.clear()
    }

    /** Internal method to accept an incoming connection request. */
    private fun acceptConnection(endpointId: String) {
        connectionsClient.acceptConnection(endpointId, payloadCallback)
    }

    /** Initiates a connection to a discovered endpoint. */
    fun requestConnection(endpointId: String) {
        if (pendingConnections.add(endpointId)) {
            connectionsClient.requestConnection(
                localHumanReadableName,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener {
                Timber.w("Failed to request connection to $endpointId")
                if (!pendingConnections.remove(endpointId)) {
                    Timber.e("Failed to remove $endpointId from pendingConnections after a failed connection request.")
                }
            }
        } else {
            Timber.w("Tried to connect to $endpointId, but it was already in pendingConnections.")
        }
    }

    /** Disconnects from a connected endpoint. */
    fun disconnectFromEndpoint(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

    /** Sends a raw payload to a list of specific endpoint IDs. */
    fun sendPayload(endpointIds: Collection<String>, payload: Payload) {
        connectionsClient.sendPayload(endpointIds.toList(), payload)
    }

    /**
     * Forwards a message to all connected peers except the original sender.
     * Adds the current device to the message's breadcrumbs to prevent loops.
     */
    private fun forwardMessage(message: NetworkMessage) {
        val msgToForward =
            message.copy(breadCrumbs = message.breadCrumbs + (localHumanReadableName to Clock.System.now()))
        val payload = Payload.fromBytes(msgToForward.toByteArray())

        // Rely on the "no duplicate messages" checking to avoid infinite loops.
        val targets = establishedConnections.keys

        if (targets.isNotEmpty()) {
            Timber.d("Forwarding message ${message.id} to $targets")
            sendPayload(targets, payload)
        } else {
            Timber.d("No targets for message ${message.id}, dropping.")
        }
    }

    /** Initiates a broadcast of a message to all connected peers. */
    fun broadcast(message: NetworkMessage) {
        Timber.d("BROADCAST: $message")
        NetworkMessageRegistry.isFirstSeen(message)
        forwardMessage(message)
    }


    companion object {
        private const val MESH_SERVICE_ID = "info.benjaminhill.localmesh2.MESH"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }
}
