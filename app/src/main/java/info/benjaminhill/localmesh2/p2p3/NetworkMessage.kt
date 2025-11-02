package info.benjaminhill.localmesh2.p2p3

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
@Serializable
data class NetworkMessage(
    val id:String = UUID.randomUUID().toString(),
    // Always include "the journey this message took to get to you". Append yourself when you forward it onwawrds.
    // Id to Instant (string)
    val breadCrumbs:List<Pair<String, String>>,
    // Optional command to change the display target.
    val displayTarget: String?,

) {
    fun toByteArray(): ByteArray = Cbor.Default.encodeToByteArray(serializer(), this)

    companion object {
        fun fromByteArray(byteArray: ByteArray): NetworkMessage =
            Cbor.Default.decodeFromByteArray(serializer(), byteArray)
    }
}