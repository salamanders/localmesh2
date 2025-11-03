package info.benjaminhill.localmesh2.p2p

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Custom serializer for Instant
@OptIn(ExperimentalTime::class)
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilliseconds())
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.fromEpochMilliseconds(decoder.decodeLong())
    }
}

@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
@Serializable
data class NetworkMessage(
    val id: String = UUID.randomUUID().toString(),
    // Always include "the journey this message took to get to you". Append yourself when you forward it onwawrds.
    // Id to Instant
    val breadCrumbs: List<Pair<String, @Serializable(with = InstantSerializer::class) Instant>>,
    // Optional command to change the display target.
    val displayTarget: String?,
) {
    fun toByteArray(): ByteArray = Cbor.Default.encodeToByteArray(serializer(), this)

    companion object {
        fun fromByteArray(byteArray: ByteArray): NetworkMessage =
            Cbor.Default.decodeFromByteArray(serializer(), byteArray)
    }
}