package info.benjaminhill.localmesh2

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object EndpointRegistry {
    // Keyed off of endpoint ID
    private val allEndpoints: MutableMap<String, Endpoint> = ConcurrentHashMap()

    /** Gets an existing endpoint or creates a new one, logging its creation. Always updates the ts */
    fun get(endpointId: String, autoUpdateTs: Boolean = true): Endpoint {
        return allEndpoints.getOrPut(endpointId) {
            Log.d(TAG, "Creating new endpoint for $endpointId")
            Endpoint(
                id = endpointId,
                lastUpdatedTs = System.currentTimeMillis(),
                distance = null,
                immediateConnections = null,
            ).also {
                if (autoUpdateTs)
                    it.lastUpdatedTs = System.currentTimeMillis()
            }
        }
    }

    // All peers.  Shallow copy, so edits are ok.
    fun getAllKnownEndpoints(): Set<Endpoint> = allEndpoints.values.toSet()

    // Immediate peers
    fun getDirectlyConnectedEndpoints(): Set<Endpoint> =
        getAllKnownEndpoints().filter { it.distance == 1 }.toSet()

    private const val TAG = "P2P"
}