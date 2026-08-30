package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.ResolvedGraph
import java.nio.file.Path

public data class ClientInstallRequest(
    public val fetched: FetchedGraph,
    public val context: InstallContext,
    public val explicitWorld: Path? = null,
    public val explicitInstance: Path? = null,
    public val confirmed: Boolean = false,
    public val dryRun: Boolean = false,
    public val force: Boolean = false,
)

public data class ClientInstallResult(
    public val context: InstallContext,
    public val world: Path?,
    public val instance: Path?,
    public val copiedTargets: List<Path>,
    public val removedTargets: List<Path>,
    public val orderFiles: List<Path>,
    public val evidence: List<String>,
    public val transactionId: String?,
    public val dryRun: Boolean,
)

public fun interface InstallExecutor {
    public fun install(request: ClientInstallRequest): McfpmResult<ClientInstallResult>
}

public interface McfpmClient {
    public fun resolve(request: ResolveRequest): McfpmResult<ResolvedGraph>
    public fun fetch(graph: ResolvedGraph, offline: Boolean = false): McfpmResult<FetchedGraph>
    public fun verify(fetched: FetchedGraph): McfpmResult<FetchedGraph>
    public fun bundle(fetched: FetchedGraph): McfpmResult<BundleOutput>
    public fun install(request: ClientInstallRequest): McfpmResult<ClientInstallResult>
}

public class DefaultMcfpmClient(
    private val resolver: PubGrubResolver,
    private val fetcher: ArtifactGraphFetcher,
    private val verifier: ArtifactVerifier,
    private val installer: InstallExecutor,
) : McfpmClient {
    override fun resolve(request: ResolveRequest): McfpmResult<ResolvedGraph> =
        resolver.resolve(request)

    override fun fetch(graph: ResolvedGraph, offline: Boolean): McfpmResult<FetchedGraph> =
        fetcher.fetch(graph, offline)

    override fun verify(fetched: FetchedGraph): McfpmResult<FetchedGraph> =
        verifier.verify(fetched)

    override fun bundle(fetched: FetchedGraph): McfpmResult<BundleOutput> =
        BundleBuilder.build(fetched)

    override fun install(request: ClientInstallRequest): McfpmResult<ClientInstallResult> =
        installer.install(request)
}
