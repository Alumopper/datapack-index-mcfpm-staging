package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.ArtifactSource
import moe.afox.mcfpm.model.Dependency
import moe.afox.mcfpm.model.FeatureDefinition
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.PayloadRequirement
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.ToolConfiguration
import moe.afox.mcfpm.model.VersionRequirement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackageManifestCodecTest {
    @Test
    fun `canonical TOML round trips and ignores declaration ordering`() {
        val first = manifest(reverse = false)
        val second = manifest(reverse = true)

        val firstBytes = PackageManifestCodec.encode(first)
        val secondBytes = PackageManifestCodec.encode(second)

        assertContentEquals(firstBytes, secondBytes)
        assertEquals(first.normalized(), PackageManifestCodec.decode(firstBytes))
    }

    @Test
    fun `rejects undeclared dependency versions and unknown keys`() {
        assertFailsWith<IllegalArgumentException> {
            PackageManifestCodec.decode(
                """
                schema = 1
                surprise = true
                [package]
                id = "example:root"
                version = "1.0.0"
                license = "MIT"
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PackageManifestCodec.decode(
                """
                schema = 1
                [package]
                id = "example:root"
                version = "1.0.0"
                license = "MIT"
                [dependencies]
                "example:child" = { optional = true }
                """.trimIndent(),
            )
        }
    }

    private fun manifest(reverse: Boolean): PackageManifest {
        val dependencies = listOf(
            Dependency(PackageId.parse("example:b"), VersionRequirement.parse("^1.2.3"), listOf("client")),
            Dependency(PackageId.parse("example:a"), VersionRequirement.parse("2.0.0"), optional = true),
        ).let { if (reverse) it.reversed() else it }
        val artifacts = listOf(
            ArtifactDescriptor(
                type = PayloadType.MINECRAFT_RESOURCEPACK,
                classifier = "resourcepack",
                sha256 = "b".repeat(64),
                size = 20,
                source = ArtifactSource(
                    kind = "url",
                    uri = "https://example.invalid/resource.zip",
                    immutableVersion = "v1",
                    redistributionLicense = "CC0-1.0",
                    upstreamId = "asset-42",
                    revision = "a".repeat(40),
                    path = "dist/resource.zip!/pack",
                    sha256 = "c".repeat(64),
                    size = 42,
                ),
            ),
            ArtifactDescriptor(
                type = PayloadType.MINECRAFT_DATAPACK,
                classifier = "datapack",
                sha256 = "a".repeat(64),
                size = 10,
                minecraft = ">=1.21 <2.0",
                requires = listOf(PayloadRequirement(PayloadType.MINECRAFT_RESOURCEPACK, "resourcepack")),
            ),
        ).let { if (reverse) it.reversed() else it }
        return PackageManifest(
            packageId = PackageId.parse("example:root"),
            version = SemVer.parse("1.0.0"),
            license = "Apache-2.0",
            minecraft = ">=1.21",
            dependencies = dependencies,
            features = listOf(FeatureDefinition("client", listOf(PackageId.parse("example:b")))),
            artifacts = artifacts,
            tool = ToolConfiguration(
                consumerProfile = moe.afox.mcfpm.model.ConsumerProfile.MCFPP,
                defaultRepository = "private",
                repositories = mapOf("private" to "https://repo.example.invalid/maven2/"),
                bindings = mapOf("example" to "private"),
                options = mapOf("kore.package" to "example.bindings"),
            ),
        )
    }
}
