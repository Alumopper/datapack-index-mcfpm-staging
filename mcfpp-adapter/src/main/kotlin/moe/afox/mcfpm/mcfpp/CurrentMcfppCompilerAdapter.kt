package moe.afox.mcfpm.mcfpp

import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * Runtime bridge for the current MCFPP compiler ABI.
 *
 * MCFPP currently publishes only mutable snapshots compiled for Java 21. Keeping the bridge
 * reflective lets the Mcfpm SDK retain Java 17 bytecode while still attaching verified libraries
 * to MCFPP's READ_LIB stage. An incompatible compiler is reported as a stable Mcfpm diagnostic.
 */
public class CurrentMcfppCompilerAdapter(
    private val compilerClassLoader: ClassLoader = Thread.currentThread().contextClassLoader,
) {
    public fun compile(
        mcfppJson: Path,
        libraries: List<McfppLibraryPayload>,
    ): McfpmResult<McfppCompilationResult> = synchronized(compilerClassLoader) {
        runCatching {
            require(Files.isRegularFile(mcfppJson)) { "MCFPP configuration does not exist: $mcfppJson" }
            val api = CurrentMcfppApi.resolve(compilerClassLoader)
            val session = LibrarySession(api, libraries)
            api.readStageHooks.add(session.hook)
            try {
                val configuration = api.readConfig.invoke(api.project, mcfppJson.toAbsolutePath().normalize().toString())
                api.compile.invoke(null, configuration)
                val errors = (api.errorCount.invoke(api.project) as Number).toInt()
                require(errors == 0) { "MCFPP compilation completed with $errors error(s)" }
                McfppCompilationResult(libraries.size, session.loadedModules, errors)
            } finally {
                api.readStageHooks.remove(session.hook)
                session.close()
            }
        }.fold(
            onSuccess = { McfpmResult.Success(it) },
            onFailure = { failure ->
                McfpmResult.Failure(
                    listOf(
                        Diagnostic(
                            DiagnosticCode.MCFPP_INTEGRATION_FAILED,
                            DiagnosticSeverity.ERROR,
                            "MCFPP compiler integration failed: ${failure.rootMessage()}",
                        ),
                    ),
                )
            },
        )
    }
}

public data class McfppCompilationResult(
    public val loadedLibraries: Int,
    public val loadedModules: Int,
    public val compilerErrors: Int,
)

private class LibrarySession(
    private val api: CurrentMcfppApi,
    private val libraries: List<McfppLibraryPayload>,
) : AutoCloseable {
    private val resourceRoots = mutableListOf<Path>()
    private val registeredModules = mutableListOf<Any>()
    var loadedModules: Int = 0
        private set

    val hook: () -> Unit = {
        libraries.forEach(::load)
        loadedModules = registeredModules.size
    }

    private fun load(library: McfppLibraryPayload) {
        api.readLibrary.invoke(api.libraryReader, ByteArrayInputStream(library.binaryLibrary))
        val json = api.parseJson.invoke(null, library.moduleJson.decodeToString())
        @Suppress("UNCHECKED_CAST")
        val modules = api.readModules.invoke(api.moduleCompanion, json) as Iterable<Any>
        val resourceRoot = materializeResources(library)
        modules.forEach { module ->
            api.setModuleType.invoke(module, api.directoryModuleType)
            api.setModuleResourcePath.invoke(module, resourceRoot)
            api.projectModules.add(module)
            registeredModules.add(module)
        }
    }

    private fun materializeResources(library: McfppLibraryPayload): Path {
        val root = Files.createTempDirectory("mcfpm-mcfpp-resources-")
        resourceRoots.add(root)
        library.moduleResources.toSortedMap().forEach { (entryName, bytes) ->
            val safeName = ReproducibleZip.validateEntryName(entryName)
            val target = root.resolve(safeName).normalize()
            require(target.startsWith(root)) { "MCFPP module resource escapes its sandbox: $entryName" }
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
        return root
    }

    override fun close() {
        api.projectModules.removeAll(registeredModules.toSet())
        resourceRoots.asReversed().forEach { root ->
            if (Files.exists(root)) {
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }
}

private data class CurrentMcfppApi(
    val project: Any,
    val readConfig: Method,
    val compile: Method,
    val errorCount: Method,
    val readStageHooks: MutableList<Any>,
    val libraryReader: Any,
    val readLibrary: Method,
    val parseJson: Method,
    val moduleCompanion: Any,
    val readModules: Method,
    val directoryModuleType: Any,
    val setModuleType: Method,
    val setModuleResourcePath: Method,
    val projectModules: MutableList<Any>,
) {
    companion object {
        fun resolve(loader: ClassLoader): CurrentMcfppApi {
            val projectClass = loader.loadClass("top.mcfpp.Project")
            val project = projectClass.getField("INSTANCE").get(null)
            val stages = projectClass.getMethod("getStageProcessor").invoke(project) as Array<*>
            val compileStageClass = loader.loadClass("top.mcfpp.Project\$CompileStage")
            val readStage = requireNotNull(compileStageClass.enumConstants.firstOrNull {
                (it as Enum<*>).name == "READ_LIB"
            }) { "MCFPP READ_LIB stage is unavailable" } as Enum<*>
            @Suppress("UNCHECKED_CAST")
            val readStageHooks = stages[readStage.ordinal] as MutableList<Any>

            val libraryReaderClass = loader.loadClass("top.mcfpp.io.LibBinReader")
            val libraryReader = libraryReaderClass.getField("INSTANCE").get(null)
            val jsonClass = loader.loadClass("com.alibaba.fastjson2.JSONObject")
            val moduleClass = loader.loadClass("top.mcfpp.util.Module")
            val moduleCompanion = moduleClass.getField("Companion").get(null)
            val moduleTypeClass = loader.loadClass("top.mcfpp.util.ModuleType")
            val directoryType = requireNotNull(moduleTypeClass.enumConstants.firstOrNull {
                (it as Enum<*>).name == "DIR"
            }) { "MCFPP directory module type is unavailable" }
            @Suppress("UNCHECKED_CAST")
            val modules = projectClass.getMethod("getModules").invoke(project) as MutableList<Any>
            val compile = loader.loadClass("top.mcfpp.MCFPPKt").methods.singleOrNull {
                it.name == "compile" && it.parameterCount == 1
            } ?: error("MCFPP compile(ProjectConfig) entry point is unavailable")

            return CurrentMcfppApi(
                project = project,
                readConfig = projectClass.getMethod("readConfig", String::class.java),
                compile = compile,
                errorCount = projectClass.getMethod("getErrorCount"),
                readStageHooks = readStageHooks,
                libraryReader = libraryReader,
                readLibrary = libraryReaderClass.getMethod("readFromStream", java.io.InputStream::class.java),
                parseJson = jsonClass.getMethod("parse", String::class.java),
                moduleCompanion = moduleCompanion,
                readModules = moduleCompanion.javaClass.getMethod("fromJson", jsonClass),
                directoryModuleType = directoryType,
                setModuleType = moduleClass.getMethod("setType", moduleTypeClass),
                setModuleResourcePath = moduleClass.getMethod("setResourcePath", Path::class.java),
                projectModules = modules,
            )
        }
    }
}

private fun Throwable.rootMessage(): String {
    var current = this
    while (current is InvocationTargetException && current.targetException != null) {
        current = current.targetException
    }
    return current.message ?: current::class.java.simpleName
}
