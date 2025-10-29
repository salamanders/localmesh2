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
    private const val MAX_LIEUTENANTS = 5
    private const val MAX_CLIENTS_PER_LIEUTENANT = 7
    private val STRATEGY = Strategy.P2P_CLUSTER
    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope
    private lateinit var localId: String
    private lateinit var messageCleanupJob: Job
    private lateinit var connectionsClient: ConnectionsClient
    private var hubConnectionTimeoutJob: Job? = null

    @OptIn(ExperimentalAtomicApi::class)
    val role: AtomicReference<Role> = AtomicReference(Role.LIEUTENANT)
    val lieutenantEndpointIds = mutableListOf<String>()
    val clientEndpointIds = mutableListOf<String>()
    var mainHubEndpointId: String? = null
    fun initialize(newContext: Context, newScope: CoroutineScope) {
        appContext = newContext.applicationContext
        scope = newScope
        localId = CachedPrefs.getId(appContext)
        connectionsClient = Nearby.getConnectionsClient(appContext)
    }

    suspend fun start(reboot: Boolean = false) {
        if (reboot) {
            runBlocking {
                connectionsClient.stopAllEndpoints()
                delay(5.seconds)
                mainHubEndpointId = null
                lieutenantEndpointIds.clear()
                clientEndpointIds.clear()
                role.store(Role.CLIENT)
            }
        }
        when (role.load()) {
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
            displayTarget = displayTarget
        )
        broadcastInternal(message)
    }

    internal fun broadcastInternal(message: NetworkMessage) {
        val outboundPayload = Payload.fromBytes(message.toByteArray())
        when (role.load()) {
            Role.HUB -> {
                Log.i(TAG, "Hub broadcasting to ${lieutenantEndpointIds.size} lieutenants")
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

    private fun startHub() {
        Log.i(TAG, "Starting Hub...")
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            localId,
            Role.HUB.advertisedServiceId!!,
            hubConnectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Hub advertising started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Hub advertising failed.", e)
        }
    }

    private fun startLieutenant() {
        Log.i(TAG, "Starting Lieutenant...")
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        hubConnectionTimeoutJob?.cancel()
        hubConnectionTimeoutJob = scope.launch {
            Log.i(TAG, "Lieutenant Hub connection timed search started.")
            delay(60.seconds)
            Log.w(TAG, "Lieutenant Hub connection search timed out. Demoting to CLIENT.")
            mainHubEndpointId = null
            if (role.load() == Role.LIEUTENANT) {
                role.store(Role.CLIENT)
                start(reboot = true)
            }
        }

        connectionsClient.startDiscovery(
            Role.HUB.advertisedServiceId!!, hubEndpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Lieutenant discovery for Hub started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Lieutenant discovery for Hub failed.", e)
            runBlocking { start(reboot = true) }
        }
    }

    private suspend fun startClient() {
        Log.i(TAG, "Starting Client...")
        // First give the Lieutenant mode time to clear out
        connectionsClient.stopDiscovery()
        connectionsClient.stopAdvertising()
        delay(5.seconds)
        // No Advertising
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            Role.LIEUTENANT.advertisedServiceId!!, clientEndpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Client discovery for Lieutenants started.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Client discovery for Lieutenants failed.", e)
            runBlocking { start(reboot = true) }
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
                            "Lieutenant received message from Hub: ${receivedMessage.displayTarget}"
                        )
                        // Both rebroadcast and display
                        broadcastInternal(receivedMessage)
                        WebAppActivity.navigateTo(receivedMessage.displayTarget)
                    }

                    Role.CLIENT -> {
                        Log.i(
                            TAG,
                            "Client received message from Lieutenant: ${receivedMessage.displayTarget}"
                        )
                        // Just display
                        WebAppActivity.navigateTo(receivedMessage.displayTarget)
                    }

                    else -> {
                        Log.e(TAG, "Why did hub just get told to ${receivedMessage.displayTarget}?")
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No special handling needed for this topology
        }
    }
    private val hubConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            if (lieutenantEndpointIds.size >= MAX_LIEUTENANTS) {
                Log.i(TAG, "Lieutenant is NOT authorized to connect to this Hub: $endpointId")
                connectionsClient.rejectConnection(endpointId)
            } else {
                Log.i(TAG, "Lieutenant is authorized to connect to this Hub: $endpointId")
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.i(TAG, "Lieutenant now connected to this Hub: $endpointId")
                lieutenantEndpointIds.add(endpointId)
            } else {
                Log.i(TAG, "Lieutenant filed to connect to this Hub: $endpointId")
            }
        }

        override fun onDisconnected(endpointId: String) {
            lieutenantEndpointIds.remove(endpointId)
            Log.i(TAG, "Lieutenant disconnected from this Hub: $endpointId")
        }
    }
    private val lieutenantToHubConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
                Log.w(TAG, "Hi Hub, I would like to be your lieutenant")
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                hubConnectionTimeoutJob?.cancel()
                if (result.status.isSuccess) {
                    Log.w(TAG, "Hi Hub thanks for letting me be your lieutenant")
                    mainHubEndpointId = endpointId

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
                        runBlocking { start(reboot = true) }
                    }

                } else {
                    Log.w(TAG, "I guess the hub didn't want me as a lieutenant")
                    mainHubEndpointId = null
                    if (role.load() == Role.LIEUTENANT) {
                        Log.w(TAG, "demoting self to client.")
                        role.store(Role.CLIENT)
                        runBlocking {
                            start()
                        }
                    } else {
                        Log.e(TAG, "Musta already changed rank.")
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                mainHubEndpointId = null
                if (role.load() == Role.LIEUTENANT) {
                    runBlocking {
                        Log.w(TAG, "LIEUTENANT attempting to find new hub.")
                        start()
                    }
                } else {
                    Log.e(TAG, "Musta already changed rank.")
                    runBlocking { start(reboot = true) }
                }
            }
        }

    private val clientConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            if (clientEndpointIds.size >= MAX_CLIENTS_PER_LIEUTENANT) {
                Log.w(TAG, "LIEUTENANT telling client to look elsewhere.")
                connectionsClient.rejectConnection(endpointId)
            } else {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                Log.i(TAG, "LIEUTENANT telling client please connect!")
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                clientEndpointIds.add(endpointId)
                Log.i(TAG, "LIEUTENANT telling client welcome!")
            } else {
                if (role.load() == Role.CLIENT) {
                    Log.w(TAG, "client retrying to connect.")
                    runBlocking {
                        start()
                    }
                } else {
                    Log.e(TAG, "How could a client change rank? ${role.load()}")
                    runBlocking { start(reboot = true) }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            clientEndpointIds.remove(endpointId)
            if (role.load() == Role.CLIENT) {
                Log.w(TAG, "client disconnected, retrying to connect.")
                runBlocking {
                    startClient()
                }
            } else {
                Log.e(TAG, "How could a client change rank? ${role.load()}")
                runBlocking { start(reboot = true) }
            }
        }
    }

    private val hubEndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (mainHubEndpointId == null) {
                connectionsClient.requestConnection(
                    localId, endpointId, lieutenantToHubConnectionLifecycleCallback
                )
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Don't care
        }
    }
    private val clientEndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (role.load() == Role.CLIENT && mainHubEndpointId == null) {
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
