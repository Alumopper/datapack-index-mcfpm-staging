package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PayloadRef
import moe.afox.mcfpm.model.ResolvedGraph
import java.nio.file.Path

public class ArtifactGraphFetcher(
    private val repositories: RepositoryRegistry,
    private val cache: ContentAddressedCache,
) {
    public fun fetch(graph: ResolvedGraph, offline: Boolean = false): McfpmResult<FetchedGraph> {
        return try {
            fetchUnchecked(graph, offline)
        } catch (exception: Exception) {
            McfpmResult.Failure(
                listOf(
                    Diagnostic(
                        moe.afox.mcfpm.model.DiagnosticCode.NETWORK_FAILURE,
                        moe.afox.mcfpm.model.DiagnosticSeverity.ERROR,
                        "Unable to fetch artifact graph: ${exception.message}",
                    ),
                ),
            )
        }
    }

    private fun fetchUnchecked(graph: ResolvedGraph, offline: Boolean): McfpmResult<FetchedGraph> {
        val artifacts = linkedMapOf<PayloadRef, Path>()
        val diagnostics = mutableListOf<Diagnostic>()
        graph.packages.sortedBy { it.packageId }.forEach { resolvedPackage ->
            when (val sourceValidation = repositories.validateLockedSource(
                resolvedPackage.packageId,
                resolvedPackage.repositoryUrl,
            )) {
                is McfpmResult.Success -> Unit
                is McfpmResult.Failure -> {
                    diagnostics += sourceValidation.diagnostics
                    return@forEach
                }
            }
            val repository = when (val selected = repositories.repositoryForSource(
                resolvedPackage.packageId,
                resolvedPackage.repositoryUrl,
            )) {
                is McfpmResult.Success -> selected.value
                is McfpmResult.Failure -> {
                    diagnostics += selected.diagnostics
                    return@forEach
                }
            }
            resolvedPackage.artifacts.forEach { artifact ->
                // A content-addressed offline lookup does not need a remote filename. This also
                // avoids consulting timestamped Maven snapshot metadata in a fresh offline process.
                val uri = if (offline) {
                    repository.baseUri
                } else {
                    repository.artifactUri(resolvedPackage.packageId, resolvedPackage.version, artifact)
                }
                when (val cached = cache.fetch(
                    uri,
                    artifact.sha256,
                    artifact.size,
                    offline,
                    if (offline) emptyMap() else repository.requestHeaders(uri),
                )) {
                    is McfpmResult.Success -> artifacts[
                        PayloadRef(resolvedPackage.packageId, artifact.type, artifact.classifier)
                    ] = cached.value.path
                    is McfpmResult.Failure -> diagnostics += cached.diagnostics
                }
            }
        }
        return if (diagnostics.isEmpty()) {
            McfpmResult.Success(FetchedGraph(graph, artifacts))
        } else {
            McfpmResult.Failure(diagnostics)
        }
    }
}
