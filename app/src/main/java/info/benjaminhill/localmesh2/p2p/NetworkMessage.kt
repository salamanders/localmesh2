package info.benjaminhill.localmesh2.p2p

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NetworkMessage(
    /** Per-Type optional fields */
    val displayTarget: String,
) {
    fun toByteArray(): ByteArray = Cbor.Default.encodeToByteArray(serializer(), this)

    companion object {
        fun fromByteArray(byteArray: ByteArray): NetworkMessage =
            Cbor.Default.decodeFromByteArray(serializer(), byteArray)
    }
}