package moe.afox.mcfpm.minecraft

import moe.afox.mcfpm.core.InstallContext
import moe.afox.mcfpm.core.InstallContextDetector
import moe.afox.mcfpm.core.InstallContextKind
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

public class MinecraftInstallContextDetector : InstallContextDetector {
    override fun detect(
        workingDirectory: Path,
        forcedKind: InstallContextKind?,
    ): McfpmResult<InstallContext> {
        val start = canonicalDirectory(workingDirectory)
            ?: return failure(DiagnosticCode.UNKNOWN_INSTALL_CONTEXT, "Working directory does not exist: $workingDirectory")
        val special = specialDirectoryContext(start)
        if (special != null && (forcedKind == null || special.kind == forcedKind)) {
            return McfpmResult.Success(withInstanceInference(special))
        }

        var current: Path? = start
        while (current != null) {
            val candidates = markerContexts(current)
            val selected = if (forcedKind == null) candidates else candidates.filter { it.kind == forcedKind }
            if (forcedKind == null && candidates.size > 1) {
                return failure(
                    DiagnosticCode.AMBIGUOUS_INSTALL_CONTEXT,
                    "Conflicting install context markers at $current; use --context project|world|instance",
                    mapOf("markers" to candidates.joinToString { it.kind.name.lowercase() }),
                )
            }
            if (selected.isNotEmpty()) return McfpmResult.Success(withInstanceInference(selected.single()))
            current = current.parent
        }
        return failure(
            DiagnosticCode.UNKNOWN_INSTALL_CONTEXT,
            if (forcedKind == null) {
                "No Mcfpm project, Minecraft world, or Minecraft instance context was found"
            } else {
                "No ${forcedKind.name.lowercase()} context was found"
            },
            mapOf("start" to start.toString()),
        )
    }

    override fun explicit(kind: InstallContextKind, root: Path): McfpmResult<InstallContext> {
        val canonical = canonicalDirectory(root)
            ?: return failure(DiagnosticCode.UNKNOWN_INSTALL_CONTEXT, "Explicit context directory does not exist: $root")
        val context = markerContexts(canonical).singleOrNull { it.kind == kind }
            ?: specialDirectoryContext(canonical)?.takeIf { it.kind == kind }
            ?: return failure(
                DiagnosticCode.UNKNOWN_INSTALL_CONTEXT,
                "Explicit path is not a valid ${kind.name.lowercase()} context: $canonical",
            )
        return McfpmResult.Success(withInstanceInference(context))
    }

    public fun inferOwningInstance(world: Path): Path? {
        val canonicalWorld = canonicalDirectory(world) ?: return null
        val saves = canonicalWorld.parent ?: return null
        if (saves.fileName?.toString() != "saves") return null
        val instance = saves.parent ?: return null
        if (markerContexts(instance).none { it.kind == InstallContextKind.INSTANCE }) return null
        val expected = runCatching {
            instance.resolve("saves").resolve(canonicalWorld.fileName).toRealPath(LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        return instance.takeIf { expected == canonicalWorld }
    }

    private fun specialDirectoryContext(directory: Path): InstallContext? {
        val name = directory.fileName?.toString() ?: return null
        val parent = directory.parent ?: return null
        return when (name) {
            "datapacks" -> if (Files.isRegularFile(parent.resolve("level.dat"))) {
                InstallContext(
                    InstallContextKind.WORLD,
                    parent,
                    listOf("$directory is a datapacks directory whose parent contains level.dat"),
                )
            } else {
                null
            }
            "resourcepacks" -> when {
                Files.isRegularFile(parent.resolve("level.dat")) -> InstallContext(
                    InstallContextKind.WORLD,
                    parent,
                    listOf("$directory is a world resourcepacks directory"),
                )
                isInstance(parent) -> InstallContext(
                    InstallContextKind.INSTANCE,
                    parent,
                    listOf("$directory is an instance resourcepacks directory"),
                )
                else -> null
            }
            else -> null
        }
    }

    private fun markerContexts(directory: Path): List<InstallContext> = buildList {
        if (Files.isRegularFile(directory.resolve("mcfpm.toml"), LinkOption.NOFOLLOW_LINKS)) {
            add(InstallContext(InstallContextKind.PROJECT, directory, listOf("$directory/mcfpm.toml")))
        }
        if (Files.isRegularFile(directory.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)) {
            add(InstallContext(InstallContextKind.WORLD, directory, listOf("$directory/level.dat")))
        }
        if (isInstance(directory)) {
            val secondary = if (Files.isDirectory(directory.resolve("resourcepacks"))) "resourcepacks" else "saves"
            add(
                InstallContext(
                    InstallContextKind.INSTANCE,
                    directory,
                    listOf("$directory/options.txt", "$directory/$secondary"),
                ),
            )
        }
    }

    private fun isInstance(directory: Path): Boolean =
        Files.isRegularFile(directory.resolve("options.txt"), LinkOption.NOFOLLOW_LINKS) &&
            (Files.isDirectory(directory.resolve("resourcepacks"), LinkOption.NOFOLLOW_LINKS) ||
                Files.isDirectory(directory.resolve("saves"), LinkOption.NOFOLLOW_LINKS))

    private fun withInstanceInference(context: InstallContext): InstallContext =
        if (context.kind == InstallContextKind.WORLD) {
            context.copy(inferredInstance = inferOwningInstance(context.root))
        } else {
            context
        }

    private fun canonicalDirectory(path: Path): Path? = runCatching {
        path.toRealPath(LinkOption.NOFOLLOW_LINKS).takeIf {
            Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)
        }
    }.getOrNull()

    private fun <T> failure(
        code: DiagnosticCode,
        message: String,
        context: Map<String, String> = emptyMap(),
    ): McfpmResult<T> = McfpmResult.Failure(
        listOf(Diagnostic(code, DiagnosticSeverity.ERROR, message, context)),
    )
}
