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
import info.benjaminhill.localmesh2.randomString
import kotlinx.serialization.ExperimentalSerializationApi
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
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
object MeshConnection {
    private const val MESH_SERVICE_ID = "info.benjaminhill.localmesh2.MESH"
    val STRATEGY: Strategy = Strategy.P2P_CLUSTER

    // Self identification, lasts the duration of the app
    val localHumanReadableName: String = randomString(5)

    /**
     * Callback interface for `MeshConnection` events.
     * Implemented by `HealingMesh` to receive notifications about connection results and incoming data.
     */
    interface MeshConnectionListener {
        /** Called when a connection attempt succeeds. */
        fun onSuccessfulConnection(endpointId: String)

        /** Called when a connection attempt fails. */
        fun onFailedConnection(endpointId: String)

        /** Called when a connection is disconnected. */
        fun onDisconnected(endpointId: String)

        /** Called when a data `Payload` is received from a connected endpoint. */
        fun onPayloadReceived(endpointId: String, message: NetworkMessage)
    }

    /** The main entry point for interacting with the Nearby Connections API. */
    private lateinit var connectionsClient: ConnectionsClient

    fun init(appContext: Context) {
        connectionsClient = Nearby.getConnectionsClient(appContext)
    }


    /** STATE 1: Endpoints that have been found through discovery but are not yet connected. */
    private val discoveredEndpoints = ConcurrentHashMap<String, DiscoveredEndpointInfo>()

    /** STATE 2: Endpoints for which a connection has been initiated but not yet resolved. */
    private val pendingConnections = ConcurrentHashMap<String, Instant>()

    /** STATE 3: Endpoints that are fully connected and can exchange data. */
    private val establishedConnections = ConcurrentHashMap<String, Instant>()

    fun getDiscoveredEndpoints(): Map<String, DiscoveredEndpointInfo> = discoveredEndpoints
    fun getPendingConnections(): Set<String> = pendingConnections.keys
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
            if (pendingConnections.remove(endpointId) == null) {
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
            Timber.i("CONNECTION $endpointId 2: onConnectionInitiated from ${connectionInfo.endpointName}")
            if (pendingConnections.put(endpointId, Clock.System.now()) == null) {
                Timber.i("CONNECTION $endpointId 2: Accepting new connection (inbound or outbound) from ${connectionInfo.endpointName} ($endpointId)")
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            } else {
                Timber.w("CONNECTION $endpointId 2: Duplicate onConnectionInitiated for $endpointId, ignoring.")
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            Timber.i("CONNECTION $endpointId 3: onConnectionResult")
            if (pendingConnections.remove(endpointId) == null) {
                Timber.w("CONNECTION $endpointId 3: It was not in pendingConnections. Unclear how we got here.")
            }
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                establishedConnections[endpointId] = Clock.System.now()
                Timber.i("CONNECTION $endpointId 3: Connection established.")
                HealingMesh.onSuccessfulConnection(endpointId)
            } else {
                Timber.w("CONNECTION $endpointId 3: Connection failed with status code ${result.status.statusCode}")
                HealingMesh.onFailedConnection(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Timber.i("CONNECTION $endpointId 4: onDisconnected")
            if (establishedConnections.remove(endpointId) == null) {
                Timber.w("CONNECTION $endpointId 4: onDisconnected but it was not in establishedConnections, maybe someone else removed it?")
            }
            HealingMesh.onDisconnected(endpointId)
        }
    }

    /** Handles incoming data from connected peers and forwards it to the listener. */
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) {
                Timber.w("Received non-bytes payload from $endpointId, ignoring.")
                return
            }
            val message = NetworkMessage.fromByteArray(payload.asBytes()!!)
            HealingMesh.onPayloadReceived(endpointId, message)
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
            Timber.w("Advertising started for service ${localHumanReadableName}.")
        }.addOnFailureListener { e ->
            Timber.w(e, "Advertising failed for service $localHumanReadableName.")
        }

        connectionsClient.startDiscovery(
            MESH_SERVICE_ID,
            endpointDiscoveryCallback, // Use the new callback
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        ).addOnSuccessListener {
            Timber.w("Discovery started.")
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

    /** Manually initiates a connection to a discovered endpoint. */
    fun requestConnection(endpointId: String) {
        Timber.i("CONNECTION $endpointId 1: requestConnection")
        if (pendingConnections.put(endpointId, Clock.System.now()) == null) {

            connectionsClient.requestConnection(
                localHumanReadableName,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener {
                Timber.w("CONNECTION $endpointId 1: Failed to request connection")
                if (pendingConnections.remove(endpointId) == null) {
                    Timber.e("CONNECTION $endpointId 1: Failed to remove from pendingConnections after a failed connection request, maybe someone else removed it?")
                }
            }
        } else {
            Timber.w("CONNECTION $endpointId 1: Tried to connect, but it was already in pendingConnections.")
        }
    }

    /** Manually disconnects from a connected endpoint. */
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
}
