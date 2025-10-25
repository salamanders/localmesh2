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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

class NearbyConnectionsManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private var reshuffleJob: Job? = null
    private var messageCleanupJob: Job? = null
    private val serviceId = "info.benjaminhill.localmesh2"
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val localName = CachedPrefs.getId(context)
    private val connectedEndpoints = mutableSetOf<String>()

    /**
     * Discovered endpoints and their reported number of connections.
     * endpointId -> connectionCount
     */
    private val discoveredEndpoints = ConcurrentHashMap<String, Int>()

    /**
     * A map of recently seen message UIDs to the timestamp of their arrival.
     * Used to prevent message loops and to re-broadcast messages to new peers.
     * uid -> timestamp
     */
    private val seenMessageIds = ConcurrentHashMap<String, Long>()

    fun startAdvertising() {
        // Strategy.P2P_CLUSTER is used as it supports M-to-N connections,
        // which is suitable for a dynamic snake topology where multiple
        // endpoints can be discovering or advertising simultaneously.
        val advertisingOptions =
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        // The local endpoint name is advertised with the number of connections it has.
        // e.g. "MyDeviceName:2"
        val advertisingName = "$localName:${connectedEndpoints.size}"

        connectionsClient.startAdvertising(
            advertisingName, serviceId, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "$advertisingName started Advertising.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "$advertisingName failed to start advertising", e)
        }
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            Log.i(TAG, "$localName started discovery.")
        }.addOnFailureListener { e ->
            Log.e(TAG, "$localName failed to start discovery", e)
        }
        startReshuffling()
        startMessageCleanup()
    }

    fun stop() {
        Log.i(TAG, "Stopping nearby connections.")
        reshuffleJob?.cancel()
        messageCleanupJob?.cancel()
        connectionsClient.stopAllEndpoints()
    }

    fun broadcast(bytes: ByteArray) {
        val uid = UUID.randomUUID().toString()
        val payloadBytes = uid.toByteArray() + bytes
        seenMessageIds[uid] = System.currentTimeMillis()
        Log.i(TAG, "Broadcasting ${payloadBytes.size} bytes with uid $uid")
        connectionsClient.sendPayload(
            connectedEndpoints.toList(),
            Payload.fromBytes(payloadBytes)
        )
    }

    private fun startMessageCleanup() {
        messageCleanupJob = scope.launch {
            while (true) {
                delay(1.minutes)
                val now = System.currentTimeMillis()
                val thirtyMinutesAgo = now - 30.minutes.inWholeMilliseconds
                seenMessageIds.entries.removeAll { it.value < thirtyMinutesAgo }
            }
        }
    }

    private fun startReshuffling() {
        reshuffleJob = scope.launch {
            while (true) {
                delay(1.minutes)
                val myConnectionCount = connectedEndpoints.size
                if (myConnectionCount < MIN_CONNECTIONS) {
                    // Not enough connections to reshuffle.
                    continue
                }

                // If all my peers are well-connected, drop one to find a new, less-connected peer.
                val allPeersWellConnected = connectedEndpoints.all { endpointId ->
                    (discoveredEndpoints[endpointId] ?: 0) >= 3
                }

                if (allPeersWellConnected) {
                    val randomEndpoint = connectedEndpoints.randomOrNull()
                    if (randomEndpoint != null) {
                        Log.i(TAG, "Reshuffling: all peers are well-connected. Dropping $randomEndpoint.")
                        connectionsClient.disconnectFromEndpoint(randomEndpoint)
                    }
                }
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes()!!
                val uid = String(bytes.copyOfRange(0, 36))
                val messageBytes = bytes.copyOfRange(36, bytes.size)

                if (seenMessageIds.containsKey(uid)) {
                    Log.i(TAG, "Ignoring duplicate message from $endpointId with uid $uid")
                    return
                }

                seenMessageIds[uid] = System.currentTimeMillis()
                Log.i(
                    TAG,
                    "Received ${messageBytes.size} bytes from $endpointId with uid $uid. Re-broadcasting."
                )

                // TODO: Do something with the messageBytes

                // Re-broadcast to other connected endpoints.
                val otherEndpoints = connectedEndpoints.filter { it != endpointId }
                if (otherEndpoints.isNotEmpty()) {
                    connectionsClient.sendPayload(
                        otherEndpoints,
                        Payload.fromBytes(bytes) // Send the original payload with UID
                    )
                }
            } else {
                Log.w(TAG, "Ignoring non-BYTES payload from $endpointId, type: ${payload.type}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS ->
                    Log.i(
                        TAG,
                        "SUCCESS: Payload ${update.payloadId} transfer to $endpointId complete."
                    )

                PayloadTransferUpdate.Status.FAILURE ->
                    Log.e(
                        TAG,
                        "FAILURE: Payload ${update.payloadId} transfer to $endpointId failed.",
                        Exception("PayloadTransferUpdate Failure")
                    )

                PayloadTransferUpdate.Status.CANCELED ->
                    Log.w(
                        TAG,
                        "CANCELED: Payload ${update.payloadId} transfer to $endpointId was canceled."
                    )

                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // Ignoring for now to keep logs clean. This is where you'd update a progress bar.
                }
            }

        }
    }

    private fun requestConnection(endpointId: String) {
        Log.i(TAG, "Requesting connection to $endpointId")
        connectionsClient.requestConnection(localName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Log.d(TAG, "Successfully requested connection to $endpointId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to request connection to $endpointId", e)
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo
        ) {
            Log.d(TAG, "onEndpointFound: $endpointId, ${discoveredEndpointInfo.endpointName}")
            val parts = discoveredEndpointInfo.endpointName.split(":")
            if (parts.size == 2) {
                val connectionCount = parts[1].toIntOrNull()
                if (connectionCount != null) {
                    discoveredEndpoints[endpointId] = connectionCount
                } else {
                    Log.w(TAG, "Could not parse connection count: ${discoveredEndpointInfo.endpointName}")
                    return
                }
            } else {
                Log.w(TAG, "Unexpected endpoint name format: ${discoveredEndpointInfo.endpointName}")
                return
            }

            val theirConnectionCount = discoveredEndpoints[endpointId] ?: return

            if (connectedEndpoints.contains(endpointId)) {
                return // Already connected
            }

            val myConnectionCount = connectedEndpoints.size

            if (myConnectionCount >= MAX_CONNECTIONS) {
                return // I'm full
            }
            if (theirConnectionCount >= MAX_CONNECTIONS) {
                return // They're full
            }

            // Connect if either of us is below the minimum threshold.
            if (myConnectionCount < MIN_CONNECTIONS || theirConnectionCount < MIN_CONNECTIONS) {
                Log.i(
                    TAG,
                    "Either we ($myConnectionCount) or they ($theirConnectionCount) are low on connections. Connecting to $endpointId."
                )
                requestConnection(endpointId)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "Endpoint lost: $endpointId")
            discoveredEndpoints.remove(endpointId)
        }
    }

    private val connectionLifecycleCallback: ConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(
                endpointId: String, connectionInfo: ConnectionInfo
            ) {
                Log.i(TAG, "onConnectionInitiated from $endpointId")
                if (connectedEndpoints.size < MAX_CONNECTIONS) {
                    Log.i(TAG, "Accepting connection from $endpointId")
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                } else {
                    Log.i(TAG, "Rejecting connection from $endpointId, already at max connections.")
                    connectionsClient.rejectConnection(endpointId)
                }
            }

            override fun onConnectionResult(
                endpointId: String, resolution: ConnectionResolution
            ) {
                if (resolution.status.isSuccess) {
                    Log.i(TAG, "Successfully connected to $endpointId")
                    connectedEndpoints.add(endpointId)
                } else {
                    Log.w(TAG, "Failed to connect to $endpointId: ${resolution.status.statusMessage}")
                }
            }

            override fun onDisconnected(endpointId: String) {
                Log.i(TAG, "onDisconnected from $endpointId")
                connectedEndpoints.remove(endpointId)

                if (connectedEndpoints.size < MIN_CONNECTIONS) {
                    Log.w(
                        TAG,
                        "Below minimum connections (${connectedEndpoints.size}), trying to find a new peer."
                    )
                    // Find the best candidate to connect to.
                    val bestCandidate = discoveredEndpoints.entries
                        .filter { !connectedEndpoints.contains(it.key) } // Not already connected
                        .filter { it.value < MAX_CONNECTIONS }           // Not full
                        .minByOrNull { it.value }                        // Least connected

                    if (bestCandidate != null) {
                        Log.i(TAG, "Aggressively reconnecting to ${bestCandidate.key}")
                        requestConnection(bestCandidate.key)
                    } else {
                        Log.w(TAG, "No suitable candidate for reconnection found.")
                    }
                }
            }
        }

    companion object {
        private const val TAG = "NCM"
        private const val MIN_CONNECTIONS = 2
        private const val MAX_CONNECTIONS = 4
    }
}