package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.Dependency
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.VersionRequirement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class ImportCandidateTest {
    @Test
    fun `candidate archive is deterministic and round trips root level datapack`() {
        val candidate = candidate()
        val first = ImportCandidateCodec.encode(candidate)
        val second = ImportCandidateCodec.encode(candidate)

        assertContentEquals(first, second)
        val decoded = ImportCandidateCodec.decode(first)
        assertEquals(listOf("example:a", "example:z"), decoded.document.dependencies.map { it.packageId.value })
        assertEquals(candidate.document.payload, decoded.document.payload)
        assertContentEquals(candidate.payload, decoded.payload)
        assertEquals(candidate.document.packageId, decoded.manifest().packageId)
    }

    @Test
    fun `candidate rejects changed payload and unsafe selection`() {
        val candidate = candidate()
        val entries = ReproducibleZip.readEntries(ImportCandidateCodec.encode(candidate)).entries
        val changedPayload = entries.map { (name, bytes) ->
            name to if (name == "payload.zip") bytes + 0x01 else bytes
        }
        assertFailsWith<IllegalArgumentException> {
            ImportCandidateCodec.decode(ReproducibleZip.fromEntries(changedPayload))
        }

        val unsafe = candidate.copy(
            document = candidate.document.copy(
                source = candidate.document.source.copy(
                    selectedRoot = "../packs",
                    selectionPath = "../packs",
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> { ImportCandidateCodec.encode(unsafe) }
    }

    private fun candidate(): FrozenImportCandidate {
        val payload = ReproducibleZip.fromEntries(
            listOf(
                "pack.mcmeta" to """{"pack":{"pack_format":48,"description":"fixture"}}""".encodeToByteArray(),
                "data/example/function/load.mcfunction" to "say hello\n".encodeToByteArray(),
            ),
        )
        val raw = "raw archive bytes".encodeToByteArray()
        val packageId = PackageId.parse("example:pack")
        val version = SemVer.parse("1.2.3")
        return FrozenImportCandidate(
            ImportCandidateDocument(
                source = ImportCandidateSource(
                    kind = "url",
                    requestUrl = "https://downloads.example.test/pack.zip",
                    finalUrl = "https://downloads.example.test/pack.zip",
                    rawSha256 = Hashing.sha256(raw),
                    rawSize = raw.size.toLong(),
                    immutableVersion = "sha256:${Hashing.sha256(raw)}",
                    selectionPath = "/",
                ),
                packageId = packageId,
                version = version,
                license = "MIT",
                dependencies = listOf(
                    Dependency(packageId = PackageId.parse("example:z"), requirement = VersionRequirement.parse("^1.0.0")),
                    Dependency(packageId = PackageId.parse("example:a"), requirement = VersionRequirement.exact(SemVer.parse("1.0.0"))),
                ),
                payload = ImportCandidatePayload(
                    type = PayloadType.MINECRAFT_DATAPACK,
                    classifier = "datapack",
                    normalizedSha256 = Hashing.sha256(payload),
                    normalizedSize = payload.size.toLong(),
                ),
            ),
            payload,
        )
    }
}
