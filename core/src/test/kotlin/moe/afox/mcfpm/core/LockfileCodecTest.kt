package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadRef
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.ResolvedEdge
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.model.ResolvedPackage
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.VersionRequirement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LockfileCodecTest {
    @Test
    fun `lockfile is deterministic and round trips the full graph`() {
        val graph = graph()
        val encoded = LockfileCodec.encode(graph)
        val decoded = LockfileCodec.decode(encoded)

        assertEquals(graph.normalized(), decoded)
        assertContentEquals(encoded, LockfileCodec.encode(decoded))
    }

    @Test
    fun `lockfile rejects unknown fields`() {
        val encoded = LockfileCodec.encode(graph()).decodeToString()
            .replace("lock-version = 1", "lock-version = 1\nunknown = true")

        assertFailsWith<IllegalArgumentException> { LockfileCodec.decode(encoded.encodeToByteArray()) }
    }

    private fun graph(): ResolvedGraph {
        val root = PackageId.parse("example:a")
        val child = PackageId.parse("example:b")
        val artifact = ArtifactDescriptor(
            type = PayloadType.MINECRAFT_DATAPACK,
            classifier = "datapack",
            sha256 = "a".repeat(64),
            size = 42,
        )
        return ResolvedGraph(
            resolverVersion = "1",
            roots = listOf(root),
            packages = listOf(
                ResolvedPackage(root, SemVer.parse("1.0.0"), "https://repo1.maven.org/maven2", "b".repeat(64), artifacts = listOf(artifact)),
                ResolvedPackage(
                    child,
                    SemVer.parse("2.0.0"),
                    "https://repo1.maven.org/maven2",
                    "c".repeat(64),
                    selectedFeatures = listOf("client"),
                    artifacts = listOf(artifact),
                ),
            ),
            edges = listOf(ResolvedEdge(root, child, VersionRequirement.parse("^2.0.0"))),
            loadOrder = listOf(PayloadRef(child, PayloadType.MINECRAFT_DATAPACK, "datapack"), PayloadRef(root, PayloadType.MINECRAFT_DATAPACK, "datapack")),
            diagnostics = listOf(
                Diagnostic(
                    DiagnosticCode.MINECRAFT_FORMAT_MISMATCH,
                    DiagnosticSeverity.WARNING,
                    "Target version is outside the payload range",
                    mapOf("target" to "1.21.4", "range" to "<1.21"),
                ),
            ),
        )
    }
}
