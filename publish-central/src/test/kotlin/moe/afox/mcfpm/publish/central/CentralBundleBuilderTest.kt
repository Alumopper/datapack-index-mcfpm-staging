package moe.afox.mcfpm.publish.central

import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.LockfileCodec
import moe.afox.mcfpm.core.ManifestSigner
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.model.SemVer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CentralBundleBuilderTest {
    @Test
    fun `validates and builds a reproducible Central directory bundle`() {
        val payload = ReproducibleZip.fromEntries(
            listOf("pack.mcmeta" to """{"pack":{"pack_format":48}}""".encodeToByteArray()),
        )
        val manifest = ManifestSigner.sign(PackageManifest(
            packageId = PackageId.parse("io.github.example:demo"),
            version = SemVer.parse("1.0.0"),
            license = "Apache-2.0",
            artifacts = listOf(
                ArtifactDescriptor(
                    PayloadType.MINECRAFT_DATAPACK,
                    "datapack",
                    sha256 = Hashing.sha256(payload),
                    size = payload.size.toLong(),
                ),
            ),
        ), ManifestSigner.generateKeyPair())
        val prefix = "demo-1.0.0"
        val signatures = listOf("$prefix.pom", "$prefix.mcfpkg", "$prefix-datapack.zip")
            .associateWith { "valid-signature".encodeToByteArray() }
        val request = CentralBundleRequest(
            manifest = manifest,
            lockfile = LockfileCodec.encode(
                ResolvedGraph("1", emptyList(), emptyList(), emptyList(), emptyList()),
            ),
            artifactsByClassifier = mapOf("datapack" to payload),
            signaturesByFileName = signatures,
            pom = CentralPomMetadata(
                displayName = "Demo",
                description = "Demo package",
                projectUrl = "https://github.com/example/demo",
                scmUrl = "https://github.com/example/demo.git",
                licenseName = "Apache License 2.0",
                licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
                developers = listOf(CentralDeveloper("example", "Example Developer")),
            ),
        )
        val builder = CentralBundleBuilder(DetachedSignatureVerifier { _, signature -> signature.decodeToString() == "valid-signature" })

        val first = assertIs<McfpmResult.Success<ByteArray>>(builder.build(request)).value
        val second = assertIs<McfpmResult.Success<ByteArray>>(builder.build(request)).value

        assertContentEquals(first, second)
        assertIs<McfpmResult.Success<Unit>>(CentralBundleInspector.validate(first))
        val verified = ReproducibleZip.verify(first)
        assertTrue(verified.entries.any { it.endsWith("demo-1.0.0.mcfpkg") })
        assertTrue(verified.entries.any { it.endsWith("demo-1.0.0-datapack.zip.asc.sha512") })
    }

    @Test
    fun `rejects payload drift before creating a bundle`() {
        val manifest = PackageManifest(
            packageId = PackageId.parse("io.github.example:demo"),
            version = SemVer.parse("1.0.0"),
            license = "Apache-2.0",
            artifacts = listOf(
                ArtifactDescriptor(PayloadType.MCFPP_LIBRARY, "mcfpp", sha256 = "a".repeat(64), size = 1),
            ),
        )
        val request = CentralBundleRequest(
            manifest,
            LockfileCodec.encode(ResolvedGraph("1", emptyList(), emptyList(), emptyList(), emptyList())),
            mapOf("mcfpp" to byteArrayOf(1)),
            emptyMap(),
            CentralPomMetadata("Demo", "Demo", "https://example.invalid", "https://example.invalid/repo.git", "MIT", "https://opensource.org/license/mit", listOf(CentralDeveloper("dev", "Dev"))),
        )

        assertIs<McfpmResult.Failure>(CentralBundleBuilder(DetachedSignatureVerifier { _, _ -> true }).build(request))
    }
}
