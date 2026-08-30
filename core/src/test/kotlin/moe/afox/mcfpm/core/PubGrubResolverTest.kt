package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.ConsumerProfile
import moe.afox.mcfpm.model.Dependency
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.PayloadRequirement
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.VersionRequirement
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PubGrubResolverTest {
    @Test
    fun `empty project dependencies resolve to a valid empty lock graph`() {
        val graph = assertIs<McfpmResult.Success<moe.afox.mcfpm.model.ResolvedGraph>>(
            resolver().resolve(ResolveRequest(emptyList(), minecraftVersion = "1.21.4")),
        ).value

        assertTrue(graph.packages.isEmpty())
        assertEquals("1.21.4", graph.minecraftVersion)
    }

    @Test
    fun `resolves transitive graph once and orders dependencies before dependents`() {
        val c = manifest("test:c")
        val b = manifest("test:b", dependencies = listOf(dependency("test:c", "^1.0.0")))
        val a = manifest("test:a", dependencies = listOf(dependency("test:b", "^1.0.0")))
        val result = resolver(a, b, c).resolve(request("test:a"))

        val graph = assertIs<McfpmResult.Success<*>>(result).value as moe.afox.mcfpm.model.ResolvedGraph
        assertEquals(listOf("test:c", "test:b", "test:a"), graph.loadOrder.map { it.packageId.value })
        assertEquals(3, graph.packages.size)
    }

    @Test
    fun `backtracks from an incompatible highest version`() {
        val c1 = manifest("test:c", "1.0.0")
        val c2 = manifest("test:c", "2.0.0")
        val a1 = manifest("test:a", "1.0.0", listOf(dependency("test:c", "^1.0.0")))
        val a2 = manifest("test:a", "2.0.0", listOf(dependency("test:c", "^2.0.0")))
        val b = manifest("test:b", dependencies = listOf(dependency("test:c", "^1.0.0")))
        val result = resolver(a1, a2, b, c1, c2).resolve(
            ResolveRequest(
                listOf(
                    RootRequirement(PackageId.parse("test:a"), VersionRequirement.parse(">=1.0.0 <3.0.0")),
                    RootRequirement(PackageId.parse("test:b"), VersionRequirement.parse("1.0.0")),
                ),
            ),
        )

        val graph = (assertIs<McfpmResult.Success<*>>(result).value as moe.afox.mcfpm.model.ResolvedGraph)
        assertEquals(SemVer.parse("1.0.0"), graph.packages.single { it.packageId.value == "test:a" }.version)
        assertEquals(SemVer.parse("1.0.0"), graph.packages.single { it.packageId.value == "test:c" }.version)
    }

    @Test
    fun `returns a stable conflict chain when no version can satisfy constraints`() {
        val c1 = manifest("test:c", "1.0.0")
        val c2 = manifest("test:c", "2.0.0")
        val a = manifest("test:a", dependencies = listOf(dependency("test:c", "^1.0.0")))
        val b = manifest("test:b", dependencies = listOf(dependency("test:c", "^2.0.0")))
        val result = resolver(a, b, c1, c2).resolve(
            ResolveRequest(
                listOf(
                    RootRequirement(PackageId.parse("test:a"), VersionRequirement.parse("1.0.0")),
                    RootRequirement(PackageId.parse("test:b"), VersionRequirement.parse("1.0.0")),
                ),
            ),
        )

        val failure = assertIs<McfpmResult.Failure>(result)
        assertEquals(DiagnosticCode.RESOLUTION_FAILED, failure.diagnostics.single().code)
        assertTrue(failure.diagnostics.single().context.getValue("constraints").contains("test:a requires ^1.0.0"))
        assertTrue(failure.diagnostics.single().context.getValue("constraints").contains("test:b requires ^2.0.0"))
    }

    @Test
    fun `rejects dependency cycles`() {
        val a = manifest("test:a", dependencies = listOf(dependency("test:b", "1.0.0")))
        val b = manifest("test:b", dependencies = listOf(dependency("test:a", "1.0.0")))

        val failure = assertIs<McfpmResult.Failure>(resolver(a, b).resolve(request("test:a")))

        assertEquals(DiagnosticCode.DEPENDENCY_CYCLE, failure.diagnostics.single().code)
        assertEquals("test:a -> test:b -> test:a", failure.diagnostics.single().context["cycle"])
    }

    @Test
    fun `consumer profile keeps required paired payload without unrelated payloads`() {
        val manifest = manifest(
            "test:a",
            artifacts = listOf(
                artifact(PayloadType.MCFPP_LIBRARY, "mcfpp", "a"),
                artifact(PayloadType.MINECRAFT_RESOURCEPACK, "resources", "b"),
                artifact(PayloadType.MINECRAFT_DATAPACK, "datapack", "c"),
            ),
        ).let { value ->
            value.copy(
                artifacts = value.artifacts.map { item ->
                    if (item.classifier == "mcfpp") {
                        item.copy(requires = listOf(PayloadRequirement(PayloadType.MINECRAFT_RESOURCEPACK, "resources")))
                    } else {
                        item
                    }
                },
            )
        }

        val graph = (assertIs<McfpmResult.Success<*>>(
            resolver(manifest).resolve(request("test:a", ConsumerProfile.MCFPP)),
        ).value as moe.afox.mcfpm.model.ResolvedGraph)

        assertEquals(listOf("mcfpp", "resources"), graph.packages.single().artifacts.map { it.classifier }.sorted())
    }

    @Test
    fun `minecraft target mismatch is a warning and does not reject resolution`() {
        val payload = artifact(PayloadType.MINECRAFT_DATAPACK, "datapack", "e")
            .copy(minecraft = ">=1.20 <1.21")
        val result = resolver(manifest("test:a", artifacts = listOf(payload))).resolve(
            request("test:a").copy(minecraftVersion = "1.21.4"),
        )

        val graph = assertIs<McfpmResult.Success<moe.afox.mcfpm.model.ResolvedGraph>>(result).value
        assertEquals(DiagnosticCode.MINECRAFT_FORMAT_MISMATCH, graph.diagnostics.single().code)
        assertEquals(moe.afox.mcfpm.model.DiagnosticSeverity.WARNING, graph.diagnostics.single().severity)
    }

    private fun resolver(vararg manifests: PackageManifest): PubGrubResolver {
        val repository = FakeRepository(manifests.toList())
        return PubGrubResolver(RepositoryRegistry(listOf(repository), defaultRepositoryId = repository.id))
    }

    private fun request(id: String, profile: ConsumerProfile = ConsumerProfile.ALL): ResolveRequest =
        ResolveRequest(
            listOf(RootRequirement(PackageId.parse(id), VersionRequirement.parse("1.0.0"))),
            profile,
        )

    private fun dependency(id: String, requirement: String): Dependency =
        Dependency(PackageId.parse(id), VersionRequirement.parse(requirement))

    private fun artifact(type: PayloadType, classifier: String, hashCharacter: String): ArtifactDescriptor =
        ArtifactDescriptor(type, classifier, sha256 = hashCharacter.repeat(64), size = 1)

    private fun manifest(
        id: String,
        version: String = "1.0.0",
        dependencies: List<Dependency> = emptyList(),
        artifacts: List<ArtifactDescriptor> = listOf(artifact(PayloadType.MINECRAFT_DATAPACK, "datapack", "d")),
    ): PackageManifest = PackageManifest(
        packageId = PackageId.parse(id),
        version = SemVer.parse(version),
        license = "Apache-2.0",
        dependencies = dependencies,
        artifacts = artifacts,
    )

    private class FakeRepository(manifests: List<PackageManifest>) : PackageRepository {
        private val manifests = manifests.associateBy { it.packageId to it.version }

        override val id: String = "fake"
        override val baseUri: URI = URI.create("file:///fake-repository/")

        override fun versions(packageId: PackageId): McfpmResult<List<SemVer>> =
            McfpmResult.Success(manifests.keys.filter { it.first == packageId }.map { it.second })

        override fun manifest(packageId: PackageId, version: SemVer): McfpmResult<RepositoryManifest> {
            val manifest = manifests[packageId to version]
                ?: return McfpmResult.Failure(emptyDiagnostics())
            return McfpmResult.Success(RepositoryManifest(manifest, CanonicalJson.encodeManifest(manifest)))
        }

        override fun artifactUri(
            packageId: PackageId,
            version: SemVer,
            artifact: ArtifactDescriptor,
        ): URI = baseUri.resolve("${packageId.name}/$version/${artifact.classifier}.${artifact.extension}")

        private fun emptyDiagnostics() = listOf(
            moe.afox.mcfpm.model.Diagnostic(
                DiagnosticCode.NETWORK_FAILURE,
                moe.afox.mcfpm.model.DiagnosticSeverity.ERROR,
                "missing",
            ),
        )
    }
}
