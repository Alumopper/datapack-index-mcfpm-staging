package moe.afox.mcfpm.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SemVerTest {
    @Test
    fun `implements the SemVer precedence example`() {
        val ordered = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
        ).map(SemVer::parse)

        assertEquals(ordered, ordered.shuffled().sorted())
    }

    @Test
    fun `supports arbitrarily large numeric identifiers`() {
        val lower = SemVer.parse("999999999999999999999999999.0.0")
        val higher = SemVer.parse("1000000000000000000000000000.0.0")

        assertTrue(lower < higher)
    }

    @Test
    fun `build metadata does not affect precedence`() {
        assertEquals(
            0,
            SemVer.parse("1.2.3+linux").compareTo(SemVer.parse("1.2.3+windows")),
        )
    }

    @Test
    fun `rejects partial versions and leading zeroes`() {
        listOf("1", "1.2", "01.2.3", "1.02.3", "1.2.03", "1.2.3-01").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { SemVer.parse(value) }
        }
    }
}
