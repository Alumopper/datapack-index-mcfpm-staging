package moe.afox.mcfpm.model

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanonicalJsonTest {
    @Test
    fun `normalizes descriptor collection order and object keys`() {
        val a = descriptor(reverse = false)
        val b = descriptor(reverse = true)

        val first = CanonicalJson.encodeManifest(a)
        val second = CanonicalJson.encodeManifest(b)

        assertContentEquals(first, second)
        assertEquals(sha256(first), sha256(second))
        assertTrue(first.decodeToString().startsWith("{\"artifacts\":"))
        assertTrue(first.decodeToString().indexOf("\"packageId\"") < first.decodeToString().indexOf("\"schema\""))
    }

    @Test
    fun `round trips a package descriptor`() {
        val manifest = descriptor(reverse = false).normalized()
        val encoded = CanonicalJson.encodeManifest(manifest)

        assertEquals(
            manifest,
            CanonicalJson.decode(PackageManifest.serializer(), encoded),
        )
    }

    private fun descriptor(reverse: Boolean): PackageManifest {
        val dependencies = listOf(
            Dependency(PackageId.parse("example:b"), VersionRequirement.parse("^1.0.0"), listOf("z", "a")),
            Dependency(PackageId.parse("example:a"), VersionRequirement.parse("~2.0.0")),
        ).let { if (reverse) it.reversed() else it }
        val artifacts = listOf(
            ArtifactDescriptor(
                type = PayloadType.MINECRAFT_RESOURCEPACK,
                classifier = "resourcepack",
                sha256 = "b".repeat(64),
                size = 12,
            ),
            ArtifactDescriptor(
                type = PayloadType.MINECRAFT_DATAPACK,
                classifier = "datapack",
                sha256 = "a".repeat(64),
                size = 10,
                requires = listOf(PayloadRequirement(PayloadType.MINECRAFT_RESOURCEPACK, "resourcepack")),
            ),
        ).let { if (reverse) it.reversed() else it }
        return PackageManifest(
            packageId = PackageId.parse("example:root"),
            version = SemVer.parse("1.0.0"),
            license = "Apache-2.0",
            dependencies = dependencies,
            artifacts = artifacts,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
