package moe.afox.mcfpm.cli

import moe.afox.mcfpm.core.ArtifactGraphFetcher
import moe.afox.mcfpm.core.ArtifactVerifier
import moe.afox.mcfpm.core.FetchedGraph
import moe.afox.mcfpm.core.InstallContext
import moe.afox.mcfpm.core.InstallContextKind
import moe.afox.mcfpm.core.LockfileCodec
import moe.afox.mcfpm.core.PubGrubResolver
import moe.afox.mcfpm.core.ResolveRequest
import moe.afox.mcfpm.core.RootRequirement
import moe.afox.mcfpm.core.TrustGrant
import moe.afox.mcfpm.minecraft.MinecraftInstallEngine
import moe.afox.mcfpm.minecraft.MinecraftInstallPlan
import moe.afox.mcfpm.minecraft.MinecraftInstallRequest
import moe.afox.mcfpm.minecraft.MinecraftInstallResult
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.ConsumerProfile
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.ResolvedGraph
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import picocli.AutoComplete
import picocli.CommandLine

internal data class InstallOptions(
    val lockfile: Path?,
    val forcedKind: InstallContextKind?,
    val project: Path?,
    val world: Path?,
    val instance: Path?,
    val force: Boolean,
)

@CommandLine.Command(
    name = "install",
    description = ["Add dependencies, then fetch and install according to the nearest valid context."],
    mixinStandardHelpOptions = true,
)
internal class InstallCommand : CliCallable() {
    @CommandLine.Parameters(arity = "0..*", paramLabel = "GROUP:NAME@REQUIREMENT")
    private var dependencies: MutableList<String> = mutableListOf()

    @CommandLine.Option(names = ["--lock"], paramLabel = "PATH")
    private var lockfile: Path? = null

    @CommandLine.Option(names = ["--context"])
    private var context: InstallContextKind? = null

    @CommandLine.Option(names = ["--project"], paramLabel = "PATH")
    private var project: Path? = null

    @CommandLine.Option(names = ["--world"], paramLabel = "PATH")
    private var world: Path? = null

    @CommandLine.Option(names = ["--instance"], paramLabel = "PATH")
    private var instance: Path? = null

    @CommandLine.Option(names = ["--force"])
    private var force: Boolean = false

    override fun execute(): Int {
        require(dependencies.isEmpty() || (world == null && instance == null)) {
            "Dependency coordinates can be installed only into a project; omit --world/--instance and deploy after resolving"
        }
        val selectedProject = project?.let { path ->
            (if (path.isAbsolute) path else root.workingDirectory.resolve(path)).toAbsolutePath().normalize()
        }
        val deploymentContext = when (
            val detected = coordinateDeploymentContext(root, dependencies, selectedProject, context)
        ) {
            is McfpmResult.Success -> detected.value
            is McfpmResult.Failure -> return CliOutput.failure(root, detected.diagnostics)
        }
        val projectEdit = prepareInstallProject(root, dependencies, selectedProject)
        val options = if (projectEdit == null) {
            InstallOptions(lockfile, context, selectedProject, world, instance, force)
        } else {
            InstallOptions(lockfile, InstallContextKind.PROJECT, projectEdit.projectRoot, null, null, force)
        }
        return runInstall(
            root,
            options,
            explicitDeployment = false,
            projectEdit = projectEdit,
            deploymentContext = deploymentContext,
        )
    }
}

@CommandLine.Command(name = "deploy", description = ["Deploy explicitly to a world or Minecraft instance."])
internal class DeployCommand : CliCallable() {
    @CommandLine.Option(names = ["--lock"], paramLabel = "PATH")
    private var lockfile: Path? = null

    @CommandLine.Option(names = ["--context"])
    private var context: InstallContextKind? = null

    @CommandLine.Option(names = ["--world"], paramLabel = "PATH")
    private var world: Path? = null

    @CommandLine.Option(names = ["--instance"], paramLabel = "PATH")
    private var instance: Path? = null

    @CommandLine.Option(names = ["--force"])
    private var force: Boolean = false

    override fun execute(): Int {
        require(world != null || instance != null) { "deploy requires --world or --instance" }
        return runInstall(
            root,
            InstallOptions(lockfile, context, null, world, instance, force),
            explicitDeployment = true,
        )
    }
}

private fun runInstall(
    root: McfpmCommand,
    options: InstallOptions,
    explicitDeployment: Boolean,
    projectEdit: InstallProjectEdit? = null,
    deploymentContext: InstallContext? = null,
): Int {
    val services = McfpmServices(root)
    val context = selectContext(services, root, options, explicitDeployment)
        .let { result ->
            when (result) {
                is McfpmResult.Success -> result.value
                is McfpmResult.Failure -> return CliOutput.failure(root, result.diagnostics)
            }
        }
    val installContext = deploymentContext ?: context
    val progress = CliProgress(root, if (installContext.kind == InstallContextKind.PROJECT) 3 else 4)
    val lockPath = resolveInstallLock(root, context, options.lockfile)
    val graph = if (Files.isRegularFile(lockPath) && projectEdit == null) {
        progress.stage("Reading ${lockPath.fileName}")
        LockfileCodec.decode(Files.readAllBytes(lockPath))
    } else if (context.kind == InstallContextKind.PROJECT) {
        progress.stage("Resolving dependencies")
        val manifestPath = context.root.resolve("mcfpm.toml")
        val manifest = services.loadManifest(manifestPath)
        val request = ResolveRequest(
            manifest.dependencies.filterNot { it.optional }.map { RootRequirement(it.packageId, it.requirement, it.features) },
            ConsumerProfile.ALL,
            manifest.minecraft,
        )
        when (val resolved = PubGrubResolver(services.configuredRegistry()).resolve(request)) {
            is McfpmResult.Success -> resolved.value.also {
                if (!root.dryRun) atomicWrite(lockPath, LockfileCodec.encode(it))
            }
            is McfpmResult.Failure -> return CliOutput.failure(root, resolved.diagnostics)
        }
    } else {
        return CliOutput.argumentFailure(root, "No mcfpm.lock was found; pass --lock for world or instance deployment")
    }
    if (context.kind == InstallContextKind.PROJECT) {
        val manifest = services.loadManifest(context.root.resolve("mcfpm.toml"))
        when (val validated = services.validateLockMatchesManifest(manifest, graph)) {
            is McfpmResult.Failure -> return CliOutput.failure(root, validated.diagnostics)
            is McfpmResult.Success -> Unit
        }
    }
    val registry = services.lockedRegistry(graph)
    val artifactCount = graph.packages.sumOf { it.artifacts.size }
    progress.stage("Fetching $artifactCount artifact(s)")
    val fetched = when (val result = ArtifactGraphFetcher(registry, services.cache).fetch(graph, root.offline)) {
        is McfpmResult.Success -> result.value
        is McfpmResult.Failure -> return CliOutput.failure(root, result.diagnostics)
    }
    progress.stage("Verifying $artifactCount artifact(s)")
    val verified = when (val result = ArtifactVerifier(services.trustStore).verify(fetched)) {
        is McfpmResult.Success -> result.value
        is McfpmResult.Failure -> return CliOutput.failure(root, result.diagnostics)
    }

    if (installContext.kind == InstallContextKind.PROJECT) {
        return CliOutput.success(
            root,
            if (explicitDeployment) "deploy" else "install",
            JsonObject(
                mapOf(
                    "context" to JsonPrimitive("project"),
                    "root" to JsonPrimitive(context.root.toString()),
                    "artifacts" to JsonPrimitive(verified.artifacts.size),
                    "mutated" to JsonPrimitive(false),
                    "manifestCreated" to JsonPrimitive(projectEdit?.manifestCreated ?: false),
                    "dependenciesAdded" to JsonArray(
                        projectEdit?.dependencies.orEmpty().map { JsonPrimitive(it.value) },
                    ),
                ),
            ),
            buildString {
                if (projectEdit?.manifestCreated == true) append("Created ${context.root.resolve("mcfpm.toml")}; ")
                if (!projectEdit?.dependencies.isNullOrEmpty()) {
                    append("added ${projectEdit.dependencies.size} direct dependency/dependencies; ")
                }
                append("resolved, downloaded, and verified ${verified.artifacts.size} artifact(s); no Minecraft files were written")
            },
        )
    }

    progress.stage("Installing into ${installContext.kind.name.lowercase()} ${installContext.root}")
    val engine = MinecraftInstallEngine()
    var confirmed = root.yes
    val request = MinecraftInstallRequest(
        fetched = verified,
        context = installContext,
        explicitWorld = options.world,
        explicitInstance = options.instance,
        confirmedGlobalResourcePackImpact = confirmed,
        dryRun = root.dryRun,
        force = options.force,
    )
    val plan = when (val result = engine.plan(request)) {
        is McfpmResult.Success -> result.value
        is McfpmResult.Failure -> return CliOutput.failure(root, result.diagnostics)
    }
    if (plan.requiresGlobalResourcePackConfirmation && !confirmed && !root.dryRun) {
        if (root.json || System.getenv("CI") != null || System.console() == null) {
            return CliOutput.failure(
                root,
                listOf(
                    Diagnostic(
                        DiagnosticCode.INSTALL_CONFIRMATION_REQUIRED,
                        DiagnosticSeverity.ERROR,
                        "Installing resource packs into the inferred instance affects every world; pass --yes",
                    ),
                ),
            )
        }
        root.err().print("Resource packs will be enabled for every world in ${plan.instance}. Continue? [y/N] ")
        root.err().flush()
        confirmed = System.console().readLine()?.trim()?.lowercase() in setOf("y", "yes")
        if (!confirmed) return CliOutput.argumentFailure(root, "Installation cancelled")
    }
    val confirmedRequest = request.copy(confirmedGlobalResourcePackImpact = confirmed)
    return engine.install(confirmedRequest).foldCli(root) { result ->
        installSuccess(root, if (explicitDeployment) "deploy" else "install", result, projectEdit)
    }
}

private fun coordinateDeploymentContext(
    root: McfpmCommand,
    dependencies: List<String>,
    explicitProject: Path?,
    forcedKind: InstallContextKind?,
): McfpmResult<InstallContext?> {
    if (dependencies.isEmpty() || explicitProject != null || forcedKind == InstallContextKind.PROJECT) {
        return McfpmResult.Success(null)
    }
    val detector = McfpmServices(root).contextDetector
    if (forcedKind != null) {
        return when (val detected = detector.detect(root.workingDirectory, forcedKind)) {
            is McfpmResult.Success -> McfpmResult.Success(detected.value)
            is McfpmResult.Failure -> detected
        }
    }
    listOf(InstallContextKind.WORLD, InstallContextKind.INSTANCE).forEach { kind ->
        when (val detected = detector.detect(root.workingDirectory, kind)) {
            is McfpmResult.Success -> return McfpmResult.Success(detected.value)
            is McfpmResult.Failure -> Unit
        }
    }
    return McfpmResult.Success(null)
}

private fun selectContext(
    services: McfpmServices,
    root: McfpmCommand,
    options: InstallOptions,
    explicitDeployment: Boolean,
): McfpmResult<InstallContext> {
    val explicitCount = listOf(options.project, options.world, options.instance).count { it != null }
    if (explicitCount == 0) {
        require(!explicitDeployment) { "deploy requires an explicit target" }
        return services.contextDetector.detect(root.workingDirectory, options.forcedKind)
    }
    val kind = options.forcedKind ?: when {
        options.project != null -> InstallContextKind.PROJECT
        options.world != null -> InstallContextKind.WORLD
        else -> InstallContextKind.INSTANCE
    }
    val primary = when (kind) {
        InstallContextKind.PROJECT -> options.project
        InstallContextKind.WORLD -> options.world
        InstallContextKind.INSTANCE -> options.instance
    } ?: return McfpmResult.Failure(
        listOf(
            Diagnostic(
                DiagnosticCode.INVALID_ARGUMENT,
                DiagnosticSeverity.ERROR,
                "--context ${kind.name.lowercase()} requires its matching path option",
            ),
        ),
    )
    return services.contextDetector.explicit(kind, primary)
}

private fun resolveInstallLock(root: McfpmCommand, context: InstallContext, explicit: Path?): Path {
    if (explicit != null) return explicit.toAbsolutePath().normalize()
    if (context.kind == InstallContextKind.PROJECT) return context.root.resolve("mcfpm.lock")
    return McfpmServices.findAncestor(root.workingDirectory, "mcfpm.lock")?.resolve("mcfpm.lock")
        ?: context.root.resolve("mcfpm.lock")
}

private fun installSuccess(
    root: McfpmCommand,
    command: String,
    result: MinecraftInstallResult,
    projectEdit: InstallProjectEdit? = null,
): Int {
    val plan = result.plan
    return CliOutput.success(
        root,
        command,
        planJson(plan, result.transactionId, result.dryRun),
        buildString {
            if (projectEdit?.manifestCreated == true) {
                appendLine("Created ${projectEdit.projectRoot.resolve("mcfpm.toml")}")
            }
            if (!projectEdit?.dependencies.isNullOrEmpty()) {
                appendLine("Added ${projectEdit.dependencies.size} direct dependency/dependencies")
            }
            append(if (result.dryRun) "Dry run" else "Installed")
            append(": ${plan.copies.size} copy/copies, ${plan.removals.size} removal(s)")
            if (result.transactionId != null) append("; transaction ${result.transactionId}")
            if (plan.evidence.isNotEmpty()) append("\n" + plan.evidence.joinToString("\n") { "- $it" })
        },
    )
}

private fun planJson(plan: MinecraftInstallPlan, transactionId: String?, dryRun: Boolean): JsonObject = JsonObject(
    linkedMapOf(
        "context" to JsonPrimitive(plan.context.kind.name.lowercase()),
        "root" to JsonPrimitive(plan.context.root.toString()),
        "world" to (plan.world?.let { JsonPrimitive(it.toString()) } ?: JsonPrimitive(null as String?)),
        "instance" to (plan.instance?.let { JsonPrimitive(it.toString()) } ?: JsonPrimitive(null as String?)),
        "copies" to JsonArray(
            plan.copies.map { copy ->
                JsonObject(
                    mapOf(
                        "package" to JsonPrimitive(copy.payload.packageId.value),
                        "type" to JsonPrimitive(copy.payload.type.value),
                        "source" to JsonPrimitive(copy.source.toString()),
                        "target" to JsonPrimitive(copy.target.toString()),
                    ),
                )
            },
        ),
        "removals" to JsonArray(plan.removals.map { JsonPrimitive(it.toString()) }),
        "orderChanges" to JsonArray(
            plan.orderChanges.map { change ->
                JsonObject(
                    mapOf(
                        "file" to JsonPrimitive(change.file.toString()),
                        "before" to JsonArray(change.before.map(::JsonPrimitive)),
                        "after" to JsonArray(change.after.map(::JsonPrimitive)),
                    ),
                )
            },
        ),
        "evidence" to JsonArray(plan.evidence.map(::JsonPrimitive)),
        "requiresConfirmation" to JsonPrimitive(plan.requiresGlobalResourcePackConfirmation),
        "transactionId" to (transactionId?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?)),
        "dryRun" to JsonPrimitive(dryRun),
    ),
)

@CommandLine.Command(name = "rollback", description = ["Restore the latest Mcfpm installation transaction."])
internal class RollbackCommand : CliCallable() {
    @CommandLine.Option(names = ["--context"])
    private var context: InstallContextKind? = null

    @CommandLine.Option(names = ["--project"], paramLabel = "PATH")
    private var project: Path? = null

    @CommandLine.Option(names = ["--world"], paramLabel = "PATH")
    private var world: Path? = null

    @CommandLine.Option(names = ["--instance"], paramLabel = "PATH")
    private var instance: Path? = null

    @CommandLine.Option(names = ["--force"])
    private var force: Boolean = false

    override fun execute(): Int {
        require(!root.dryRun) { "rollback does not support --dry-run" }
        val services = McfpmServices(root)
        val selected = selectContext(
            services,
            root,
            InstallOptions(null, context, project, world, instance, force),
            explicitDeployment = false,
        )
        val installContext = when (selected) {
            is McfpmResult.Success -> selected.value
            is McfpmResult.Failure -> return CliOutput.failure(root, selected.diagnostics)
        }
        return MinecraftInstallEngine().rollback(installContext, force).foldCli(root) { transaction ->
            CliOutput.success(
                root,
                "rollback",
                JsonObject(mapOf("transactionId" to JsonPrimitive(transaction))),
                "Rolled back transaction $transaction",
            )
        }
    }
}

@CommandLine.Command(name = "cache", description = ["Inspect or verify the content-addressed cache."])
internal class CacheCommand : CliCallable() {
    @CommandLine.Option(names = ["--verify-lock"], paramLabel = "PATH")
    private var lockfile: Path? = null

    override fun execute(): Int {
        val services = McfpmServices(root)
        val failures = mutableListOf<String>()
        if (lockfile != null) {
            val graph = services.loadLock(requireNotNull(lockfile))
            graph.packages.flatMap { it.artifacts }.forEach { artifact ->
                if (!services.cache.verify(artifact.sha256, artifact.size)) failures += artifact.sha256
            }
        }
        val contentRoot = root.cacheDirectory.resolve("sha256")
        val count = if (Files.isDirectory(contentRoot)) {
            Files.walk(contentRoot).use { paths -> paths.filter(Files::isRegularFile).count() }
        } else {
            0L
        }
        val data = JsonObject(
            mapOf(
                "path" to JsonPrimitive(root.cacheDirectory.toAbsolutePath().normalize().toString()),
                "objects" to JsonPrimitive(count),
                "missingOrCorrupt" to JsonArray(failures.sorted().map(::JsonPrimitive)),
            ),
        )
        return if (failures.isEmpty()) {
            CliOutput.success(root, "cache", data, "Cache: ${root.cacheDirectory} ($count content object(s))")
        } else {
            CliOutput.failure(
                root,
                listOf(
                    Diagnostic(
                        DiagnosticCode.INTEGRITY_FAILURE,
                        DiagnosticSeverity.ERROR,
                        "${failures.size} locked cache object(s) are missing or corrupt",
                    ),
                ),
            )
        }
    }
}

@CommandLine.Command(
    name = "trust",
    description = ["Manage exact package/signing-fingerprint trust grants."],
    subcommands = [TrustAddCommand::class, TrustRemoveCommand::class, TrustListCommand::class],
)
internal class TrustCommand : CliCallable() {
    override fun execute(): Int {
        spec.commandLine().usage(root.out())
        return CliExitCode.SUCCESS.value
    }
}

@CommandLine.Command(name = "add", description = ["Trust one exact package and signature fingerprint."])
internal class TrustAddCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", paramLabel = "GROUP:NAME")
    private lateinit var packageId: String

    @CommandLine.Option(names = ["--fingerprint"], required = true)
    private lateinit var fingerprint: String

    override fun execute(): Int {
        val grant = TrustGrant(PackageId.parse(packageId), fingerprint)
        McfpmServices(root).trustStore.add(grant)
        return CliOutput.success(root, "trust add", trustJson(grant), "Trusted ${grant.packageId} at ${grant.fingerprint}")
    }
}

@CommandLine.Command(name = "remove", description = ["Remove one exact trust grant."])
internal class TrustRemoveCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", paramLabel = "GROUP:NAME")
    private lateinit var packageId: String

    @CommandLine.Option(names = ["--fingerprint"], required = true)
    private lateinit var fingerprint: String

    override fun execute(): Int {
        val grant = TrustGrant(PackageId.parse(packageId), fingerprint)
        McfpmServices(root).trustStore.remove(grant)
        return CliOutput.success(root, "trust remove", trustJson(grant), "Removed trust for ${grant.packageId}")
    }
}

@CommandLine.Command(name = "list", description = ["List trust grants."])
internal class TrustListCommand : CliCallable() {
    override fun execute(): Int {
        val grants = McfpmServices(root).trustStore.grants().sortedWith(compareBy({ it.packageId }, { it.fingerprint }))
        return CliOutput.success(
            root,
            "trust list",
            JsonObject(mapOf("grants" to JsonArray(grants.map(::trustJson)))),
            grants.joinToString("\n") { "${it.packageId} ${it.fingerprint}" }.ifEmpty { "No trust grants" },
        )
    }
}

private fun trustJson(grant: TrustGrant): JsonObject = JsonObject(
    mapOf("package" to JsonPrimitive(grant.packageId.value), "fingerprint" to JsonPrimitive(grant.fingerprint)),
)

@CommandLine.Command(name = "auth", description = ["Show Central Portal authentication readiness without exposing secrets."])
internal class AuthCommand : CliCallable() {
    override fun execute(): Int {
        val username = !System.getenv("CENTRAL_USERNAME").isNullOrBlank()
        val password = !System.getenv("CENTRAL_PASSWORD").isNullOrBlank()
        return CliOutput.success(
            root,
            "auth",
            JsonObject(
                mapOf(
                    "provider" to JsonPrimitive("central-portal"),
                    "usernameConfigured" to JsonPrimitive(username),
                    "passwordConfigured" to JsonPrimitive(password),
                    "ready" to JsonPrimitive(username && password),
                ),
            ),
            if (username && password) "Central Portal credentials are configured" else
                "Set CENTRAL_USERNAME and CENTRAL_PASSWORD to publish",
        )
    }
}

@CommandLine.Command(name = "config", description = ["Show effective non-secret Mcfpm configuration."])
internal class ConfigCommand : CliCallable() {
    override fun execute(): Int {
        val tool = runCatching { McfpmServices(root).loadManifest().tool }.getOrNull()
        val repositoryMap = linkedMapOf(
            "afox" to McfpmServices.AFOX_MAVEN_URI,
            "central" to moe.afox.mcfpm.repository.maven.MavenPackageRepository.MAVEN_CENTRAL_URI.toString(),
        )
        tool?.repositories.orEmpty().forEach { (id, uri) ->
            require(id !in McfpmServices.BUILT_IN_REPOSITORIES) {
                "The built-in repository $id cannot be replaced"
            }
            repositoryMap[id] = uri
        }
        root.repositories.forEach { declaration ->
            val separator = declaration.indexOf('=')
            if (separator > 0) {
                val id = declaration.substring(0, separator)
                require(id !in McfpmServices.BUILT_IN_REPOSITORIES) {
                    "The built-in repository $id cannot be replaced"
                }
                repositoryMap[id] = declaration.substring(separator + 1)
            }
        }
        val repositories = repositoryMap.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" }
        val bindingMap = tool?.bindings.orEmpty().toMutableMap()
        root.bindings.forEach { declaration ->
            val separator = declaration.indexOf('=')
            if (separator > 0) bindingMap[declaration.substring(0, separator)] = declaration.substring(separator + 1)
        }
        val defaultRepository = root.defaultRepository ?: run {
            when {
                tool == null -> "afox"
                tool.defaultRepository == "afox" -> "afox"
                else -> tool.defaultRepository
            }
        }
        val repositoryPriority = if (defaultRepository == "afox") listOf("afox", "central") else listOf(defaultRepository)
        val data = JsonObject(
            linkedMapOf(
                "workingDirectory" to JsonPrimitive(root.workingDirectory.toString()),
                "cacheDirectory" to JsonPrimitive(root.cacheDirectory.toString()),
                "trustStore" to JsonPrimitive(root.trustStore.toString()),
                "offline" to JsonPrimitive(root.offline),
                "defaultRepository" to JsonPrimitive(defaultRepository),
                "repositoryPriority" to JsonArray(repositoryPriority.map(::JsonPrimitive)),
                "repositories" to JsonArray(repositories.map(::JsonPrimitive)),
                "bindings" to JsonArray(bindingMap.toSortedMap().map { JsonPrimitive("${it.key}=${it.value}") }),
            ),
        )
        return CliOutput.success(
            root,
            "config",
            data,
            buildString {
                appendLine("working-directory=${root.workingDirectory}")
                appendLine("cache-directory=${root.cacheDirectory}")
                appendLine("trust-store=${root.trustStore}")
                appendLine("offline=${root.offline}")
                appendLine("default-repository=$defaultRepository")
                appendLine("repository-priority=${repositoryPriority.joinToString(" -> ")}")
                repositories.forEach { appendLine("repository=$it") }
                bindingMap.toSortedMap().forEach { (group, repository) -> appendLine("binding=$group=$repository") }
            }.trimEnd(),
        )
    }
}

@CommandLine.Command(name = "doctor", description = ["Inspect the local runtime and nearest Mcfpm/Minecraft context."])
internal class DoctorCommand : CliCallable() {
    override fun execute(): Int {
        val services = McfpmServices(root)
        val checks = mutableListOf<JsonObject>()
        fun check(name: String, ok: Boolean, detail: String) {
            checks += JsonObject(
                mapOf("name" to JsonPrimitive(name), "ok" to JsonPrimitive(ok), "detail" to JsonPrimitive(detail)),
            )
        }
        val javaFeature = Runtime.version().feature()
        check("java", javaFeature >= 17, Runtime.version().toString())
        check("cache", runCatching { Files.createDirectories(root.cacheDirectory); Files.isWritable(root.cacheDirectory) }.getOrDefault(false), root.cacheDirectory.toString())
        val project = McfpmServices.findAncestor(root.workingDirectory, "mcfpm.toml")
        check("manifest", project != null, project?.resolve("mcfpm.toml")?.toString() ?: "not found")
        if (project != null) {
            val manifestStatus = runCatching { services.loadManifest(project.resolve("mcfpm.toml")); "valid" }
            check("manifest-format", manifestStatus.isSuccess, manifestStatus.getOrElse { it.message.orEmpty() })
            val lock = project.resolve("mcfpm.lock")
            val lockStatus = runCatching { services.loadLock(lock); "valid" }
            check("lockfile", lockStatus.isSuccess, lockStatus.getOrElse { it.message.orEmpty() })
        }
        val context = services.contextDetector.detect(root.workingDirectory)
        when (context) {
            is McfpmResult.Success -> check("context", true, "${context.value.kind.name.lowercase()}:${context.value.root}")
            is McfpmResult.Failure -> check("context", false, context.diagnostics.joinToString { it.message })
        }
        val healthy = checks.all { (it.getValue("ok") as JsonPrimitive).content.toBoolean() }
        val human = checks.joinToString("\n") { item ->
            val ok = (item.getValue("ok") as JsonPrimitive).content.toBoolean()
            "${if (ok) "OK" else "FAIL"} ${(item.getValue("name") as JsonPrimitive).content}: " +
                (item.getValue("detail") as JsonPrimitive).content
        }
        return CliOutput.success(root, "doctor", JsonObject(mapOf("healthy" to JsonPrimitive(healthy), "checks" to JsonArray(checks))), human)
    }
}

@CommandLine.Command(name = "completion", description = ["Generate a Bash completion script."])
internal class CompletionCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", arity = "0..1", defaultValue = "bash", paramLabel = "SHELL")
    private lateinit var shell: String

    override fun execute(): Int {
        require(shell.lowercase() == "bash") { "Only bash completion is currently supported" }
        val script = AutoComplete.bash("mcfpm", CommandLine(McfpmCommand(root.workingDirectory)))
        return CliOutput.success(root, "completion", JsonObject(mapOf("shell" to JsonPrimitive("bash"), "script" to JsonPrimitive(script))), script)
    }
}
