package moe.afox.mcfpm.mcfpp

import moe.afox.mcfpm.core.FetchedGraph
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.InMemoryTrustStore
import moe.afox.mcfpm.core.KoreLockGraphAdapter
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.core.TrustGrant
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadRef
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.model.ResolvedPackage
import moe.afox.mcfpm.model.SemVer
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.mcfpp.Project
import top.mcfpp.io.LibBinReader

class McfppAdapterTest {
    @Test
    fun `current MCFPP bridge compiles three level library graph through READ_LIB streams`() {
        Project.reset()
        LibBinReader.loaded.clear()
        val temporary = createTempDirectory("mcfpm-current-mcfpp")
        val config = Files.writeString(temporary.resolve("mcfpp.json"), "{}")
        val libraries = listOf("c", "b", "a").map { name ->
            McfppLibraryPayload(
                packageId = PackageId.parse("test:$name"),
                classifier = "mcfpp",
                binaryLibrary = "binary-$name".encodeToByteArray(),
                moduleJson = "{\"$name\":{\"base\":{},\"packages\":{}}}".encodeToByteArray(),
                moduleResources = mapOf("$name/data/value.txt" to "resource-$name".encodeToByteArray()),
            )
        }

        val result = assertIs<McfpmResult.Success<McfppCompilationResult>>(
            CurrentMcfppCompilerAdapter(javaClass.classLoader).compile(config, libraries),
        ).value

        assertEquals(3, result.loadedLibraries)
        assertEquals(3, result.loadedModules)
        assertEquals(listOf("binary-c", "binary-b", "binary-a"), LibBinReader.loaded)
        assertEquals(listOf("c", "b", "a"), Project.compiledModuleIds)
        assertEquals(listOf("resource-c", "resource-b", "resource-a"), Project.compiledResources)
        assertTrue(Project.modules.isEmpty())
        assertTrue(Project.observedResourceRoots.all { Files.notExists(it) })
        assertTrue(Project.stageProcessor[Project.CompileStage.READ_LIB.ordinal].isEmpty())
    }

    @Test
    fun `current MCFPP bridge returns stable failure for missing compiler ABI`() {
        val directory = createTempDirectory("mcfpm-incompatible-mcfpp")
        val config = Files.writeString(directory.resolve("mcfpp.json"), "{}")
        val emptyLoader = java.net.URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader())

        emptyLoader.use {
            val failure = assertIs<McfpmResult.Failure>(
                CurrentMcfppCompilerAdapter(it).compile(config, emptyList()),
            )
            assertEquals(DiagnosticCode.MCFPP_INTEGRATION_FAILED, failure.diagnostics.single().code)
        }
    }

    @Test
    fun `Kore and MCFPP consume their own payload types from one lock graph`() {
        val temporary = createTempDirectory("mcfpm-shared-lock")
        val packageId = PackageId.parse("test:shared")
        val datapack = PayloadRef(packageId, PayloadType.MINECRAFT_DATAPACK, "datapack")
        val library = PayloadRef(packageId, PayloadType.MCFPP_LIBRARY, "mcfpp")
        val datapackBytes = ReproducibleZip.fromEntries(
            listOf("pack.mcmeta" to "{\"pack\":{\"pack_format\":48}}".encodeToByteArray()),
        )
        val libraryBytes = ReproducibleZip.fromEntries(
            listOf("bin.mclib" to byteArrayOf(1), "module.json" to "{}".encodeToByteArray()),
        )
        val datapackPath = Files.write(temporary.resolve("datapack.zip"), datapackBytes)
        val libraryPath = Files.write(temporary.resolve("mcfpp.zip"), libraryBytes)
        val graph = ResolvedGraph(
            "1",
            listOf(packageId),
            listOf(
                ResolvedPackage(
                    packageId,
                    SemVer.parse("1.0.0"),
                    "file:///repo/",
                    "a".repeat(64),
                    artifacts = listOf(
                        ArtifactDescriptor(datapack.type, datapack.classifier, sha256 = Hashing.sha256(datapackBytes), size = datapackBytes.size.toLong()),
                        ArtifactDescriptor(library.type, library.classifier, sha256 = Hashing.sha256(libraryBytes), size = libraryBytes.size.toLong()),
                    ),
                ),
            ),
            emptyList(),
            listOf(datapack, library),
        )
        val fetched = FetchedGraph(graph, mapOf(datapack to datapackPath, library to libraryPath))

        assertEquals(listOf("datapack"), KoreLockGraphAdapter.bindings(graph).map { it.classifier })
        val libraries = assertIs<McfpmResult.Success<List<McfppLibraryPayload>>>(McfppDependencyAdapter(fetched).libraries()).value
        assertEquals(listOf("mcfpp"), libraries.map { it.classifier })
    }

    @Test
    fun `loads three level libraries through streams in lock order`() {
        val temporary = createTempDirectory("mcfpm-mcfpp-libraries")
        val references = listOf("c", "b", "a").map { name ->
            PayloadRef(PackageId.parse("test:$name"), PayloadType.MCFPP_LIBRARY, "mcfpp")
        }
        val paths = linkedMapOf<PayloadRef, java.nio.file.Path>()
        val packages = references.map { reference ->
            val bytes = ReproducibleZip.fromEntries(
                listOf(
                    "bin.mclib" to "binary-${reference.packageId.name}".encodeToByteArray(),
                    "module.json" to "{\"name\":\"${reference.packageId.name}\"}".encodeToByteArray(),
                    "resources/value.txt" to reference.packageId.value.encodeToByteArray(),
                ),
            )
            val path = Files.createTempFile(temporary, "library", ".zip")
            Files.write(path, bytes)
            paths[reference] = path
            ResolvedPackage(
                reference.packageId,
                SemVer.parse("1.0.0"),
                "file:///repo/",
                "a".repeat(64),
                artifacts = listOf(
                    ArtifactDescriptor(reference.type, reference.classifier, sha256 = Hashing.sha256(bytes), size = bytes.size.toLong()),
                ),
            )
        }
        val fetched = FetchedGraph(
            ResolvedGraph("1", listOf(references.last().packageId), packages, emptyList(), references),
            paths,
        )

        val libraries = assertIs<McfpmResult.Success<List<McfppLibraryPayload>>>(
            McfppDependencyAdapter(fetched).libraries(),
        ).value

        assertEquals(listOf("test:c", "test:b", "test:a"), libraries.map { it.packageId.value })
        assertTrue(libraries.all { "resources/value.txt" in it.moduleResources })
    }

    @Test
    fun `executable plugin requires exact package fingerprint trust`() {
        val temporary = createTempDirectory("mcfpm-mcfpp-plugin")
        val packageId = PackageId.parse("test:plugin")
        val reference = PayloadRef(packageId, PayloadType.JVM_PLUGIN, "plugin")
        val bytes = ReproducibleZip.fromEntries(listOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".encodeToByteArray()))
        val path = Files.createTempFile(temporary, "plugin", ".jar")
        Files.write(path, bytes)
        val fingerprint = "b".repeat(64)
        val descriptor = ArtifactDescriptor(
            PayloadType.JVM_PLUGIN,
            "plugin",
            extension = "jar",
            sha256 = Hashing.sha256(bytes),
            size = bytes.size.toLong(),
            executable = true,
        )
        val fetched = FetchedGraph(
            ResolvedGraph(
                "1",
                listOf(packageId),
                listOf(
                    ResolvedPackage(
                        packageId,
                        SemVer.parse("1.0.0"),
                        "file:///repo/",
                        "a".repeat(64),
                        signatureFingerprint = fingerprint,
                        artifacts = listOf(descriptor),
                    ),
                ),
                emptyList(),
                listOf(reference),
            ),
            mapOf(reference to path),
        )

        val untrusted = assertIs<McfpmResult.Failure>(TrustedMcfppPluginLoader(InMemoryTrustStore()).load(fetched))
        assertEquals(DiagnosticCode.UNTRUSTED_EXECUTABLE, untrusted.diagnostics.single().code)
        val trusted = assertIs<McfpmResult.Success<List<java.net.URLClassLoader>>>(
            TrustedMcfppPluginLoader(InMemoryTrustStore(listOf(TrustGrant(packageId, fingerprint)))).load(fetched),
        ).value
        assertEquals(1, trusted.size)
        trusted.forEach(java.net.URLClassLoader::close)
    }

    @Test
    fun `legacy includes and jars warn during migration and fail publication`() {
        val directory = createTempDirectory("mcfpm-mcfpp-project")
        val config = directory.resolve("mcfpp.json")
        Files.writeString(config, """{"includes":["legacy.mclib"],"jars":["legacy.jar"]}""")

        val legacy = McfppLegacyConfigInspector.inspect(config)

        assertTrue(legacy.isPresent)
        assertTrue(McfppLegacyConfigInspector.migrationDiagnostics(legacy).isNotEmpty())
        assertIs<McfpmResult.Failure>(McfppLegacyConfigInspector.validateForPublish(legacy))
        assertEquals(directory.resolve("mcfpm.toml"), McfppProjectLocator.manifestBeside(config))
    }
}
