package moe.afox.mcfpm.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ReproducibleZipTest {
    @Test
    fun `accepts modern min and max pack format metadata`() {
        val archive = ReproducibleZip.fromEntries(
            listOf(
                "pack.mcmeta" to """{"pack":{"description":"modern","min_format":[82,0],"max_format":[88,0]}}""".encodeToByteArray(),
                "data/example/function/load.mcfunction" to byteArrayOf(),
            ),
        )

        ReproducibleZip.verify(archive, requirePackMetadata = true)
    }

    @Test
    fun `entry input order does not change bytes`() {
        val entries = listOf(
            "data/example/functions/a.mcfunction" to "say a\n".encodeToByteArray(),
            "pack.mcmeta" to """{"pack":{"pack_format":48,"description":"test"}}""".encodeToByteArray(),
        )

        val first = ReproducibleZip.fromEntries(entries)
        val second = ReproducibleZip.fromEntries(entries.reversed())

        assertContentEquals(first, second)
        val verified = ReproducibleZip.verify(first, requirePackMetadata = true)
        assertEquals(listOf("data/example/functions/a.mcfunction", "pack.mcmeta"), verified.entries)
        assertNotNull(verified.packMetadata)
    }

    @Test
    fun `rejects traversal duplicate and unsafe expansion`() {
        assertFailsWith<IllegalArgumentException> {
            ReproducibleZip.fromEntries(listOf("../escape" to byteArrayOf(1)))
        }
        assertFailsWith<IllegalArgumentException> {
            ReproducibleZip.fromEntries(
                listOf("same" to byteArrayOf(1), "same" to byteArrayOf(2)),
            )
        }
        val archive = ReproducibleZip.fromEntries(listOf("large" to ByteArray(16)))
        assertFailsWith<IllegalArgumentException> {
            ReproducibleZip.verify(archive, limits = ZipSafetyLimits(maxEntryBytes = 8))
        }
    }

    @Test
    fun `rejects missing and malformed pack metadata`() {
        val missing = ReproducibleZip.fromEntries(listOf("data/a" to byteArrayOf(1)))
        assertFailsWith<IllegalArgumentException> { ReproducibleZip.verify(missing, requirePackMetadata = true) }

        val malformed = ReproducibleZip.fromEntries(listOf("pack.mcmeta" to "{}".encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> { ReproducibleZip.verify(malformed, requirePackMetadata = true) }
    }
}
