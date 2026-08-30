@file:JvmName("MCFPPKt")

package top.mcfpp

import java.nio.file.Files
import java.nio.file.Path
import top.mcfpp.util.Module

public data class ProjectConfig(public val source: Path)

public object Project {
    public enum class CompileStage {
        PRE_INIT,
        INIT,
        READ_LIB,
        INDEX_TYPE,
        RESOLVE_FIELD,
        RUN_ANNOTATION,
        COMPILE,
        OPTIMIZATION,
        GEN_INDEX,
        GEN_DATAPACK,
    }

    public val stageProcessor: Array<ArrayList<() -> Unit>> =
        Array(CompileStage.entries.size) { arrayListOf() }
    public var modules: ArrayList<Module> = arrayListOf()
    public var errorCount: Int = 0
    public val compiledModuleIds: MutableList<String> = mutableListOf()
    public val compiledResources: MutableList<String> = mutableListOf()
    public val observedResourceRoots: MutableList<Path> = mutableListOf()

    public fun readConfig(path: String): ProjectConfig = ProjectConfig(Path.of(path))

    public fun reset() {
        stageProcessor.forEach(MutableList<() -> Unit>::clear)
        modules.clear()
        errorCount = 0
        compiledModuleIds.clear()
        compiledResources.clear()
        observedResourceRoots.clear()
    }
}

public fun compile(config: ProjectConfig) {
    require(Files.isRegularFile(config.source))
    Project.stageProcessor[Project.CompileStage.READ_LIB.ordinal].forEach { it() }
    Project.modules.forEach { module ->
        Project.compiledModuleIds.add(module.id)
        Project.observedResourceRoots.add(module.resourcePath)
        Project.compiledResources.add(
            Files.readString(module.resourcePath.resolve(module.id).resolve("data/value.txt")),
        )
    }
}
