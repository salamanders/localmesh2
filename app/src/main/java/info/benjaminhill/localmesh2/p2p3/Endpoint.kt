package info.benjaminhill.localmesh2.p2p3

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class Endpoint(
    val id:String,
    private var name:String? = null,
    private var distance:Int? = null
) {
    var lastUpdate: Instant = Clock.System.now()
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Endpoint
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()

    fun refreshLastUpdate(): Endpoint {
        lastUpdate = Clock.System.now()
        return this
    }

    fun setName(name:String?): Endpoint {
        this.name = name
        return this
    }

    fun isPeer(): Boolean = distance == 1

    fun setIsPeer() :Endpoint {
        distance = 1
        refreshLastUpdate()
        return this
    }

    fun setIsNotPeer() :Endpoint {
        if(distance == 1) {
            distance = null
        }
        refreshLastUpdate()
        return this
    }
}