package moe.afox.mcfpm.model

import java.math.BigInteger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = VersionRequirementSerializer::class)
public class VersionRequirement private constructor(
    public val expression: String,
    private val comparators: List<Comparator>,
    public val explicitlyIncludesPrerelease: Boolean,
) {
    public fun matches(version: SemVer): Boolean {
        if (version.isPrerelease && !explicitlyIncludesPrerelease) return false
        return comparators.all { it.matches(version) }
    }

    public val exactVersion: SemVer?
        get() = comparators.singleOrNull()
            ?.takeIf { it.operator == Operator.EQUAL }
            ?.version

    override fun equals(other: Any?): Boolean =
        other is VersionRequirement && expression == other.expression

    override fun hashCode(): Int = expression.hashCode()

    override fun toString(): String = expression

    public companion object {
        private val comparatorPattern = Regex("(<=|>=|<|>|=)?(.+)")

        public fun parse(expression: String): VersionRequirement {
            val value = expression.trim()
            require(value.isNotEmpty()) { "A version requirement is required" }
            require(!value.equals("latest", ignoreCase = true)) { "'latest' is not a valid version requirement" }
            require(value.none { it == '[' || it == ']' || it == '(' || it == ')' || it == ',' }) {
                "Maven-style version ranges are not supported: $value"
            }

            val comparators = when {
                value.startsWith('^') -> caretComparators(SemVer.parse(value.drop(1)))
                value.startsWith('~') -> tildeComparators(SemVer.parse(value.drop(1)))
                else -> value.split(Regex("\\s+"))
                    .filter(String::isNotBlank)
                    .map(::parseComparator)
            }
            require(comparators.isNotEmpty()) { "A version requirement is required" }
            return VersionRequirement(
                expression = normalize(value, comparators),
                comparators = comparators,
                explicitlyIncludesPrerelease = comparators.any { it.version.isPrerelease },
            )
        }

        public fun exact(version: SemVer): VersionRequirement = parse(version.toString())

        private fun parseComparator(value: String): Comparator {
            val match = comparatorPattern.matchEntire(value)
                ?: throw IllegalArgumentException("Invalid version comparator: $value")
            val operator = when (match.groupValues[1]) {
                "", "=" -> Operator.EQUAL
                ">" -> Operator.GREATER
                ">=" -> Operator.GREATER_OR_EQUAL
                "<" -> Operator.LESS
                "<=" -> Operator.LESS_OR_EQUAL
                else -> error("Unreachable comparator")
            }
            return Comparator(operator, SemVer.parse(match.groupValues[2]))
        }

        private fun caretComparators(version: SemVer): List<Comparator> {
            val upperBound = when {
                version.major > BigInteger.ZERO -> SemVer.of(version.major + BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO)
                version.minor > BigInteger.ZERO -> SemVer.of(BigInteger.ZERO, version.minor + BigInteger.ONE, BigInteger.ZERO)
                else -> SemVer.of(BigInteger.ZERO, BigInteger.ZERO, version.patch + BigInteger.ONE)
            }
            return listOf(
                Comparator(Operator.GREATER_OR_EQUAL, version),
                Comparator(Operator.LESS, upperBound),
            )
        }

        private fun tildeComparators(version: SemVer): List<Comparator> = listOf(
            Comparator(Operator.GREATER_OR_EQUAL, version),
            Comparator(
                Operator.LESS,
                SemVer.of(version.major, version.minor + BigInteger.ONE, BigInteger.ZERO),
            ),
        )

        private fun normalize(value: String, comparators: List<Comparator>): String =
            when {
                value.startsWith('^') -> "^${comparators.first().version}"
                value.startsWith('~') -> "~${comparators.first().version}"
                comparators.size == 1 && comparators.single().operator == Operator.EQUAL ->
                    comparators.single().version.toString()
                else -> comparators.joinToString(" ")
            }
    }

    private data class Comparator(
        val operator: Operator,
        val version: SemVer,
    ) {
        fun matches(candidate: SemVer): Boolean {
            val comparison = candidate.compareTo(version)
            return when (operator) {
                Operator.EQUAL -> comparison == 0
                Operator.GREATER -> comparison > 0
                Operator.GREATER_OR_EQUAL -> comparison >= 0
                Operator.LESS -> comparison < 0
                Operator.LESS_OR_EQUAL -> comparison <= 0
            }
        }

        override fun toString(): String = "${operator.symbol}$version"
    }

    private enum class Operator(val symbol: String) {
        EQUAL("="),
        GREATER(">"),
        GREATER_OR_EQUAL(">="),
        LESS("<"),
        LESS_OR_EQUAL("<="),
    }
}

public object VersionRequirementSerializer : KSerializer<VersionRequirement> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("moe.afox.mcfpm.model.VersionRequirement", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: VersionRequirement): Unit =
        encoder.encodeString(value.expression)

    override fun deserialize(decoder: Decoder): VersionRequirement =
        VersionRequirement.parse(decoder.decodeString())
}
