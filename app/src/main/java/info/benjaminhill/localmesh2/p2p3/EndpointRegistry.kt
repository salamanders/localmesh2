package info.benjaminhill.localmesh2.p2p3

import timber.log.Timber
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * All endpoints that have ever been seen.  Some may no longer be viable.
 */
@OptIn(ExperimentalTime::class)
object EndpointRegistry {
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

    fun numConnectedPeers(): Int = endpoints.values.filter { it.isPeer() }.size

    fun getPotentialConnections(): Set<Endpoint> = endpoints.values.filter {
        !it.isPeer() &&
        it.lastUpdate > Clock.System.now().minus(3.minutes)
    }.toSet()

    const val TAG = "Endpoints"
}