package moe.afox.mcfpm.cli

import java.io.PrintWriter
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import picocli.CommandLine

@CommandLine.Command(
    name = "mcfpm",
    description = ["Independent package manager for Minecraft function projects."],
    mixinStandardHelpOptions = true,
    versionProvider = McfpmVersionProvider::class,
    subcommands = [
        InitCommand::class,
        AddCommand::class,
        RemoveCommand::class,
        UpdateCommand::class,
        ResolveCommand::class,
        FetchCommand::class,
        InstallCommand::class,
        TreeCommand::class,
        WhyCommand::class,
        SearchCommand::class,
        InfoCommand::class,
        ManifestCommand::class,
        ArtifactCommand::class,
        PackCommand::class,
        VerifyCommand::class,
        BundleCommand::class,
        PublishCommand::class,
        ImportCommand::class,
        DeployCommand::class,
        RollbackCommand::class,
        CacheCommand::class,
        TrustCommand::class,
        AuthCommand::class,
        ConfigCommand::class,
        DoctorCommand::class,
        CompletionCommand::class,
    ],
)
public class McfpmCommand(
    internal val workingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
) : Callable<Int> {
    @CommandLine.Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    @CommandLine.Option(names = ["--json"], description = ["Emit schema-v1 JSON only on stdout."], scope = CommandLine.ScopeType.INHERIT)
    internal var json: Boolean = false

    @CommandLine.Option(names = ["--offline"], description = ["Forbid network access for artifact retrieval."], scope = CommandLine.ScopeType.INHERIT)
    internal var offline: Boolean = false

    @CommandLine.Option(names = ["--yes", "-y"], description = ["Confirm non-interactive changes."], scope = CommandLine.ScopeType.INHERIT)
    internal var yes: Boolean = false

    @CommandLine.Option(names = ["--dry-run"], description = ["Plan mutations without writing target files."], scope = CommandLine.ScopeType.INHERIT)
    internal var dryRun: Boolean = false

    @CommandLine.Option(names = ["--cache-dir"], paramLabel = "PATH", scope = CommandLine.ScopeType.INHERIT)
    internal var cacheDirectory: Path = Path.of(System.getProperty("user.home"), ".mcfpm", "cache")

    @CommandLine.Option(names = ["--trust-store"], paramLabel = "PATH", scope = CommandLine.ScopeType.INHERIT)
    internal var trustStore: Path = Path.of(System.getProperty("user.home"), ".mcfpm", "trust.toml")

    @CommandLine.Option(
        names = ["--repository-url"],
        paramLabel = "ID=URI|HTTPS_URL",
        description = ["Declare a Maven repository; import publish also accepts one HTTPS_URL. May be repeated."],
        scope = CommandLine.ScopeType.INHERIT,
    )
    internal var repositories: MutableList<String> = mutableListOf()

    @CommandLine.Option(names = ["--default-repository"], paramLabel = "ID", scope = CommandLine.ScopeType.INHERIT)
    internal var defaultRepository: String? = null

    @CommandLine.Option(
        names = ["--bind"],
        paramLabel = "GROUP=REPOSITORY",
        description = ["Bind a package group to one repository. May be repeated."],
        scope = CommandLine.ScopeType.INHERIT,
    )
    internal var bindings: MutableList<String> = mutableListOf()

    override fun call(): Int {
        spec.commandLine().usage(out())
        return CliExitCode.SUCCESS.value
    }

    internal fun out(): PrintWriter = spec.commandLine().out
    internal fun err(): PrintWriter = spec.commandLine().err
}

public class McfpmVersionProvider : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("mcfpm ${mcfpmVersion()}")
}

internal fun mcfpmVersion(): String =
    McfpmCommand::class.java.`package`.implementationVersion ?: "0.1.0-SNAPSHOT"

public fun runMcfpm(
    args: Array<String>,
    workingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
    out: PrintWriter = PrintWriter(System.out, true),
    err: PrintWriter = PrintWriter(System.err, true),
): Int {
    val command = McfpmCommand(workingDirectory)
    val commandLine = CommandLine(command)
        .setOut(out)
        .setErr(err)
        .setCaseInsensitiveEnumValuesAllowed(true)
    commandLine.parameterExceptionHandler = CommandLine.IParameterExceptionHandler { exception, _ ->
        command.json = args.contains("--json")
        if (!command.json) {
            exception.commandLine.err.println(exception.message)
            exception.commandLine.usage(exception.commandLine.err)
        }
        CliOutput.argumentFailure(command, exception.message ?: "Invalid command line")
    }
    commandLine.executionExceptionHandler = CommandLine.IExecutionExceptionHandler { exception, _, _ ->
        CliOutput.exception(command, exception)
    }
    if (args.contains("--json") && args.any { it == "--help" || it == "-h" }) {
        command.json = true
        var selected = commandLine
        args.forEach { argument -> selected.subcommands[argument]?.let { selected = it } }
        return CliOutput.success(
            command,
            "help",
            JsonObject(
                mapOf(
                    "command" to JsonPrimitive(selected.commandName),
                    "usage" to JsonPrimitive(selected.getUsageMessage(CommandLine.Help.Ansi.OFF)),
                ),
            ),
            "",
        )
    }
    val nonJsonArguments = args.filterNot { it == "--json" }
    if (args.contains("--json") && nonJsonArguments == listOf("--version")) {
        command.json = true
        return CliOutput.success(
            command,
            "version",
            JsonObject(mapOf("version" to JsonPrimitive(mcfpmVersion()))),
            "",
        )
    }
    return commandLine.execute(*args)
}

public fun main(args: Array<String>): Unit = kotlin.system.exitProcess(runMcfpm(args))
