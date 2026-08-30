package moe.afox.mcfpm.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VersionRequirementTest {
    @Test
    fun `caret respects the leftmost non-zero component`() {
        assertMatches("^1.2.3", "1.2.3", "1.9.9")
        assertDoesNotMatch("^1.2.3", "2.0.0", "1.2.2")
        assertMatches("^0.2.3", "0.2.3", "0.2.99")
        assertDoesNotMatch("^0.2.3", "0.3.0")
        assertMatches("^0.0.3", "0.0.3")
        assertDoesNotMatch("^0.0.3", "0.0.4")
    }

    @Test
    fun `tilde holds the minor version`() {
        assertMatches("~1.2.3", "1.2.3", "1.2.999")
        assertDoesNotMatch("~1.2.3", "1.3.0", "1.2.2")
    }

    @Test
    fun `comparator ranges form an intersection`() {
        assertMatches(">=1.2.0 <2.0.0", "1.2.0", "1.9.9")
        assertDoesNotMatch(">=1.2.0 <2.0.0", "1.1.9", "2.0.0")
    }

    @Test
    fun `prereleases require explicit opt in`() {
        assertFalse(VersionRequirement.parse(">=1.0.0 <2.0.0").matches(SemVer.parse("1.5.0-beta.1")))
        assertTrue(VersionRequirement.parse(">=1.0.0-beta.1 <2.0.0").matches(SemVer.parse("1.5.0-beta.1")))
    }

    @Test
    fun `rejects ambiguous or Maven requirements`() {
        listOf("", "latest", "1.2", "[1.0,2.0)", "(,1.0]").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { VersionRequirement.parse(value) }
        }
    }

    private fun assertMatches(requirement: String, vararg versions: String) {
        val parsed = VersionRequirement.parse(requirement)
        versions.forEach { assertTrue(parsed.matches(SemVer.parse(it)), "$requirement should match $it") }
    }

    private fun assertDoesNotMatch(requirement: String, vararg versions: String) {
        val parsed = VersionRequirement.parse(requirement)
        versions.forEach { assertFalse(parsed.matches(SemVer.parse(it)), "$requirement should not match $it") }
    }
}
