package info.benjaminhill.localmesh2.p2p

import kotlin.time.ExperimentalTime

/**
 * Represents another device in the network.
 *
 * This data class holds the state of a known endpoint, including its unique ID,
 * human-readable name, and its distance from the local device in network hops.
 * It also tracks the last time a message was received from or about this endpoint
 * to determine its freshness.
 */
@OptIn(ExperimentalTime::class)
data class Endpoint(
    val id: String,
    private var name: String? = null,
    var distance: Int? = null
) {
    // var lastUpdate: Instant = Clock.System.now()
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Endpoint
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    fun setName(name: String?): Endpoint {
        this.name = name
        return this
    }

    // fun isPeer(): Boolean = distance == 1
}