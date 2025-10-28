package info.benjaminhill.localmesh2

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import java.util.UUID

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NetworkMessage(
    /** A unique identifier for this message to prevent broadcast loops. */
    val messageId: String = UUID.randomUUID().toString(),
    /** The node that initially injected this message into the network */
    val sendingNodeId: String,
    /** Type of command (gossip, display, do, etc) */
    val messageType: Types = Types.GOSSIP,

    // Network Topology
    /** The number of hops this message has taken. */
    val hopCount: Int = 0,
    /** Immediate peers of the originator */
    val peers: Set<String>,
    /** Key: endpointId, value: distance */
    val distance: Map<String, Int>,
    /** Per-Type optional fields */
    val displayTarget: String? = null,
) {
    fun toByteArray(): ByteArray = Cbor.encodeToByteArray(serializer(), this)

    companion object {
        enum class Types {
            GOSSIP,
            DISPLAY,
        }

        fun fromByteArray(byteArray: ByteArray): NetworkMessage =
            Cbor.decodeFromByteArray(serializer(), byteArray)
    }
}