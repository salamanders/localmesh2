@file:OptIn(ExperimentalAtomicApi::class)

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

/**
 * Manages all low-level interactions with the Google Nearby Connections API.
 * This class is responsible for advertising, discovering, and managing the lifecycle of connections.
 */
object NearbyConnectionsManager {
    private const val TAG = "P2P"
    private val STRATEGY = Strategy.P2P_CLUSTER
    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope
    private lateinit var localId: String
    private lateinit var messageCleanupJob: Job
    private lateinit var connectionsClient: ConnectionsClient
    private var connectionTimeout: Job? = null

    @OptIn(ExperimentalAtomicApi::class)
    val role: AtomicReference<Role> = AtomicReference(Role.LIEUTENANT)
    val lieutenantEndpointIds = mutableListOf<String>()
    val clientEndpointIds = mutableListOf<String>()
    var mainCommanderEndpointId: String? = null
    var connectedLieutenantEndpointId: String? = null
    fun initialize(newContext: Context, newScope: CoroutineScope) {
        appContext = newContext.applicationContext
        scope = newScope
        localId = CachedPrefs.getId(appContext)
        connectionsClient = Nearby.getConnectionsClient(appContext)
    }

    suspend fun start() {
        when (role.load()) {
            Role.COMMANDER -> startCommander()
            Role.LIEUTENANT -> startLieutenant()
            Role.CLIENT -> startClient()
        }
    }

    private fun restart() {
        scope.launch {
            Log.i(TAG, "Restarting connection process.")
            connectionsClient.stopAllEndpoints()
            delay(10.seconds)
            start()
        }
    }

    fun stop() {
        Log.w(TAG, "NearbyConnectionsManager.stop() called.")
        connectionsClient.stopAllEndpoints()
        if(::messageCleanupJob.isInitialized) {
            messageCleanupJob.cancel()
        }
    }

    fun broadcastDisplayMessage(displayTarget: String) {
        val message = NetworkMessage(
            displayTarget = displayTarget
        )
        broadcastInternal(message)
    }

    internal fun broadcastInternal(message: NetworkMessage) {
        val outboundPayload = Payload.fromBytes(message.toByteArray())
        when (role.load()) {
            Role.COMMANDER -> {
                Log.i(TAG, "Commander broadcasting to ${lieutenantEndpointIds.size} lieutenants")
                connectionsClient.sendPayload(lieutenantEndpointIds, outboundPayload)
            }

            Role.LIEUTENANT -> {
                Log.i(TAG, "Lieutenant broadcasting to ${clientEndpointIds.size} clients")
                connectionsClient.sendPayload(clientEndpointIds, outboundPayload)
            }

            Role.CLIENT -> {
                // Clients do not broadcast
                Log.e(TAG, "Why is a client trying to broadcast?")
            }
        }
    }

    private fun startCommander() {
        Log.i(TAG, "Starting Commander...")
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            localId,
            Role.COMMANDER.advertisedServiceId!!,
            commanderConnectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Commander advertising started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Commander advertising failed.", e)
        }
    }

    private fun startLieutenant() {
        Log.i(TAG, "Starting Lieutenant...")
        mainCommanderEndpointId = null
        connectionsClient.stopDiscovery()

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        connectionTimeout?.cancel()
        connectionTimeout = scope.launch {
            Log.i(TAG, "Lieutenant connection timed search started.")
            delay(60.seconds)
            Log.w(TAG, "Lieutenant connection search timed out. Restarting.")
            restart()
        }

        connectionsClient.startDiscovery(
            Role.COMMANDER.advertisedServiceId!!, commanderEndpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Lieutenant discovery for Commander started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Lieutenant discovery for Commander failed.", e)
            restart()
        }
    }

    private fun startClient() {
        Log.i(TAG, "Starting Client...")
        connectedLieutenantEndpointId = null
        connectionsClient.stopDiscovery()

        connectionTimeout?.cancel()
        connectionTimeout = scope.launch {
            Log.i(TAG, "Client connection timed search started.")
            delay(60.seconds)
            Log.w(TAG, "Client connection search timed out. Restarting.")
            restart()
        }

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            Role.LIEUTENANT.advertisedServiceId!!, clientEndpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Client discovery for Lieutenants started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Client discovery for Lieutenants failed.", e)
            restart()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedMessage = NetworkMessage.fromByteArray(payload.asBytes()!!)
                when (role.load()) {
                    Role.LIEUTENANT -> {
                        Log.i(
                            TAG,
                            "Lieutenant received message from Commander: ${receivedMessage.displayTarget}"
                        )
                        broadcastInternal(receivedMessage)
                        WebAppActivity.navigateTo(receivedMessage.displayTarget)
                    }

                    Role.CLIENT -> {
                        Log.i(
                            TAG,
                            "Client received message from Lieutenant: ${receivedMessage.displayTarget}"
                        )
                        WebAppActivity.navigateTo(receivedMessage.displayTarget)
                    }

                    else -> {
                        Log.e(
                            TAG,
                            "Why did commander just get told to ${receivedMessage.displayTarget}?"
                        )
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No special handling needed for this topology
        }
    }
    private val commanderConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.i(TAG, "Lieutenant is authorized to connect to this Commander: $endpointId")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.i(TAG, "Lieutenant now connected to this Commander: $endpointId")
                lieutenantEndpointIds.add(endpointId)
            } else {
                Log.i(TAG, "Lieutenant filed to connect to this Commander: $endpointId")
            }
        }

        override fun onDisconnected(endpointId: String) {
            lieutenantEndpointIds.remove(endpointId)
            Log.i(TAG, "Lieutenant disconnected from this Commander: $endpointId")
        }
    }
    private val lieutenantToCommanderConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(
                endpointId: String,
                connectionInfo: ConnectionInfo
            ) {
                Log.i(TAG, "Lieutenant requesting connection to Commander: $endpointId")
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                connectionTimeout?.cancel()
                if (result.status.isSuccess) {
                    Log.i(TAG, "Lieutenant connected to Commander: $endpointId")
                    mainCommanderEndpointId = endpointId
                    connectionsClient.stopDiscovery() // Found our commander.

                    // Now start advertising to clients
                    val advertisingOptions =
                        AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
                    connectionsClient.startAdvertising(
                        localId,
                        Role.LIEUTENANT.advertisedServiceId!!,
                        clientConnectionLifecycleCallback,
                        advertisingOptions
                    ).addOnSuccessListener {
                        Log.i(TAG, "Lieutenant advertising for clients started.")
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "Lieutenant advertising for clients failed.", e)
                        restart()
                    }
                } else {
                    Log.w(TAG, "Commander rejected connection. Restarting discovery.")
                    mainCommanderEndpointId = null
                    restart()
                }
            }

            override fun onDisconnected(endpointId: String) {
                Log.w(TAG, "Lieutenant disconnected from Commander. Restarting discovery.")
                mainCommanderEndpointId = null
                restart()
            }
        }

    private val clientConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            // This is called on both Lieutenant and Client.
            Log.i(TAG, "Accepting connection from $endpointId")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                when (role.load()) {
                    Role.LIEUTENANT -> {
                        Log.i(TAG, "Client connected to this Lieutenant: $endpointId")
                        clientEndpointIds.add(endpointId)
                    }

                    Role.CLIENT -> {
                        Log.i(TAG, "Client connected to Lieutenant: $endpointId")
                        connectionTimeout?.cancel()
                        connectionsClient.stopDiscovery()
                        connectedLieutenantEndpointId = endpointId
                    }

                    else -> {}
                }
            } else {
                if (role.load() == Role.CLIENT) {
                    Log.w(TAG, "Client failed to connect to Lieutenant. Restarting discovery.")
                    connectedLieutenantEndpointId = null
                    restart()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            when (role.load()) {
                Role.LIEUTENANT -> {
                    clientEndpointIds.remove(endpointId)
                    Log.i(TAG, "Client disconnected from this Lieutenant: $endpointId")
                }

                Role.CLIENT -> {
                    Log.w(TAG, "Client disconnected from Lieutenant. Restarting discovery.")
                    connectedLieutenantEndpointId = null
                    restart()
                }

                else -> {}
            }
        }
    }

    private val commanderEndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (mainCommanderEndpointId == null) {
                connectionsClient.requestConnection(
                    localId, endpointId, lieutenantToCommanderConnectionLifecycleCallback
                )
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Don't care
        }
    }
    private val clientEndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (role.load() == Role.CLIENT && connectedLieutenantEndpointId == null) {
                connectionsClient.requestConnection(
                    localId, endpointId, clientConnectionLifecycleCallback
                )
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Don't care
        }
    }
}
