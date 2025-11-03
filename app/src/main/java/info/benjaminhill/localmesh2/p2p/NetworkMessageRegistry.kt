package info.benjaminhill.localmesh2.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A singleton object responsible for de-duplicating incoming `NetworkMessage`s.
 *
 * It maintains a cache of recently seen message IDs. This is a critical component
 * of the gossip protocol to prevent broadcast storms and infinite message loops.
 * A background coroutine periodically prunes the cache to prevent it from growing indefinitely.
 */
@OptIn(ExperimentalTime::class)
object NetworkMessageRegistry {
    /** Map of MessageUuids to the timestamp we first saw them. Used for de-duplication. */
    private val seenMessageIds = mutableMapOf<String, Pair<NetworkMessage, Instant>>()

    init {
        CoroutineScope(Job() + Dispatchers.IO).launch {
            while (isActive) {
                val ago = Clock.System.now().minus(MESSAGE_CACHE_EXPIRY)
                seenMessageIds.entries.removeIf { it.value.second < ago }
                delay(10.seconds)
            }
        }
    }

    fun isFirstSeen(networkMessage: NetworkMessage): Boolean =
        seenMessageIds.putIfAbsent(networkMessage.id, networkMessage to Clock.System.now()) == null

    fun clear() = seenMessageIds.clear()

    private val MESSAGE_CACHE_EXPIRY = 5.minutes

}