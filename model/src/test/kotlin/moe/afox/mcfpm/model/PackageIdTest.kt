package moe.afox.mcfpm.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackageIdTest {
    @Test
    fun `parses lowercase coordinates`() {
        val id = PackageId.parse("io.github.alumopper:example-pack")

        assertEquals("io.github.alumopper", id.group)
        assertEquals("example-pack", id.name)
    }

    @Test
    fun `rejects uppercase missing and empty coordinate parts`() {
        listOf("Example:pack", "group", "group:", ":name", "group:name:extra").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { PackageId.parse(value) }
        }
    }
}
