package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadRef
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.ResolvedGraph
import java.nio.file.Files
import java.nio.file.Path

public data class FetchedGraph(
    public val graph: ResolvedGraph,
    public val artifacts: Map<PayloadRef, Path>,
)

public class ArtifactVerifier(
    private val trustStore: TrustStore,
) {
    public fun verify(fetched: FetchedGraph): McfpmResult<FetchedGraph> {
        return try {
            verifyUnchecked(fetched)
        } catch (exception: Exception) {
            McfpmResult.Failure(
                listOf(error(DiagnosticCode.INTEGRITY_FAILURE, "Unable to verify artifact graph: ${exception.message}")),
            )
        }
    }

    private fun verifyUnchecked(fetched: FetchedGraph): McfpmResult<FetchedGraph> {
        val diagnostics = mutableListOf<Diagnostic>()
        val packages = fetched.graph.packages.associateBy { it.packageId }
        fetched.graph.packages.forEach { resolvedPackage ->
            resolvedPackage.artifacts.forEach { artifact ->
                val reference = PayloadRef(resolvedPackage.packageId, artifact.type, artifact.classifier)
                val path = fetched.artifacts[reference]
                if (path == null || !Files.isRegularFile(path)) {
                    diagnostics += error(
                        DiagnosticCode.INTEGRITY_FAILURE,
                        "Fetched graph is missing ${resolvedPackage.packageId}:${artifact.classifier}",
                    )
                    return@forEach
                }
                if (Files.size(path) != artifact.size || Hashing.sha256(path) != artifact.sha256) {
                    diagnostics += error(
                        DiagnosticCode.INTEGRITY_FAILURE,
                        "Artifact checksum or size does not match the descriptor",
                        mapOf("package" to resolvedPackage.packageId.value, "classifier" to artifact.classifier),
                    )
                    return@forEach
                }
                if (artifact.extension == "zip") {
                    val requiresPackMetadata = artifact.type == PayloadType.MINECRAFT_DATAPACK ||
                        artifact.type == PayloadType.MINECRAFT_RESOURCEPACK
                    runCatching { ReproducibleZip.verify(Files.readAllBytes(path), requiresPackMetadata) }
                        .onFailure { cause ->
                            diagnostics += error(
                                DiagnosticCode.INTEGRITY_FAILURE,
                                "Unsafe or invalid ZIP payload: ${cause.message}",
                                mapOf("package" to resolvedPackage.packageId.value, "classifier" to artifact.classifier),
                            )
                        }
                }
                if (artifact.executable) {
                    val fingerprint = packages[resolvedPackage.packageId]?.signatureFingerprint
                    if (fingerprint == null || !trustStore.isTrusted(resolvedPackage.packageId, fingerprint)) {
                        diagnostics += error(
                            DiagnosticCode.UNTRUSTED_EXECUTABLE,
                            "Executable payload is not explicitly trusted",
                            mapOf("package" to resolvedPackage.packageId.value, "classifier" to artifact.classifier),
                        )
                    }
                }
            }
        }
        return if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            McfpmResult.Failure(diagnostics)
        } else {
            McfpmResult.Success(fetched)
        }
    }

    private fun error(
        code: DiagnosticCode,
        message: String,
        context: Map<String, String> = emptyMap(),
    ): Diagnostic = Diagnostic(code, DiagnosticSeverity.ERROR, message, context)
}
