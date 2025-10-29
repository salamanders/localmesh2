package info.benjaminhill.localmesh2

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NetworkMessage(
    /** Per-Type optional fields */
    val displayTarget: String,
) {
    fun toByteArray(): ByteArray = Cbor.encodeToByteArray(serializer(), this)

    companion object {
        fun fromByteArray(byteArray: ByteArray): NetworkMessage =
            Cbor.decodeFromByteArray(serializer(), byteArray)
    }
}