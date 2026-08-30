package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadRef
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.model.ResolvedPackage
import moe.afox.mcfpm.model.SemVer
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BundleBuilderTest {
    @Test
    fun `fails deterministic output name collisions instead of overwriting`() {
        val temporary = createTempDirectory("mcfpm-bundle-collision")
        val references = listOf("a.b:item", "a-b:item").map { id ->
            PayloadRef(PackageId.parse(id), PayloadType.MINECRAFT_DATAPACK, "datapack")
        }
        val bytes = ReproducibleZip.fromEntries(
            listOf("pack.mcmeta" to "{\"pack\":{\"pack_format\":48}}".encodeToByteArray()),
        )
        val artifacts = references.associateWith { reference ->
            Files.write(temporary.resolve("${reference.packageId.group.replace('.', '-')}-${reference.packageId.name}.zip"), bytes)
        }
        val packages = references.map { reference ->
            ResolvedPackage(
                reference.packageId,
                SemVer.parse("1.0.0"),
                "file:///repo/",
                "a".repeat(64),
                artifacts = listOf(
                    ArtifactDescriptor(reference.type, reference.classifier, sha256 = Hashing.sha256(bytes), size = bytes.size.toLong()),
                ),
            )
        }

        assertIs<McfpmResult.Failure>(
            BundleBuilder.build(FetchedGraph(ResolvedGraph("1", references.map { it.packageId }, packages, emptyList(), references), artifacts)),
        )
    }

    @Test
    fun `keeps packages separate in bottom to top order and warns on resource overlap`() {
        val temporary = createTempDirectory("mcfpm-bundle-test")
        val references = listOf("c", "b", "a").map { name ->
            PayloadRef(PackageId.parse("test:$name"), PayloadType.MINECRAFT_DATAPACK, "datapack")
        }
        val artifacts = linkedMapOf<PayloadRef, java.nio.file.Path>()
        val packages = references.map { reference ->
            val bytes = ReproducibleZip.fromEntries(
                listOf(
                    "pack.mcmeta" to """{"pack":{"pack_format":48}}""".encodeToByteArray(),
                    "data/shared/functions/value.mcfunction" to reference.packageId.value.encodeToByteArray(),
                ),
            )
            val path = Files.createTempFile(temporary, reference.packageId.name, ".zip")
            Files.write(path, bytes)
            artifacts[reference] = path
            ResolvedPackage(
                reference.packageId,
                SemVer.parse("1.0.0"),
                "file:///repo/",
                "a".repeat(64),
                artifacts = listOf(
                    ArtifactDescriptor(
                        reference.type,
                        reference.classifier,
                        sha256 = Hashing.sha256(bytes),
                        size = bytes.size.toLong(),
                    ),
                ),
            )
        }
        val graph = ResolvedGraph("1", listOf(references.last().packageId), packages, emptyList(), references)

        val bundle = assertIs<McfpmResult.Success<BundleOutput>>(
            BundleBuilder.build(FetchedGraph(graph, artifacts)),
        ).value
        val manifest = moe.afox.mcfpm.model.CanonicalJson.decode(
            moe.afox.mcfpm.core.BundleManifest.serializer(),
            bundle.manifest,
        )

        assertEquals(listOf("test:c", "test:b", "test:a"), manifest.entries.map { it.packageId.value })
        assertEquals(3, bundle.files.size)
        assertTrue(bundle.diagnostics.isNotEmpty())
    }
}
