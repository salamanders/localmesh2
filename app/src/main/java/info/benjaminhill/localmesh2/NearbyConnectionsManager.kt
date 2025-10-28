package info.benjaminhill.localmesh2

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.minutes

/**
 * Manages all low-level interactions with the Google Nearby Connections API.
 * This class is responsible for advertising, discovering, and managing the lifecycle of connections.
 */
object NearbyConnectionsManager {
    private const val TAG = "P2P"
    private const val HUB_SERVICE_ID = "info.benjaminhill.localmesh2.hub"
    private const val CLIENT_SERVICE_ID = "info.benjaminhill.localmesh2.client"

    private const val MAX_LIEUTENANTS = 5
    private const val MAX_CLIENTS_PER_LIEUTENANT = 6

    private val STRATEGY = Strategy.P2P_STAR

    private val seenMessageIds = ConcurrentHashMap<String, Long>()
    private val connectionContinuations =
        ConcurrentHashMap<String, CancellableContinuation<Boolean>>()

    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope
    private lateinit var localId: String
    private lateinit var messageCleanupJob: Job
    private lateinit var connectionsClient: ConnectionsClient

    private val lieutenantEndpointIds = mutableListOf<String>()
    private val clientEndpointIds = mutableListOf<String>()
    private var mainHubEndpointId: String? = null

    fun initialize(newContext: Context, newScope: CoroutineScope) {
        appContext = newContext.applicationContext
        scope = newScope
        localId = CachedPrefs.getId(appContext)
        connectionsClient = Nearby.getConnectionsClient(appContext)

        messageCleanupJob = scope.launch {
            while (true) {
                delay(1.minutes)
                val now = System.currentTimeMillis()
                val thirtyMinutesAgo = now - 30.minutes.inWholeMilliseconds
                seenMessageIds.entries.removeAll { it.value < thirtyMinutesAgo }
            }
        }
    }

    fun start() {
        when (RoleManager.role.value) {
            Role.HUB -> startHub()
            Role.LIEUTENANT -> startLieutenant()
            Role.CLIENT -> startClient()
        }
    }

    fun stop() {
        Log.w(TAG, "NearbyConnectionsManager.stop() called.")
        connectionsClient.stopAllEndpoints()
        messageCleanupJob.cancel()
    }

    fun broadcastDisplayMessage(displayTarget: String) {
        val message = NetworkMessage(
            sendingNodeId = localId,
            displayTarget = displayTarget
        )
        broadcastInternal(message)
    }

    internal fun broadcastInternal(message: NetworkMessage) {
        seenMessageIds[message.messageId] = System.currentTimeMillis()
        val payload = Payload.fromBytes(message.toByteArray())
        when (RoleManager.role.value) {
            Role.HUB -> {
                Log.i(TAG, "Hub broadcasting to ${lieutenantEndpointIds.size} lieutenants")
                connectionsClient.sendPayload(lieutenantEndpointIds, payload)
            }
            Role.LIEUTENANT -> {
                Log.i(TAG, "Lieutenant broadcasting to ${clientEndpointIds.size} clients")
                connectionsClient.sendPayload(clientEndpointIds, payload)
            }
            Role.CLIENT -> {
                // Clients do not broadcast
            }
        }
    }

    private fun startHub() {
        Log.i(TAG, "Starting Hub...")
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            localId, HUB_SERVICE_ID, hubToLieutenantConnectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Hub advertising started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Hub advertising failed.", e)
        }
    }

    private fun startLieutenant() {
        Log.i(TAG, "Starting Lieutenant...")
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            localId, CLIENT_SERVICE_ID, clientConnectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Lieutenant advertising started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Lieutenant advertising failed.", e)
        }

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            HUB_SERVICE_ID, hubEndpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Lieutenant discovery for Hub started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Lieutenant discovery for Hub failed.", e)
        }
    }

    private fun startClient() {
        Log.i(TAG, "Starting Client...")
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            CLIENT_SERVICE_ID, clientEndpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Client discovery for Lieutenants started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Client discovery for Lieutenants failed.", e)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedMessage = NetworkMessage.fromByteArray(payload.asBytes()!!)
                if (seenMessageIds.containsKey(receivedMessage.messageId)) {
                    return
                }
                seenMessageIds[receivedMessage.messageId] = System.currentTimeMillis()

                when (RoleManager.role.value) {
                    Role.LIEUTENANT -> {
                        if (endpointId == mainHubEndpointId) {
                            broadcastInternal(receivedMessage)
                        }
                    }
                    Role.CLIENT -> {
                        WebAppActivity.navigateTo(receivedMessage.displayTarget!!)
                    }
                    else -> {
                        // Hub doesn't receive payloads
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No special handling needed for this topology
        }
    }

    private val hubToLieutenantConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            if (lieutenantEndpointIds.size >= MAX_LIEUTENANTS) {
                connectionsClient.rejectConnection(endpointId)
            } else {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                lieutenantEndpointIds.add(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            lieutenantEndpointIds.remove(endpointId)
        }
    }

    private val lieutenantToHubConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            // Lieutenants automatically accept Hub connections
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                mainHubEndpointId = endpointId
            } else {
                mainHubEndpointId = null
                if (RoleManager.role.value == Role.LIEUTENANT) {
                    RoleManager.setRole(Role.CLIENT)
                    startClient()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            mainHubEndpointId = null
            if (RoleManager.role.value == Role.LIEUTENANT) {
                startLieutenant()
            }
        }
    }

    private val clientConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            if (clientEndpointIds.size >= MAX_CLIENTS_PER_LIEUTENANT) {
                connectionsClient.rejectConnection(endpointId)
            } else {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                clientEndpointIds.add(endpointId)
            } else {
                if (RoleManager.role.value == Role.CLIENT) {
                    startClient()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            clientEndpointIds.remove(endpointId)
            if (RoleManager.role.value == Role.CLIENT) {
                startClient()
            }
        }
    }

    private val hubEndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (mainHubEndpointId == null) {
                connectionsClient.requestConnection(localId, endpointId, lieutenantToHubConnectionLifecycleCallback)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Don't care
        }
    }

    private val clientEndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (RoleManager.role.value == Role.CLIENT && mainHubEndpointId == null) {
                connectionsClient.requestConnection(localId, endpointId, clientConnectionLifecycleCallback)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Don't care
        }
    }
}
