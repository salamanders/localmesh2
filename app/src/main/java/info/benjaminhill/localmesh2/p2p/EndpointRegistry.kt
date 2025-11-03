package info.benjaminhill.localmesh2.p2p

import timber.log.Timber
import kotlin.time.ExperimentalTime

/**
 * A singleton object that acts as the single source of truth for all known endpoints in the network.
 *
 * It maintains a map of endpoint IDs to `Endpoint` objects. This registry is responsible for:
 * - Creating new `Endpoint` entries when a new endpoint is discovered.
 * - Providing access to the state of any known endpoint.
 * - Pruning stale endpoints that haven't been seen recently.
 * - Providing lists of endpoints that are direct peers or potential connection candidates.
 */
@OptIn(ExperimentalTime::class)
object EndpointRegistry {

    val localHumanReadableName: String = randomString(5)

    private val endpoints = mutableMapOf<String, Endpoint>()

    operator fun get(id: String): Endpoint {
        return endpoints.getOrPut(id) {
            Timber.i("EndpointRegistry first seen: $id")
            Endpoint(id)
        }
    }

    fun clear() {
        Timber.i("EndpointRegistry cleared")
        endpoints.clear()
    }
}