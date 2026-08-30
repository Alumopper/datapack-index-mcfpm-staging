package moe.afox.mcfpm.minecraft

import moe.afox.mcfpm.core.FetchedGraph
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.InstallContext
import moe.afox.mcfpm.core.InstallContextKind
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadRef
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.model.ResolvedPackage
import moe.afox.mcfpm.model.SemVer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MinecraftInstallEngineTest {
    @Test
    fun `default Mojang data version registry maps supported releases and rejects unknown values`() {
        assertEquals("1.21.4", MojangDataVersionRegistry.minecraftVersion(4189))
        assertEquals("26.1", MojangDataVersionRegistry.minecraftVersion(4786))
        assertEquals("26.2", MojangDataVersionRegistry.minecraftVersion(4903))
        assertEquals(null, MojangDataVersionRegistry.minecraftVersion(Int.MAX_VALUE))
    }

    private val detector = MinecraftInstallContextDetector()

    @Test
    fun `project context fetches only and writes no bundle or minecraft files`() {
        val project = createTempDirectory("mcfpm-project-install")
        Files.writeString(project.resolve("mcfpm.toml"), "schema = 1")
        val context = success(detector.detect(project))

        val installed = success(MinecraftInstallEngine().install(request(emptyGraph(), context)))

        assertTrue(installed.plan.copies.isEmpty())
        assertEquals(null, installed.transactionId)
        assertFalse(Files.exists(project.resolve(".mcfpm")))
    }

    @Test
    fun `owned world requires confirmation installs both payload kinds and rolls back byte for byte`() {
        val instance = instance()
        val world = world(Files.createDirectories(instance.resolve("saves/owned")))
        val context = success(detector.detect(world))
        val originalOptions = Files.readAllBytes(instance.resolve("options.txt"))
        val originalLevel = Files.readAllBytes(world.resolve("level.dat"))
        val fetched = graph(
            temporary = world,
            payloads = listOf(
                PayloadSpec("test:dependency", PayloadType.MINECRAFT_RESOURCEPACK, "resourcepack"),
                PayloadSpec("test:root", PayloadType.MINECRAFT_DATAPACK, "datapack"),
            ),
            minecraftVersion = "1.21.4",
        )
        val engine = MinecraftInstallEngine()

        val confirmation = assertIs<McfpmResult.Failure>(engine.install(request(fetched, context)))
        assertEquals(DiagnosticCode.INSTALL_CONFIRMATION_REQUIRED, confirmation.diagnostics.single().code)
        assertFalse(Files.exists(world.resolve("datapacks")))

        val dryRun = success(engine.install(request(fetched, context).copy(dryRun = true)))
        assertTrue(dryRun.dryRun)
        assertEquals(2, dryRun.plan.copies.size)
        assertFalse(Files.exists(world.resolve("datapacks")))

        val result = success(
            engine.install(request(fetched, context).copy(confirmedGlobalResourcePackImpact = true)),
        )
        assertTrue(result.plan.copies.all { Files.isRegularFile(it.target) })
        val options = Files.readString(instance.resolve("options.txt"))
        assertTrue(options.contains("file/user.zip"))
        assertTrue(options.contains("file/mcfpm-test-dependency-1.0.0-resourcepack.zip"))
        assertEquals(
            listOf("vanilla", "file/mcfpm-test-root-1.0.0-datapack.zip"),
            MinecraftLevelDat.enabledDataPacks(Files.readAllBytes(world.resolve("level.dat"))),
        )

        success(engine.rollback(context))
        assertContentEquals(originalOptions, Files.readAllBytes(instance.resolve("options.txt")))
        assertContentEquals(originalLevel, Files.readAllBytes(world.resolve("level.dat")))
        assertTrue(result.plan.copies.none { Files.exists(it.target) })
    }

    @Test
    fun `independent world selects the version specific world resource path`() {
        listOf(
            "1.21.4" to Path.of("resources.zip"),
            "26.1" to Path.of("resourcepacks/resources.zip"),
        ).forEach { (version, relative) ->
            val world = world(createTempDirectory("mcfpm-independent-world"))
            val context = success(detector.detect(world))
            val fetched = graph(
                world,
                listOf(PayloadSpec("test:resources", PayloadType.MINECRAFT_RESOURCEPACK, "resourcepack")),
                version,
            )

            val result = success(MinecraftInstallEngine().install(request(fetched, context)))

            assertEquals(world.resolve(relative), result.plan.copies.single().target)
            assertTrue(Files.isRegularFile(world.resolve(relative)))
        }
    }

    @Test
    fun `independent world rejects multiple resource packs without partial writes`() {
        val world = world(createTempDirectory("mcfpm-multiple-world-resources"))
        val context = success(detector.detect(world))
        val fetched = graph(
            world,
            listOf(
                PayloadSpec("test:a", PayloadType.MINECRAFT_RESOURCEPACK, "a"),
                PayloadSpec("test:b", PayloadType.MINECRAFT_RESOURCEPACK, "b"),
            ),
            "26.1",
        )

        val failure = assertIs<McfpmResult.Failure>(MinecraftInstallEngine().install(request(fetched, context)))

        assertEquals(DiagnosticCode.DEPLOYMENT_FAILED, failure.diagnostics.single().code)
        assertFalse(Files.exists(world.resolve("resourcepacks")))
    }

    @Test
    fun `instance data pack install requires explicit world`() {
        val instance = instance()
        val context = success(detector.detect(instance))
        val fetched = graph(
            instance,
            listOf(PayloadSpec("test:data", PayloadType.MINECRAFT_DATAPACK, "datapack")),
            "1.21.4",
        )

        val failure = assertIs<McfpmResult.Failure>(MinecraftInstallEngine().install(request(fetched, context)))

        assertEquals(DiagnosticCode.DEPLOYMENT_FAILED, failure.diagnostics.single().code)
    }

    @Test
    fun `world session lock prevents writes`() {
        val world = world(createTempDirectory("mcfpm-busy-world"))
        val context = success(detector.detect(world))
        val fetched = graph(
            world,
            listOf(PayloadSpec("test:data", PayloadType.MINECRAFT_DATAPACK, "datapack")),
            "1.21.4",
        )
        FileChannel.open(world.resolve("session.lock"), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val failure = assertIs<McfpmResult.Failure>(
                    MinecraftInstallEngine().install(request(fetched, context)),
                )
                assertEquals(DiagnosticCode.INSTALL_TARGET_BUSY, failure.diagnostics.single().code)
            }
        }
        assertFalse(Files.exists(world.resolve("datapacks")))
    }

    @Test
    fun `failure interruption restores every mutation`() {
        val instance = instance()
        val world = world(Files.createDirectories(instance.resolve("saves/interrupted")))
        val context = success(detector.detect(world))
        val originalOptions = Files.readAllBytes(instance.resolve("options.txt"))
        val originalLevel = Files.readAllBytes(world.resolve("level.dat"))
        val fetched = graph(
            world,
            listOf(
                PayloadSpec("test:resource", PayloadType.MINECRAFT_RESOURCEPACK, "resourcepack"),
                PayloadSpec("test:data", PayloadType.MINECRAFT_DATAPACK, "datapack"),
            ),
            "1.21.4",
        )
        val engine = MinecraftInstallEngine(
            mutationHook = MinecraftInstallEngine.MutationHook { index, _ ->
                if (index == 1) throw IllegalStateException("simulated interruption")
            },
        )

        assertIs<McfpmResult.Failure>(
            engine.install(request(fetched, context).copy(confirmedGlobalResourcePackImpact = true)),
        )
        assertContentEquals(originalOptions, Files.readAllBytes(instance.resolve("options.txt")))
        assertContentEquals(originalLevel, Files.readAllBytes(world.resolve("level.dat")))
        assertFalse(Files.exists(world.resolve("datapacks")).let { exists ->
            exists && Files.list(world.resolve("datapacks")).use { it.findAny().isPresent }
        })
    }

    @Test
    fun `upgrade removes obsolete managed packs and rollback restores previous install`() {
        val instance = instance()
        val context = success(detector.detect(instance))
        val engine = MinecraftInstallEngine()
        val firstGraph = graph(
            instance,
            listOf(
                PayloadSpec("test:a", PayloadType.MINECRAFT_RESOURCEPACK, "a", "1.0.0"),
                PayloadSpec("test:b", PayloadType.MINECRAFT_RESOURCEPACK, "b", "1.0.0"),
            ),
            "1.21.4",
        )
        val first = success(engine.install(request(firstGraph, context)))
        val secondGraph = graph(
            instance,
            listOf(PayloadSpec("test:a", PayloadType.MINECRAFT_RESOURCEPACK, "a", "2.0.0")),
            "1.21.4",
        )

        val second = success(engine.install(request(secondGraph, context)))
        assertTrue(second.plan.removals.containsAll(first.plan.copies.map { it.target }))
        assertTrue(first.plan.copies.none { Files.exists(it.target) })
        assertTrue(second.plan.copies.all { Files.exists(it.target) })

        success(engine.rollback(context))
        assertTrue(first.plan.copies.all { Files.exists(it.target) })
        assertTrue(second.plan.copies.none { Files.exists(it.target) })
    }

    @Test
    fun `user drift blocks rollback unless forced`() {
        val instance = instance()
        val context = success(detector.detect(instance))
        val fetched = graph(
            instance,
            listOf(PayloadSpec("test:a", PayloadType.MINECRAFT_RESOURCEPACK, "a")),
            "1.21.4",
        )
        val engine = MinecraftInstallEngine()
        val installed = success(engine.install(request(fetched, context)))
        Files.writeString(installed.plan.copies.single().target, "user edit")

        val failure = assertIs<McfpmResult.Failure>(engine.rollback(context))
        assertEquals(DiagnosticCode.USER_DRIFT, failure.diagnostics.single().code)
        success(engine.rollback(context, force = true))
        assertFalse(Files.exists(installed.plan.copies.single().target))
    }

    private fun request(fetched: FetchedGraph, context: InstallContext): MinecraftInstallRequest =
        MinecraftInstallRequest(fetched, context)

    private fun emptyGraph(): FetchedGraph = FetchedGraph(
        ResolvedGraph("1", emptyList(), emptyList(), emptyList(), emptyList()),
        emptyMap(),
    )

    private fun graph(
        temporary: Path,
        payloads: List<PayloadSpec>,
        minecraftVersion: String,
    ): FetchedGraph {
        val artifacts = linkedMapOf<PayloadRef, Path>()
        val packages = payloads.map { specification ->
            val packageId = PackageId.parse(specification.id)
            val version = SemVer.parse(specification.version)
            val bytes = ReproducibleZip.fromEntries(
                listOf(
                    "pack.mcmeta" to """{"pack":{"pack_format":48,"description":"${specification.id}"}}""".encodeToByteArray(),
                    "content.txt" to specification.id.encodeToByteArray(),
                ),
            )
            val source = Files.createTempFile(temporary, "payload-", ".zip")
            Files.write(source, bytes)
            val descriptor = ArtifactDescriptor(
                specification.type,
                specification.classifier,
                sha256 = Hashing.sha256(bytes),
                size = bytes.size.toLong(),
            )
            val reference = PayloadRef(packageId, specification.type, specification.classifier)
            artifacts[reference] = source
            ResolvedPackage(
                packageId,
                version,
                "file:///repository/",
                "a".repeat(64),
                artifacts = listOf(descriptor),
            )
        }
        return FetchedGraph(
            ResolvedGraph(
                resolverVersion = "1",
                roots = packages.map { it.packageId },
                packages = packages,
                edges = emptyList(),
                loadOrder = artifacts.keys.toList(),
                minecraftVersion = minecraftVersion,
            ),
            artifacts,
        )
    }

    private fun instance(): Path {
        val instance = createTempDirectory("mcfpm-instance-install")
        Files.writeString(instance.resolve("options.txt"), "resourcePacks:[\"vanilla\",\"file/user.zip\"]\nother:value\n")
        Files.createDirectories(instance.resolve("resourcepacks"))
        Files.createDirectories(instance.resolve("saves"))
        return instance
    }

    private fun world(world: Path): Path {
        Files.createDirectories(world)
        Files.write(world.resolve("level.dat"), levelDat())
        Files.write(world.resolve("session.lock"), ByteArray(8))
        return world
    }

    private fun levelDat(): ByteArray {
        val enabled = NbtTag.ListTag(
            8,
            mutableListOf(NbtTag.StringTag("vanilla")),
        )
        val dataPacks = NbtTag.CompoundTag(linkedMapOf("Enabled" to enabled, "Disabled" to NbtTag.ListTag(8, mutableListOf())))
        val data = NbtTag.CompoundTag(linkedMapOf("DataVersion" to NbtTag.IntTag(3955), "DataPacks" to dataPacks))
        return NbtCodec.encode(NamedNbt("", NbtTag.CompoundTag(linkedMapOf("Data" to data)), compressed = true))
    }

    private fun <T> success(result: McfpmResult<T>): T = assertIs<McfpmResult.Success<T>>(
        result,
        (result as? McfpmResult.Failure)?.diagnostics?.joinToString { "${it.code}: ${it.message}" },
    ).value

    private data class PayloadSpec(
        val id: String,
        val type: PayloadType,
        val classifier: String,
        val version: String = "1.0.0",
    )
}
