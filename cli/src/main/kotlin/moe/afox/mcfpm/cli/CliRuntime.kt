package moe.afox.mcfpm.cli

import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import java.io.PrintWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import picocli.CommandLine

internal enum class CliExitCode(val value: Int) {
    SUCCESS(0),
    ARGUMENT(2),
    RESOLUTION(3),
    INTEGRITY(4),
    NETWORK(5),
    PUBLISH(6),
    DEPLOYMENT(7),
    INTERNAL(70),
}

internal object CliOutput {
    fun success(root: McfpmCommand, command: String, data: JsonElement, human: String): Int {
        if (root.json) {
            root.out().println(
                canonical(
                    JsonObject(
                        linkedMapOf(
                            "schema" to JsonPrimitive(1),
                            "ok" to JsonPrimitive(true),
                            "command" to JsonPrimitive(command),
                            "data" to data,
                        ),
                    ),
                ),
            )
        } else if (human.isNotEmpty()) {
            root.out().println(human)
        }
        return CliExitCode.SUCCESS.value
    }

    fun failure(root: McfpmCommand, diagnostics: List<Diagnostic>): Int {
        val exit = exitCode(diagnostics)
        if (root.json) {
            val errors = diagnostics.map { diagnostic ->
                JsonObject(
                    linkedMapOf(
                        "code" to JsonPrimitive(diagnostic.code.stableCode),
                        "severity" to JsonPrimitive(diagnostic.severity.name.lowercase()),
                        "message" to JsonPrimitive(diagnostic.message),
                        "context" to JsonObject(
                            diagnostic.context.toSortedMap().mapValues { JsonPrimitive(it.value) },
                        ),
                    ),
                )
            }
            root.out().println(
                canonical(
                    JsonObject(
                        linkedMapOf(
                            "schema" to JsonPrimitive(1),
                            "ok" to JsonPrimitive(false),
                            "exitCode" to JsonPrimitive(exit),
                            "diagnostics" to JsonArray(errors),
                        ),
                    ),
                ),
            )
        } else {
            diagnostics.forEach { diagnostic ->
                val context = if (diagnostic.context.isEmpty()) "" else
                    " (${diagnostic.context.toSortedMap().entries.joinToString { "${it.key}=${it.value}" }})"
                root.err().println("${diagnostic.code.stableCode}: ${diagnostic.message}$context")
            }
        }
        return exit
    }

    fun argumentFailure(root: McfpmCommand, message: String): Int = failure(
        root,
        listOf(Diagnostic(DiagnosticCode.INVALID_ARGUMENT, DiagnosticSeverity.ERROR, message)),
    )

    fun exception(root: McfpmCommand, exception: Throwable): Int = failure(
        root,
        listOf(
            Diagnostic(
                DiagnosticCode.INTERNAL_ERROR,
                DiagnosticSeverity.ERROR,
                exception.message ?: exception::class.simpleName.orEmpty(),
            ),
        ),
    )

    private fun canonical(element: JsonElement): String = CanonicalJson.format
        .encodeToString(JsonElement.serializer(), element)

    private fun exitCode(diagnostics: List<Diagnostic>): Int {
        val codes = diagnostics.map(Diagnostic::code).toSet()
        return when {
            codes.any { it == DiagnosticCode.DEPLOYMENT_FAILED || it.name.startsWith("INSTALL_") || it == DiagnosticCode.USER_DRIFT } ->
                CliExitCode.DEPLOYMENT.value
            DiagnosticCode.PUBLISH_VALIDATION_FAILED in codes -> CliExitCode.PUBLISH.value
            DiagnosticCode.NETWORK_FAILURE in codes || DiagnosticCode.OFFLINE_MISS in codes -> CliExitCode.NETWORK.value
            codes.any { it == DiagnosticCode.INTEGRITY_FAILURE || it == DiagnosticCode.UNTRUSTED_EXECUTABLE } ->
                CliExitCode.INTEGRITY.value
            codes.any { it == DiagnosticCode.RESOLUTION_FAILED || it == DiagnosticCode.DEPENDENCY_CYCLE || it == DiagnosticCode.REPOSITORY_VIOLATION } ->
                CliExitCode.RESOLUTION.value
            DiagnosticCode.INTERNAL_ERROR in codes -> CliExitCode.INTERNAL.value
            else -> CliExitCode.ARGUMENT.value
        }
    }
}

internal fun <T> McfpmResult<T>.foldCli(
    root: McfpmCommand,
    success: (T) -> Int,
): Int = when (this) {
    is McfpmResult.Success -> success(value)
    is McfpmResult.Failure -> CliOutput.failure(root, diagnostics)
}

internal fun atomicWrite(path: Path, bytes: ByteArray) {
    val absolute = path.toAbsolutePath().normalize()
    Files.createDirectories(requireNotNull(absolute.parent))
    val temporary = absolute.resolveSibling("${absolute.fileName}.${UUID.randomUUID()}.part")
    try {
        Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal abstract class CliCallable : java.util.concurrent.Callable<Int> {
    @CommandLine.Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    protected val root: McfpmCommand
        get() = spec.root().userObject() as McfpmCommand

    final override fun call(): Int = try {
        execute()
    } catch (exception: CliDiagnosticException) {
        CliOutput.failure(root, exception.diagnostics)
    } catch (exception: IllegalArgumentException) {
        CliOutput.argumentFailure(root, exception.message ?: "Invalid argument")
    } catch (exception: Exception) {
        CliOutput.exception(root, exception)
    }

    protected abstract fun execute(): Int
}

internal class CliDiagnosticException(
    val diagnostics: List<Diagnostic>,
) : RuntimeException(diagnostics.joinToString { it.message })

internal fun PrintWriter.flushLine(value: String) {
    println(value)
    flush()
}

internal class CliProgress(
    private val root: McfpmCommand,
    private val total: Int,
) {
    private var current: Int = 0

    init {
        require(total > 0) { "Progress must contain at least one stage" }
    }

    fun stage(message: String) {
        current++
        require(current <= total) { "Progress advanced beyond $total stages" }
        if (!root.json) root.err().flushLine("[$current/$total] $message...")
    }
}
