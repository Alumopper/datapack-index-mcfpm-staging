package moe.afox.mcfpm.mcfpp

import moe.afox.mcfpm.core.ArtifactVerifier
import moe.afox.mcfpm.core.FetchedGraph
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.core.TrustStore
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadRef
import moe.afox.mcfpm.model.PayloadType
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

public data class McfppLibraryPayload(
    public val packageId: PackageId,
    public val classifier: String,
    public val binaryLibrary: ByteArray,
    public val moduleJson: ByteArray,
    public val moduleResources: Map<String, ByteArray>,
)

public object McfppLibraryStreamLoader {
    public fun load(
        packageId: PackageId,
        classifier: String,
        input: InputStream,
    ): McfppLibraryPayload {
        val entries = linkedMapOf<String, ByteArray>()
        var expandedBytes = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = ReproducibleZip.validateEntryName(entry.name)
                if (entry.isDirectory) continue
                require(entries.size < 100_000) { "MCFPP library contains too many entries" }
                val content = zip.readLimited()
                expandedBytes += content.size
                require(expandedBytes <= 512L * 1024L * 1024L) { "MCFPP library exceeds the expanded-size limit" }
                require(entries.put(name, content) == null) { "Duplicate MCFPP library entry: $name" }
            }
        }
        val binary = entries.remove("bin.mclib")
            ?: throw IllegalArgumentException("MCFPP library is missing bin.mclib")
        val module = entries.remove("module.json")
            ?: throw IllegalArgumentException("MCFPP library is missing module.json")
        return McfppLibraryPayload(packageId, classifier, binary, module, entries)
    }

    private fun InputStream.readLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= 256L * 1024L * 1024L) { "MCFPP library entry exceeds the safety limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

public class McfppDependencyAdapter(
    private val fetched: FetchedGraph,
) {
    public fun libraries(): McfpmResult<List<McfppLibraryPayload>> {
        val libraries = mutableListOf<McfppLibraryPayload>()
        return runCatching {
            fetched.graph.loadOrder
                .filter { it.type == PayloadType.MCFPP_LIBRARY }
                .forEach { reference ->
                    val path = fetched.artifacts[reference]
                        ?: throw IllegalArgumentException("Fetched graph is missing $reference")
                    Files.newInputStream(path).use { input ->
                        libraries += McfppLibraryStreamLoader.load(reference.packageId, reference.classifier, input)
                    }
                }
            libraries.toList()
        }.fold(
            onSuccess = { McfpmResult.Success(it) },
            onFailure = {
                McfpmResult.Failure(
                    listOf(
                        Diagnostic(
                            DiagnosticCode.INTEGRITY_FAILURE,
                            DiagnosticSeverity.ERROR,
                            "Unable to load MCFPP library: ${it.message}",
                        ),
                    ),
                )
            },
        )
    }

    public fun pairedResourcePacks(): Map<PayloadRef, Path> = fetched.artifacts.filterKeys {
        it.type == PayloadType.MINECRAFT_RESOURCEPACK
    }
}

public class TrustedMcfppPluginLoader(
    private val trustStore: TrustStore,
) {
    public fun load(fetched: FetchedGraph): McfpmResult<List<URLClassLoader>> {
        when (val verified = ArtifactVerifier(trustStore).verify(fetched)) {
            is McfpmResult.Failure -> return verified
            is McfpmResult.Success -> Unit
        }
        val loaders = fetched.graph.loadOrder
            .filter { it.type == PayloadType.JVM_PLUGIN }
            .map { reference ->
                val path = requireNotNull(fetched.artifacts[reference]) { "Fetched graph is missing $reference" }
                URLClassLoader(arrayOf(path.toUri().toURL()), ClassLoader.getPlatformClassLoader())
            }
        return McfpmResult.Success(loaders)
    }
}

public data class RegisteredMcfppOutput(
    public val descriptor: ArtifactDescriptor,
    public val bytes: ByteArray,
)

public object McfppOutputRegistrar {
    public fun registerDirectory(
        type: PayloadType,
        classifier: String,
        directory: Path,
        executable: Boolean = false,
    ): RegisteredMcfppOutput {
        val bytes = ReproducibleZip.fromDirectory(directory)
        ReproducibleZip.verify(
            bytes,
            requirePackMetadata = type == PayloadType.MINECRAFT_DATAPACK || type == PayloadType.MINECRAFT_RESOURCEPACK,
        )
        return RegisteredMcfppOutput(
            ArtifactDescriptor(
                type = type,
                classifier = classifier,
                sha256 = Hashing.sha256(bytes),
                size = bytes.size.toLong(),
                executable = executable,
            ),
            bytes,
        )
    }
}

public object McfppProjectLocator {
    public fun manifestBeside(mcfppJson: Path): Path =
        mcfppJson.toAbsolutePath().normalize().parent.resolve("mcfpm.toml")
}
