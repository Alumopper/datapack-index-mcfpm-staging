package moe.afox.mcfpm.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@JvmInline
@Serializable(with = PackageIdSerializer::class)
public value class PackageId private constructor(public val value: String) : Comparable<PackageId> {
    public val group: String
        get() = value.substringBefore(':')

    public val name: String
        get() = value.substringAfter(':')

    override fun compareTo(other: PackageId): Int = value.compareTo(other.value)

    override fun toString(): String = value

    public companion object {
        private val pattern = Regex(
            "[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?:[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?",
        )

        public fun parse(value: String): PackageId {
            require(pattern.matches(value)) {
                "Package ID must be lowercase group:name using letters, digits, '.', '_', or '-': $value"
            }
            return PackageId(value)
        }

        public fun parseOrNull(value: String): PackageId? =
            runCatching { parse(value) }.getOrNull()
    }
}

public object PackageIdSerializer : KSerializer<PackageId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("moe.afox.mcfpm.model.PackageId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PackageId): Unit =
        encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): PackageId =
        PackageId.parse(decoder.decodeString())
}
