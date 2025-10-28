package info.benjaminhill.localmesh2

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object EndpointRegistry {
    // Keyed off of endpoint ID
    private val allEndpoints: MutableMap<String, Endpoint> = ConcurrentHashMap()

    /** Gets an existing endpoint or creates a new one, logging its creation. Always updates the ts */
    fun get(endpointId: String, autoUpdateTs: Boolean): Endpoint {
        val endpoint = allEndpoints.getOrPut(endpointId) {
            Log.d(TAG, "Creating new endpoint for $endpointId")
            Endpoint(
                id = endpointId,
                lastUpdatedTs = System.currentTimeMillis(),
                distance = null,
            )
        }
        if (autoUpdateTs) {
            endpoint.lastUpdatedTs = System.currentTimeMillis()
        }
        return endpoint
    }

    fun remove(endpointId: String) {
        allEndpoints.remove(endpointId)
    }

    // All peers.  Shallow copy, so edits are ok.
    fun getAllKnownEndpoints(): Set<Endpoint> = allEndpoints.values.toSet()

    // Clears all endpoints that you are not directly connected to
    fun clearNonDirectEndpoints() {
        allEndpoints.values.removeIf { it.distance != 1 }
    }

    // Immediate peers
    fun getDirectlyConnectedEndpoints(): Set<Endpoint> =
        getAllKnownEndpoints().filter { it.distance == 1 }.toSet()

    private const val TAG = "P2P"
}