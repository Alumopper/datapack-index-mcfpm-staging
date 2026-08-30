package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.ArtifactSource
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PayloadRequirement
import moe.afox.mcfpm.model.PayloadType
import java.net.URI
import java.nio.file.Files

public enum class ExternalSourceKind(public val wireName: String) {
    GITHUB("github"),
    MODRINTH("modrinth"),
    CURSEFORGE("curseforge"),
    URL("url"),
}

public data class ExternalArtifactSpec(
    public val canonicalId: String,
    public val sourceKind: ExternalSourceKind,
    public val uri: URI,
    public val immutableVersion: String,
    public val expectedSha256: String,
    public val expectedSize: Long,
    public val redistributionLicense: String,
    public val type: PayloadType,
    public val classifier: String,
    public val requires: List<PayloadRequirement> = emptyList(),
) {
    init {
        require(CANONICAL_ID.matches(canonicalId)) { "Invalid external canonical ID: $canonicalId" }
        require(immutableVersion.isNotBlank()) { "External artifact requires an immutable version" }
        require(redistributionLicense.isNotBlank()) { "External artifact requires redistribution license metadata" }
    }

    private companion object {
        val CANONICAL_ID: Regex = Regex("[a-z0-9][a-z0-9._-]*(?::[a-z0-9][a-z0-9._/-]*)+")
    }
}

public data class PackedExternalArtifact(
    public val canonicalId: String,
    public val descriptor: ArtifactDescriptor,
    public val bytes: ByteArray,
)

public class ExternalArtifactIngester(
    private val cache: ContentAddressedCache,
) {
    public fun ingest(specifications: List<ExternalArtifactSpec>): McfpmResult<List<PackedExternalArtifact>> {
        val diagnostics = mutableListOf<Diagnostic>()
        val byCanonicalId = linkedMapOf<String, PackedExternalArtifact>()
        val sourceByCanonicalId = mutableMapOf<String, ExternalArtifactSpec>()
        specifications.forEach { specification ->
            val existing = byCanonicalId[specification.canonicalId]
            if (existing != null) {
                val previous = sourceByCanonicalId.getValue(specification.canonicalId)
                if (previous.expectedSha256 != specification.expectedSha256 ||
                    previous.expectedSize != specification.expectedSize ||
                    previous.immutableVersion != specification.immutableVersion ||
                    previous.uri != specification.uri
                ) {
                    diagnostics += error(
                        "External canonical ID ${specification.canonicalId} resolves to conflicting content",
                        mapOf("canonicalId" to specification.canonicalId),
                    )
                }
                return@forEach
            }
            when (val fetched = cache.fetch(
                specification.uri,
                specification.expectedSha256,
                specification.expectedSize,
            )) {
                is McfpmResult.Failure -> diagnostics += fetched.diagnostics
                is McfpmResult.Success -> {
                    val raw = Files.readAllBytes(fetched.value.path)
                    val requiresPackMetadata = specification.type == PayloadType.MINECRAFT_DATAPACK ||
                        specification.type == PayloadType.MINECRAFT_RESOURCEPACK
                    val normalized = runCatching { ReproducibleZip.normalize(raw, requiresPackMetadata) }
                        .getOrElse { cause ->
                            diagnostics += error(
                                "Unable to normalize external artifact: ${cause.message}",
                                mapOf("canonicalId" to specification.canonicalId),
                            )
                            return@forEach
                        }
                    val source = ArtifactSource(
                        kind = specification.sourceKind.wireName,
                        uri = specification.uri.toString(),
                        immutableVersion = specification.immutableVersion,
                        redistributionLicense = specification.redistributionLicense,
                    )
                    byCanonicalId[specification.canonicalId] = PackedExternalArtifact(
                        canonicalId = specification.canonicalId,
                        descriptor = ArtifactDescriptor(
                            type = specification.type,
                            classifier = specification.classifier,
                            extension = "zip",
                            sha256 = Hashing.sha256(normalized),
                            size = normalized.size.toLong(),
                            requires = specification.requires,
                            source = source,
                        ),
                        bytes = normalized,
                    )
                    sourceByCanonicalId[specification.canonicalId] = specification
                }
            }
        }
        return if (diagnostics.isEmpty()) {
            McfpmResult.Success(byCanonicalId.values.toList())
        } else {
            McfpmResult.Failure(diagnostics)
        }
    }

    private fun error(message: String, context: Map<String, String>): Diagnostic =
        Diagnostic(DiagnosticCode.INTEGRITY_FAILURE, DiagnosticSeverity.ERROR, message, context)
}
