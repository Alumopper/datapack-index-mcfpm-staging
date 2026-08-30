package moe.afox.mcfpm.source.github

import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.PayloadType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MinecraftPackInspectorTest {
    @Test
    fun `discovers and normalizes a datapack under a GitHub archive wrapper`() {
        val archive = ReproducibleZip.fromEntries(
            listOf(
                "example-deadbeef/datapack/pack.mcmeta" to metadata(),
                "example-deadbeef/datapack/data/example/function/load.mcfunction" to "say hello".encodeToByteArray(),
                "example-deadbeef/README.md" to "readme".encodeToByteArray(),
            ),
        )

        val candidate = MinecraftPackInspector().inspect(
            archive,
            subdirectory = "datapack",
            stripSingleRootDirectory = true,
        )

        assertEquals("datapack", candidate.rootPath)
        assertEquals(PayloadType.MINECRAFT_DATAPACK, candidate.type)
        assertEquals(
            listOf("data/example/function/load.mcfunction", "pack.mcmeta"),
            ReproducibleZip.verify(candidate.payload, requirePackMetadata = true).entries.sorted(),
        )
    }

    @Test
    fun `requires an exact selector when an archive contains multiple packs`() {
        val archive = ReproducibleZip.fromEntries(
            listOf(
                "one/pack.mcmeta" to metadata(),
                "one/data/a/function/load.mcfunction" to byteArrayOf(),
                "two/pack.mcmeta" to metadata(),
                "two/assets/a/lang/en_us.json" to "{}".encodeToByteArray(),
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> { MinecraftPackInspector().inspect(archive) }
        assertTrue(failure.message.orEmpty().contains("one, two"))
    }

    @Test
    fun `inspects one nested build output zip and rejects mixed pack types`() {
        val nested = ReproducibleZip.fromEntries(
            listOf(
                "pack.mcmeta" to metadata(),
                "assets/example/lang/en_us.json" to "{}".encodeToByteArray(),
            ),
        )
        val archive = ReproducibleZip.fromEntries(listOf("build/releases/example.zip" to nested))
        val candidate = MinecraftPackInspector().inspect(archive, nestedZip = "build/releases/example.zip")
        assertEquals(PayloadType.MINECRAFT_RESOURCEPACK, candidate.type)
        assertEquals("build/releases/example.zip", candidate.nestedZip)

        val mixed = ReproducibleZip.fromEntries(
            listOf(
                "pack.mcmeta" to metadata(),
                "data/example/function/load.mcfunction" to byteArrayOf(),
                "assets/example/lang/en_us.json" to "{}".encodeToByteArray(),
            ),
        )
        assertFailsWith<IllegalArgumentException> { MinecraftPackInspector().inspect(mixed) }
    }

    private fun metadata(): ByteArray =
        """{"pack":{"pack_format":48,"description":"fixture"}}""".encodeToByteArray()
}
