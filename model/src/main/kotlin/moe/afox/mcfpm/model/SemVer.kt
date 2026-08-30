package moe.afox.mcfpm.model

import java.math.BigInteger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = SemVerSerializer::class)
@ConsistentCopyVisibility
public data class SemVer private constructor(
    public val major: BigInteger,
    public val minor: BigInteger,
    public val patch: BigInteger,
    public val prerelease: List<String>,
    public val buildMetadata: List<String>,
) : Comparable<SemVer> {
    public val isPrerelease: Boolean
        get() = prerelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }

        if (prerelease.isEmpty() && other.prerelease.isNotEmpty()) return 1
        if (prerelease.isNotEmpty() && other.prerelease.isEmpty()) return -1

        prerelease.zip(other.prerelease).forEach { (left, right) ->
            comparePrereleaseIdentifiers(left, right).takeIf { it != 0 }?.let { return it }
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun toString(): String = buildString {
        append(major)
        append('.')
        append(minor)
        append('.')
        append(patch)
        if (prerelease.isNotEmpty()) {
            append('-')
            append(prerelease.joinToString("."))
        }
        if (buildMetadata.isNotEmpty()) {
            append('+')
            append(buildMetadata.joinToString("."))
        }
    }

    public fun withoutBuildMetadata(): SemVer =
        if (buildMetadata.isEmpty()) this else copy(buildMetadata = emptyList())

    public companion object {
        private val pattern = Regex(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-((?:0|[1-9A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9A-Za-z-][0-9A-Za-z-]*))*))?" +
                "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?",
        )

        public fun parse(value: String): SemVer {
            val match = pattern.matchEntire(value)
                ?: throw IllegalArgumentException("Version is not valid SemVer 2.0: $value")
            val prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                .orEmpty()
            require(prerelease.none { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) {
                "Numeric prerelease identifiers must not contain leading zeroes: $value"
            }
            return SemVer(
                major = match.groupValues[1].toBigInteger(),
                minor = match.groupValues[2].toBigInteger(),
                patch = match.groupValues[3].toBigInteger(),
                prerelease = prerelease,
                buildMetadata = match.groupValues[5]
                    .takeIf(String::isNotEmpty)
                    ?.split('.')
                    .orEmpty(),
            )
        }

        public fun parseOrNull(value: String): SemVer? =
            runCatching { parse(value) }.getOrNull()

        internal fun of(
            major: BigInteger,
            minor: BigInteger,
            patch: BigInteger,
        ): SemVer = SemVer(major, minor, patch, emptyList(), emptyList())

        private fun comparePrereleaseIdentifiers(left: String, right: String): Int {
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            return when {
                leftNumeric && rightNumeric -> {
                    left.length.compareTo(right.length).takeIf { it != 0 }
                        ?: left.compareTo(right)
                }
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
        }
    }
}

public object SemVerSerializer : KSerializer<SemVer> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("moe.afox.mcfpm.model.SemVer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SemVer): Unit =
        encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): SemVer =
        SemVer.parse(decoder.decodeString())
}
