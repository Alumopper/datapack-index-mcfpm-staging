package moe.afox.mcfpm.minecraft

import moe.afox.mcfpm.core.InstallContext
import moe.afox.mcfpm.core.InstallContextKind
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.McfpmResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MinecraftInstallContextDetectorTest {
    private val detector = MinecraftInstallContextDetector()

    @Test
    fun `detects project root and project descendants`() {
        val project = directory("project")
        Files.writeString(project.resolve("mcfpm.toml"), "schema = 1")
        val child = Files.createDirectories(project.resolve("a/b"))

        assertContext(project, InstallContextKind.PROJECT, project)
        assertContext(child, InstallContextKind.PROJECT, project)
    }

    @Test
    fun `detects instance root and resourcepacks directory`() {
        val instance = instance()

        assertContext(instance, InstallContextKind.INSTANCE, instance)
        assertContext(instance.resolve("resourcepacks"), InstallContextKind.INSTANCE, instance)
    }

    @Test
    fun `detects world root datapacks and post 26_1 resourcepacks directories`() {
        val world = world(directory("world"))
        val datapacks = Files.createDirectories(world.resolve("datapacks"))
        val resourcepacks = Files.createDirectories(world.resolve("resourcepacks"))

        assertContext(world, InstallContextKind.WORLD, world)
        assertContext(datapacks, InstallContextKind.WORLD, world)
        assertContext(resourcepacks, InstallContextKind.WORLD, world)
    }

    @Test
    fun `only infers an instance from strict instance saves world layout`() {
        val instance = instance()
        val ownedWorld = world(Files.createDirectories(instance.resolve("saves/owned")))
        val independentWorld = world(directory("independent"))

        val owned = success(detector.detect(ownedWorld))
        val independent = success(detector.detect(independentWorld))

        assertEquals(instance.toRealPath(), owned.inferredInstance)
        assertNull(independent.inferredInstance)
    }

    @Test
    fun `conflicting markers fail unless a context kind is forced`() {
        val directory = world(directory("conflict"))
        Files.writeString(directory.resolve("mcfpm.toml"), "schema = 1")

        val failure = assertIs<McfpmResult.Failure>(detector.detect(directory))
        assertEquals(DiagnosticCode.AMBIGUOUS_INSTALL_CONTEXT, failure.diagnostics.single().code)
        assertEquals(InstallContextKind.WORLD, success(detector.detect(directory, InstallContextKind.WORLD)).kind)
        assertEquals(InstallContextKind.PROJECT, success(detector.detect(directory, InstallContextKind.PROJECT)).kind)
    }

    @Test
    fun `unknown directory never falls back to a default minecraft directory`() {
        val failure = assertIs<McfpmResult.Failure>(detector.detect(directory("unknown")))

        assertEquals(DiagnosticCode.UNKNOWN_INSTALL_CONTEXT, failure.diagnostics.single().code)
    }

    private fun assertContext(start: Path, kind: InstallContextKind, root: Path) {
        val context = success(detector.detect(start))
        assertEquals(kind, context.kind)
        assertEquals(root.toRealPath(), context.root)
    }

    private fun instance(): Path {
        val root = directory("instance")
        Files.writeString(root.resolve("options.txt"), "resourcePacks:[]\n")
        Files.createDirectories(root.resolve("resourcepacks"))
        Files.createDirectories(root.resolve("saves"))
        return root
    }

    private fun world(root: Path): Path {
        Files.write(root.resolve("level.dat"), byteArrayOf(1))
        return root
    }

    private fun directory(name: String): Path =
        Files.createDirectories(createTempDirectory("mcfpm-context-test").resolve(name))

    private fun success(result: McfpmResult<InstallContext>): InstallContext =
        assertIs<McfpmResult.Success<InstallContext>>(result).value
}
