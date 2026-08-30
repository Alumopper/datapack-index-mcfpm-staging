package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PayloadType
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExternalArtifactIngesterTest {
    @Test
    fun `normalizes and deduplicates immutable external artifacts`() {
        val temporary = createTempDirectory("mcfpm-ingest-test")
        val raw = ReproducibleZip.fromEntries(
            listOf(
                "pack.mcmeta" to """{"pack":{"pack_format":48,"description":"external"}}""".encodeToByteArray(),
                "data/test/functions/a.mcfunction" to "say hi".encodeToByteArray(),
            ).reversed(),
        )
        val source = temporary.resolve("external.zip")
        Files.write(source, raw)
        val specification = ExternalArtifactSpec(
            canonicalId = "github:owner/project:release-v1",
            sourceKind = ExternalSourceKind.GITHUB,
            uri = source.toUri(),
            immutableVersion = "release-v1",
            expectedSha256 = Hashing.sha256(raw),
            expectedSize = raw.size.toLong(),
            redistributionLicense = "MIT",
            type = PayloadType.MINECRAFT_DATAPACK,
            classifier = "datapack",
        )

        val packed = assertIs<McfpmResult.Success<List<PackedExternalArtifact>>>(
            ExternalArtifactIngester(ContentAddressedCache(temporary.resolve("cache")))
                .ingest(listOf(specification, specification)),
        ).value

        assertEquals(1, packed.size)
        assertEquals(Hashing.sha256(packed.single().bytes), packed.single().descriptor.sha256)
        assertEquals("github", packed.single().descriptor.source?.kind)
    }

    @Test
    fun `same canonical ID with different checksum fails`() {
        val temporary = createTempDirectory("mcfpm-ingest-conflict-test")
        val firstBytes = ReproducibleZip.fromEntries(
            listOf("pack.mcmeta" to """{"pack":{"pack_format":48}}""".encodeToByteArray()),
        )
        val firstPath = temporary.resolve("first.zip")
        Files.write(firstPath, firstBytes)
        val first = specification(firstPath, firstBytes)
        val second = first.copy(expectedSha256 = "f".repeat(64))

        val failure = assertIs<McfpmResult.Failure>(
            ExternalArtifactIngester(ContentAddressedCache(temporary.resolve("cache")))
                .ingest(listOf(first, second)),
        )

        assertEquals(DiagnosticCode.INTEGRITY_FAILURE, failure.diagnostics.single().code)
    }

    private fun specification(path: java.nio.file.Path, bytes: ByteArray): ExternalArtifactSpec =
        ExternalArtifactSpec(
            canonicalId = "url:example.invalid/file-v1",
            sourceKind = ExternalSourceKind.URL,
            uri = path.toUri(),
            immutableVersion = "v1",
            expectedSha256 = Hashing.sha256(bytes),
            expectedSize = bytes.size.toLong(),
            redistributionLicense = "CC0-1.0",
            type = PayloadType.MINECRAFT_DATAPACK,
            classifier = "datapack",
        )
}
