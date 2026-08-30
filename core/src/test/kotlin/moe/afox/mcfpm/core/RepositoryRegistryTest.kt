package moe.afox.mcfpm.core

import java.net.URI
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.VersionRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun notFoundDiagnostic(): Diagnostic = Diagnostic(
    DiagnosticCode.NETWORK_FAILURE,
    DiagnosticSeverity.ERROR,
    "not found",
    mapOf("repository-failure-kind" to "not-found"),
)

class RepositoryRegistryTest {
    @Test
    fun `higher priority repository wins when both repositories contain a version`() {
        val id = PackageId.parse("example:pack")
        val version = SemVer.parse("1.0.0")
        val afox = StubRepository("afox", "https://afox.example/", mapOf(version to manifest(id, version)))
        val central = StubRepository("central", "https://central.example/", mapOf(version to manifest(id, version)))
        val registry = RepositoryRegistry(
            listOf(afox, central),
            defaultRepositoryId = "afox",
            defaultRepositoryIds = listOf("afox", "central"),
        )

        val candidates = assertIs<McfpmResult.Success<List<RepositoryCandidate>>>(
            registry.candidates(id, listOf(VersionRequirement.exact(version))),
        ).value
        assertEquals(listOf("afox"), candidates.map { it.repository.id })
    }

    @Test
    fun `not found continues to lower priority but operational failure stops`() {
        val id = PackageId.parse("example:pack")
        val version = SemVer.parse("1.0.0")
        val central = StubRepository("central", "https://central.example/", mapOf(version to manifest(id, version)))
        val notFound = StubRepository(
            "afox",
            "https://afox.example/",
            versionsResult = McfpmResult.Failure(listOf(notFoundDiagnostic())),
        )
        val fallbackRegistry = RepositoryRegistry(
            listOf(notFound, central),
            defaultRepositoryId = "afox",
            defaultRepositoryIds = listOf("afox", "central"),
        )
        assertEquals(
            "central",
            assertIs<McfpmResult.Success<RepositoryCandidate>>(
                fallbackRegistry.candidate(id, version),
            ).value.repository.id,
        )

        val unauthorized = StubRepository(
            "afox",
            "https://afox.example/",
            versionsResult = McfpmResult.Failure(
                listOf(Diagnostic(DiagnosticCode.NETWORK_FAILURE, DiagnosticSeverity.ERROR, "HTTP 401")),
            ),
        )
        val failRegistry = RepositoryRegistry(
            listOf(unauthorized, central),
            defaultRepositoryId = "afox",
            defaultRepositoryIds = listOf("afox", "central"),
        )
        val failure = assertIs<McfpmResult.Failure>(failRegistry.candidate(id, version))
        assertTrue(failure.diagnostics.single().message.contains("HTTP 401"))
    }

    @Test
    fun `group binding is a single repository and never falls back`() {
        val id = PackageId.parse("example:pack")
        val version = SemVer.parse("1.0.0")
        val bound = StubRepository("bound", "https://bound.example/")
        val central = StubRepository("central", "https://central.example/", mapOf(version to manifest(id, version)))
        val registry = RepositoryRegistry(
            listOf(bound, central),
            bindings = listOf(RepositoryBinding("example", "bound")),
            defaultRepositoryId = "central",
        )

        val result = assertIs<McfpmResult.Success<List<RepositoryCandidate>>>(registry.candidates(id)).value
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a higher priority repository with no matching version falls back`() {
        val id = PackageId.parse("example:pack")
        val requested = SemVer.parse("1.0.0")
        val newer = SemVer.parse("2.0.0")
        val afox = StubRepository("afox", "https://afox.example/", mapOf(newer to manifest(id, newer)))
        val central = StubRepository("central", "https://central.example/", mapOf(requested to manifest(id, requested)))
        val registry = RepositoryRegistry(
            listOf(afox, central),
            defaultRepositoryId = "afox",
            defaultRepositoryIds = listOf("afox", "central"),
        )

        assertEquals(
            "central",
            assertIs<McfpmResult.Success<RepositoryCandidate>>(registry.candidate(id, requested)).value.repository.id,
        )
    }

    private fun manifest(packageId: PackageId, version: SemVer): RepositoryManifest {
        val manifest = PackageManifest(packageId = packageId, version = version, license = "MIT")
        return RepositoryManifest(manifest, CanonicalJson.encodeManifest(manifest))
    }

    private class StubRepository(
        override val id: String,
        baseUri: String,
        private val manifests: Map<SemVer, RepositoryManifest> = emptyMap(),
        private val versionsResult: McfpmResult<List<SemVer>> = McfpmResult.Success(manifests.keys.toList()),
    ) : PackageRepository {
        override val baseUri: URI = URI.create(baseUri)

        override fun versions(packageId: PackageId): McfpmResult<List<SemVer>> = versionsResult

        override fun manifest(packageId: PackageId, version: SemVer): McfpmResult<RepositoryManifest> =
            manifests[version]?.let { McfpmResult.Success(it) } ?: McfpmResult.Failure(listOf(notFoundDiagnostic()))

        override fun artifactUri(packageId: PackageId, version: SemVer, artifact: ArtifactDescriptor): URI =
            baseUri.resolve("${packageId.name}-${version}-${artifact.classifier}.zip")
    }
}
