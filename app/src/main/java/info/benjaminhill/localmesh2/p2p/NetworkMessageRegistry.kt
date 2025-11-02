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

@OptIn(ExperimentalTime::class)
object NetworkMessageRegistry {
    /** Map of MessageUuids to the timestamp we first saw them. Used for de-duplication. */
    private val seenMessageIds = mutableMapOf<String, Pair<NetworkMessage, Instant>>()

    private val pruneMessageCache = CoroutineScope(Job() + Dispatchers.IO).launch {
        while (isActive) {
            val ago = Clock.System.now().minus(MESSAGE_CACHE_EXPIRY)
            seenMessageIds.entries.removeIf { it.value.second < ago }
            delay(10.seconds)
        }
    }

    fun isFirstSeen(networkMessage: NetworkMessage): Boolean =
        seenMessageIds.putIfAbsent(networkMessage.id, networkMessage to Clock.System.now()) == null

    fun clear() = seenMessageIds.clear()

    private val MESSAGE_CACHE_EXPIRY = 5.minutes

}