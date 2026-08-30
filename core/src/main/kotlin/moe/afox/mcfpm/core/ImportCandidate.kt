package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.ArtifactSource
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.Dependency
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.SpdxLicense
import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
public data class ImportCandidateSource(
    public val kind: String,
    public val requestUrl: String,
    public val finalUrl: String,
    public val rawSha256: String,
    public val rawSize: Long,
    public val immutableVersion: String,
    public val selectionPath: String,
    public val selectedRoot: String? = null,
    public val nestedZip: String? = null,
    public val upstreamId: String? = null,
    public val revision: String? = null,
    public val releaseId: Long? = null,
    public val assetId: Long? = null,
    public val assetName: String? = null,
)

@Serializable
public data class ImportCandidatePayload(
    public val type: PayloadType,
    public val classifier: String,
    public val normalizedSha256: String,
    public val normalizedSize: Long,
)

@Serializable
public data class ImportCandidateDocument(
    public val schema: Int = SCHEMA_VERSION,
    public val source: ImportCandidateSource,
    public val packageId: PackageId,
    public val version: SemVer,
    public val license: String,
    public val minecraft: String? = null,
    public val dependencies: List<Dependency> = emptyList(),
    public val payload: ImportCandidatePayload,
) {
    init {
        require(schema == SCHEMA_VERSION) { "Unsupported import candidate schema: $schema" }
        require(license.isNotBlank()) { "Import candidate license is required" }
    }

    public companion object {
        public const val SCHEMA_VERSION: Int = 1
    }
}

public data class FrozenImportCandidate(
    public val document: ImportCandidateDocument,
    public val payload: ByteArray,
) {
    public fun manifest(): PackageManifest {
        val source = document.source
        val payload = document.payload
        return PackageManifest(
            packageId = document.packageId,
            version = document.version,
            license = document.license,
            minecraft = document.minecraft,
            dependencies = document.dependencies,
            artifacts = listOf(
                ArtifactDescriptor(
                    type = payload.type,
                    classifier = payload.classifier,
                    extension = "zip",
                    sha256 = payload.normalizedSha256,
                    size = payload.normalizedSize,
                    source = ArtifactSource(
                        kind = source.kind,
                        uri = source.finalUrl,
                        immutableVersion = source.immutableVersion,
                        redistributionLicense = document.license,
                        upstreamId = source.upstreamId,
                        revision = source.revision,
                        path = source.selectionPath,
                        sha256 = source.rawSha256,
                        size = source.rawSize,
                    ),
                ),
            ),
        ).normalized()
    }
}

/** Deterministic, self-contained import hand-off between audit and publish jobs. */
public object ImportCandidateCodec {
    public fun encode(candidate: FrozenImportCandidate): ByteArray {
        val normalizedCandidate = candidate.copy(document = normalized(candidate.document))
        validate(normalizedCandidate)
        val metadata = CanonicalJson.encode(ImportCandidateDocument.serializer(), normalizedCandidate.document)
        return ReproducibleZip.fromEntries(
            listOf(
                "candidate.json" to metadata,
                "payload.zip" to normalizedCandidate.payload,
            ),
        )
    }

    public fun decode(bytes: ByteArray): FrozenImportCandidate {
        require(ReproducibleZip.normalize(bytes).contentEquals(bytes)) {
            "Import candidate archive is not a deterministic ZIP"
        }
        val entries = ReproducibleZip.readEntries(bytes).entries
        require(entries.map { it.first } == listOf("candidate.json", "payload.zip")) {
            "Import candidate must contain exactly candidate.json and payload.zip in canonical order"
        }
        val metadataBytes = entries.first().second
        val document = CanonicalJson.decode(ImportCandidateDocument.serializer(), metadataBytes)
        require(
            CanonicalJson.encode(ImportCandidateDocument.serializer(), normalized(document)).contentEquals(metadataBytes),
        ) { "Import candidate metadata is not canonical JSON" }
        val candidate = FrozenImportCandidate(document, entries[1].second)
        validate(candidate)
        return candidate
    }

    public fun validate(candidate: FrozenImportCandidate) {
        val document = normalized(candidate.document)
        require(document == candidate.document) { "Import candidate metadata is not normalized" }
        val source = document.source
        require(source.kind in SOURCE_KINDS) { "Unsupported import candidate source kind: ${source.kind}" }
        requireHttps(source.requestUrl, "request URL")
        requireHttps(source.finalUrl, "final URL")
        require(SHA256.matches(source.rawSha256)) { "Import candidate raw SHA-256 is invalid" }
        require(source.rawSize >= 0) { "Import candidate raw size must not be negative" }
        require(source.immutableVersion.isNotBlank()) { "Import candidate immutable version is required" }
        require(source.selectionPath.isNotBlank()) { "Import candidate selection path is required" }
        validateSelection(source)
        require(source.selectedRoot == null || source.selectedRoot.isNotBlank()) {
            "Import candidate selected root must not be blank"
        }
        when (source.kind) {
            "url" -> require(source.immutableVersion == "sha256:${source.rawSha256}") {
                "URL import immutable version must bind to the raw SHA-256"
            }
            "github-release-asset" -> {
                require(source.revision?.matches(COMMIT) == true) { "GitHub release commit must be a full lowercase SHA" }
                require(source.releaseId != null && source.releaseId > 0) { "GitHub release ID is required" }
                require(source.assetId != null && source.assetId > 0) { "GitHub asset ID is required" }
                require(!source.assetName.isNullOrBlank() && '/' !in source.assetName && '\\' !in source.assetName) {
                    "GitHub asset name is invalid"
                }
                require(source.immutableVersion == "release:${source.releaseId}/asset:${source.assetId}") {
                    "GitHub release immutable version is invalid"
                }
            }
            "github-archive" -> {
                require(source.revision?.matches(COMMIT) == true) { "GitHub archive revision must be a full lowercase SHA" }
                require(source.immutableVersion == source.revision) {
                    "GitHub archive immutable version must bind to its commit"
                }
                require(source.releaseId == null && source.assetId == null && source.assetName == null) {
                    "GitHub archive cannot contain release asset metadata"
                }
            }
        }
        SpdxLicense.requireIdentifier(document.license)
        require(!document.version.toString().endsWith("-SNAPSHOT", ignoreCase = true)) {
            "Import candidate version must be an immutable release SemVer"
        }

        val payload = document.payload
        require(SHA256.matches(payload.normalizedSha256)) { "Import candidate payload SHA-256 is invalid" }
        require(payload.normalizedSize >= 0) { "Import candidate payload size must not be negative" }
        require(candidate.payload.size.toLong() == payload.normalizedSize) {
            "Frozen payload size does not match candidate metadata"
        }
        require(Hashing.sha256(candidate.payload) == payload.normalizedSha256) {
            "Frozen payload SHA-256 does not match candidate metadata"
        }
        val verified = ReproducibleZip.verify(candidate.payload, requirePackMetadata = true)
        val hasData = verified.entries.any { it == "data" || it.startsWith("data/") }
        val hasAssets = verified.entries.any { it == "assets" || it.startsWith("assets/") }
        require(hasData.xor(hasAssets)) { "Frozen payload must contain either data/ or assets/, but not both" }
        val expectedType = if (hasData) PayloadType.MINECRAFT_DATAPACK else PayloadType.MINECRAFT_RESOURCEPACK
        require(payload.type == expectedType) {
            "Frozen payload type ${payload.type} does not match its contents ($expectedType)"
        }
        require(ReproducibleZip.normalize(candidate.payload, requirePackMetadata = true).contentEquals(candidate.payload)) {
            "Frozen payload.zip is not normalized"
        }
        FrozenImportCandidate(document, candidate.payload).manifest()
    }

    private fun normalized(document: ImportCandidateDocument): ImportCandidateDocument {
        val manifest = runCatching {
            PackageManifest(
                packageId = document.packageId,
                version = document.version,
                license = document.license,
                minecraft = document.minecraft,
                dependencies = document.dependencies,
            ).normalized()
        }.getOrElse { return document }
        return document.copy(
            dependencies = manifest.dependencies,
            payload = document.payload.copy(
                normalizedSha256 = document.payload.normalizedSha256.lowercase(),
            ),
        )
    }

    private fun validateSelector(value: String) {
        require(value.replace('\\', '/').split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Unsafe import candidate selector: $value"
        }
        require(!value.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(value)) {
            "Absolute import candidate selector is not permitted: $value"
        }
    }

    private fun validateSelection(source: ImportCandidateSource) {
        source.selectedRoot?.let(::validateSelector)
        source.nestedZip?.let(::validateSelector)
        val expected = when (val nestedZip = source.nestedZip) {
            null -> source.selectedRoot ?: "/"
            else -> "$nestedZip!/${source.selectedRoot.orEmpty()}"
        }
        require(source.selectionPath == expected) {
            "Import candidate selection path does not match its selectors"
        }
    }

    private fun requireHttps(value: String, label: String) {
        val uri = runCatching { URI.create(value) }.getOrElse {
            throw IllegalArgumentException("Import candidate $label is not a valid URI")
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Import candidate $label must be a public HTTPS URL"
        }
    }

    private val SOURCE_KINDS: Set<String> = setOf("github-release-asset", "github-archive", "url")
    private val SHA256: Regex = Regex("[0-9a-f]{64}")
    private val COMMIT: Regex = Regex("[0-9a-f]{40}")
}
