package moe.afox.mcfpm.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@JvmInline
@Serializable(with = PayloadTypeSerializer::class)
public value class PayloadType private constructor(public val value: String) : Comparable<PayloadType> {
    override fun compareTo(other: PayloadType): Int = value.compareTo(other.value)

    override fun toString(): String = value

    public companion object {
        public val MINECRAFT_DATAPACK: PayloadType = PayloadType("minecraft.datapack")
        public val MINECRAFT_RESOURCEPACK: PayloadType = PayloadType("minecraft.resourcepack")
        public val MCFPP_LIBRARY: PayloadType = PayloadType("compiler.mcfpp.library")
        public val JVM_PLUGIN: PayloadType = PayloadType("jvm.plugin")

        private val pattern = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+")

        public fun parse(value: String): PayloadType {
            require(pattern.matches(value)) {
                "Payload type must be a lowercase dotted reverse-domain name: $value"
            }
            return PayloadType(value)
        }
    }
}

public object PayloadTypeSerializer : KSerializer<PayloadType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("moe.afox.mcfpm.model.PayloadType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PayloadType): Unit =
        encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): PayloadType =
        PayloadType.parse(decoder.decodeString())
}

@Serializable
public data class PayloadRef(
    public val packageId: PackageId,
    public val type: PayloadType,
    public val classifier: String,
)

@Serializable
public data class PayloadRequirement(
    public val type: PayloadType,
    public val classifier: String,
)

@Serializable
public data class ArtifactSource(
    public val kind: String,
    public val uri: String,
    public val immutableVersion: String,
    public val redistributionLicense: String,
    public val upstreamId: String? = null,
    public val revision: String? = null,
    public val path: String? = null,
    public val sha256: String? = null,
    public val size: Long? = null,
) {
    init {
        require(kind.isNotBlank()) { "Artifact source kind is required" }
        require(uri.isNotBlank()) { "Artifact source URI is required" }
        require(immutableVersion.isNotBlank()) { "Artifact source immutable version is required" }
        require(redistributionLicense.isNotBlank()) { "Artifact source redistribution license is required" }
        require(upstreamId == null || upstreamId.isNotBlank()) { "Artifact source upstream ID must not be blank" }
        require(revision == null || revision.isNotBlank()) { "Artifact source revision must not be blank" }
        require(path == null || path.isNotBlank()) { "Artifact source path must not be blank" }
        require(sha256 == null || Regex("[0-9a-f]{64}").matches(sha256)) {
            "Artifact source SHA-256 must be 64 lowercase hexadecimal characters"
        }
        require(size == null || size >= 0) { "Artifact source size must not be negative" }
    }
}

@Serializable
public data class ArtifactDescriptor(
    public val type: PayloadType,
    public val classifier: String,
    public val extension: String = "zip",
    public val sha256: String,
    public val size: Long,
    public val minecraft: String? = null,
    public val compiler: String? = null,
    public val executable: Boolean = false,
    public val requires: List<PayloadRequirement> = emptyList(),
    public val source: ArtifactSource? = null,
) {
    init {
        require(classifierPattern.matches(classifier)) { "Invalid Maven classifier: $classifier" }
        require(extensionPattern.matches(extension)) { "Invalid artifact extension: $extension" }
        require(sha256Pattern.matches(sha256)) { "SHA-256 must be 64 lowercase hexadecimal characters" }
        require(size >= 0) { "Artifact size must not be negative" }
        if (source != null) {
            require(source.immutableVersion.isNotBlank()) { "External sources require an immutable version" }
            require(source.redistributionLicense.isNotBlank()) { "External sources require redistribution license metadata" }
        }
    }

    public companion object {
        private val classifierPattern = Regex("[a-z0-9][a-z0-9._-]*")
        private val extensionPattern = Regex("[a-z0-9][a-z0-9._-]*")
        private val sha256Pattern = Regex("[0-9a-f]{64}")
    }
}

@Serializable
public data class Dependency(
    public val packageId: PackageId,
    public val requirement: VersionRequirement,
    public val features: List<String> = emptyList(),
    public val optional: Boolean = false,
)

@Serializable
public data class FeatureDefinition(
    public val name: String,
    public val enablesDependencies: List<PackageId> = emptyList(),
)

@Serializable
public data class ToolConfiguration(
    public val consumerProfile: ConsumerProfile = ConsumerProfile.ALL,
    public val defaultRepository: String = "central",
    public val repositories: Map<String, String> = emptyMap(),
    public val bindings: Map<String, String> = emptyMap(),
    public val options: Map<String, String> = emptyMap(),
) {
    init {
        require(defaultRepository.isNotBlank()) { "Default repository ID is required" }
        require(repositories.keys.all(REPOSITORY_ID::matches)) { "Invalid tool repository ID" }
        require(bindings.keys.all(PACKAGE_GROUP::matches)) { "Invalid tool repository group binding" }
        require(repositories.values.none(String::isBlank)) { "Tool repository URIs must not be blank" }
        require(bindings.values.none(String::isBlank)) { "Tool repository bindings must not be blank" }
        require(options.keys.none(String::isBlank)) { "Tool option keys must not be blank" }
    }

    public fun normalized(): ToolConfiguration = copy(
        repositories = repositories.toSortedMap(),
        bindings = bindings.toSortedMap(),
        options = options.toSortedMap(),
    )

    private companion object {
        val REPOSITORY_ID: Regex = Regex("[a-z][a-z0-9._-]*")
        val PACKAGE_GROUP: Regex = Regex("[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?")
    }
}

@Serializable
public data class PackageManifest(
    public val schema: Int = SCHEMA_VERSION,
    public val packageId: PackageId,
    public val version: SemVer,
    public val license: String,
    public val minecraft: String? = null,
    public val compiler: String? = null,
    public val dependencies: List<Dependency> = emptyList(),
    public val features: List<FeatureDefinition> = emptyList(),
    public val artifacts: List<ArtifactDescriptor> = emptyList(),
    public val tool: ToolConfiguration = ToolConfiguration(),
    public val signatureFingerprint: String? = null,
    public val signatureAlgorithm: String? = null,
    public val signingPublicKey: String? = null,
    public val signature: String? = null,
) {
    init {
        require(schema == SCHEMA_VERSION) { "Unsupported package schema: $schema" }
        require(license.isNotBlank()) { "Package license is required" }
        require(dependencies.map(Dependency::packageId).distinct().size == dependencies.size) {
            "A package may declare each dependency only once"
        }
        require(features.map(FeatureDefinition::name).distinct().size == features.size) {
            "Feature names must be unique"
        }
        require(artifacts.map { it.type to it.classifier }.distinct().size == artifacts.size) {
            "Artifact type/classifier pairs must be unique"
        }
        val signatureMetadata = listOf(signatureFingerprint, signatureAlgorithm, signingPublicKey)
        require(signatureMetadata.all { it == null } || signatureMetadata.all { it != null }) {
            "Descriptor signature metadata fields must either all be present or all be absent"
        }
        require(signature == null || signatureMetadata.all { it != null }) {
            "A descriptor signature requires its algorithm, public key, and fingerprint"
        }
    }

    public fun normalized(): PackageManifest = copy(
        dependencies = dependencies
            .map { it.copy(features = it.features.distinct().sorted()) }
            .sortedBy { it.packageId },
        features = features
            .map { it.copy(enablesDependencies = it.enablesDependencies.distinct().sorted()) }
            .sortedBy(FeatureDefinition::name),
        artifacts = artifacts
            .map { artifact ->
                artifact.copy(
                    requires = artifact.requires
                        .distinct()
                        .sortedWith(compareBy({ it.type }, { it.classifier })),
                )
            }
            .sortedWith(compareBy({ it.type }, { it.classifier })),
        tool = tool.normalized(),
    )

    public companion object {
        public const val SCHEMA_VERSION: Int = 1
    }
}

@Serializable
public enum class ConsumerProfile {
    @SerialName("minecraft.datapack")
    MINECRAFT_DATAPACK,

    @SerialName("minecraft.resourcepack")
    MINECRAFT_RESOURCEPACK,

    @SerialName("compiler.mcfpp")
    MCFPP,

    @SerialName("jvm.plugin")
    JVM_PLUGIN,

    @SerialName("all")
    ALL,
}

@Serializable
public data class ResolvedPackage(
    public val packageId: PackageId,
    public val version: SemVer,
    public val repositoryUrl: String,
    public val descriptorSha256: String,
    public val selectedFeatures: List<String> = emptyList(),
    public val signatureFingerprint: String? = null,
    public val artifacts: List<ArtifactDescriptor> = emptyList(),
)

@Serializable
public data class ResolvedEdge(
    public val from: PackageId,
    public val to: PackageId,
    public val requirement: VersionRequirement,
)

@Serializable
public data class ResolvedGraph(
    public val resolverVersion: String,
    public val roots: List<PackageId>,
    public val packages: List<ResolvedPackage>,
    public val edges: List<ResolvedEdge>,
    public val loadOrder: List<PayloadRef>,
    public val diagnostics: List<Diagnostic> = emptyList(),
    public val minecraftVersion: String? = null,
) {
    public fun normalized(): ResolvedGraph = copy(
        roots = roots.distinct().sorted(),
        packages = packages
            .map {
                it.copy(
                    selectedFeatures = it.selectedFeatures.distinct().sorted(),
                    artifacts = it.artifacts.sortedWith(compareBy({ artifact -> artifact.type }, { artifact -> artifact.classifier })),
                )
            }
            .sortedBy(ResolvedPackage::packageId),
        edges = edges.sortedWith(compareBy({ it.from }, { it.to }, { it.requirement.expression })),
        diagnostics = diagnostics.sortedWith(
            compareBy({ it.code.stableCode }, { it.severity.name }, { it.message }, { it.context.toSortedMap().toString() }),
        ),
    )
}
