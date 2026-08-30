package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadRef
import java.nio.file.Files
import kotlinx.serialization.Serializable

@Serializable
public data class BundleEntry(
    public val packageId: PackageId,
    public val type: String,
    public val classifier: String,
    public val file: String,
    public val sha256: String,
    public val size: Long,
    public val loadIndex: Int,
)

@Serializable
public data class BundleManifest(
    public val schema: Int = 1,
    public val entries: List<BundleEntry>,
)

public data class BundleOutput(
    public val files: Map<String, ByteArray>,
    public val manifest: ByteArray,
    public val diagnostics: List<Diagnostic>,
)

public object BundleBuilder {
    public fun build(fetched: FetchedGraph): McfpmResult<BundleOutput> {
        val packages = fetched.graph.packages.associateBy { it.packageId }
        val files = linkedMapOf<String, ByteArray>()
        val entries = mutableListOf<BundleEntry>()
        val diagnostics = mutableListOf<Diagnostic>()
        val resourceOwners = mutableMapOf<String, PayloadRef>()

        fetched.graph.loadOrder.forEachIndexed { index, reference ->
            val resolvedPackage = packages[reference.packageId]
                ?: return failure("Load order references an unknown package: ${reference.packageId}")
            val artifact = resolvedPackage.artifacts.firstOrNull {
                it.type == reference.type && it.classifier == reference.classifier
            } ?: return failure("Load order references an unknown artifact: $reference")
            val path = fetched.artifacts[reference]
                ?: return failure("Fetched graph is missing an artifact: $reference")
            val bytes = runCatching { Files.readAllBytes(path) }.getOrElse { cause ->
                return failure("Unable to read fetched artifact $reference: ${cause.message}")
            }
            if (bytes.size.toLong() != artifact.size || Hashing.sha256(bytes) != artifact.sha256) {
                return failure("Fetched artifact changed before bundling: $reference")
            }
            val fileName = buildFileName(reference, resolvedPackage.version.toString(), artifact.extension)
            if (files.put(fileName, bytes) != null) return failure("Bundle file name collision: $fileName")
            entries += BundleEntry(
                packageId = reference.packageId,
                type = reference.type.value,
                classifier = reference.classifier,
                file = fileName,
                sha256 = artifact.sha256,
                size = artifact.size,
                loadIndex = index,
            )
            if (artifact.extension == "zip") {
                runCatching { ReproducibleZip.verify(bytes) }.getOrNull()?.entries?.forEach { resourcePath ->
                    val previous = resourceOwners.putIfAbsent(resourcePath, reference)
                    if (previous != null && previous != reference) {
                        diagnostics += Diagnostic(
                            DiagnosticCode.INTEGRITY_FAILURE,
                            DiagnosticSeverity.WARNING,
                            "Bundle resource path overlaps: $resourcePath",
                            mapOf("first" to previous.toString(), "second" to reference.toString()),
                        )
                    }
                }
            }
        }
        val manifest = CanonicalJson.encode(BundleManifest.serializer(), BundleManifest(entries = entries))
        return McfpmResult.Success(BundleOutput(files, manifest, diagnostics))
    }

    private fun buildFileName(reference: PayloadRef, version: String, extension: String): String {
        val group = reference.packageId.group.replace('.', '-').replace('_', '-').replace("--", "-")
        return "$group-${reference.packageId.name}-$version-${reference.classifier}.$extension"
    }

    private fun failure(message: String): McfpmResult.Failure = McfpmResult.Failure(
        listOf(Diagnostic(DiagnosticCode.INTEGRITY_FAILURE, DiagnosticSeverity.ERROR, message)),
    )
}
