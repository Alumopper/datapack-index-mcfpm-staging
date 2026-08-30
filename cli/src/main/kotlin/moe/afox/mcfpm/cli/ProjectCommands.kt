package moe.afox.mcfpm.cli

import moe.afox.mcfpm.core.LockfileCodec
import moe.afox.mcfpm.core.PackageManifestCodec
import moe.afox.mcfpm.core.ResolveRequest
import moe.afox.mcfpm.core.RootRequirement
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.ConsumerProfile
import moe.afox.mcfpm.model.Dependency
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.ToolConfiguration
import moe.afox.mcfpm.model.VersionRequirement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import picocli.CommandLine

@CommandLine.Command(name = "init", description = ["Create a new mcfpm.toml project manifest."])
internal class InitCommand : CliCallable() {
    @CommandLine.Option(names = ["--id"], required = true, paramLabel = "GROUP:NAME")
    private lateinit var packageId: String

    @CommandLine.Option(names = ["--version"], defaultValue = "0.1.0")
    private lateinit var version: String

    @CommandLine.Option(names = ["--license"], defaultValue = "Apache-2.0")
    private lateinit var license: String

    @CommandLine.Option(names = ["--minecraft"])
    private var minecraft: String? = null

    @CommandLine.Option(names = ["--force"])
    private var force: Boolean = false

    override fun execute(): Int {
        val path = root.workingDirectory.resolve("mcfpm.toml")
        val existing = if (Files.isRegularFile(path)) PackageManifestCodec.decode(Files.readAllBytes(path)) else null
        require(force || existing == null || DraftProject.isDraft(existing)) {
            "mcfpm.toml already contains publication metadata; use --force to replace it"
        }
        val manifest = if (existing != null && !force) {
            existing.copy(
                packageId = PackageId.parse(packageId),
                version = SemVer.parse(version),
                license = license,
                minecraft = minecraft ?: existing.minecraft,
            ).invalidateSignature().normalized()
        } else {
            PackageManifest(
                packageId = PackageId.parse(packageId),
                version = SemVer.parse(version),
                license = license,
                minecraft = minecraft,
                tool = ToolConfiguration(
                    defaultRepository = root.defaultRepository ?: "afox",
                ),
            )
        }
        atomicWrite(path, PackageManifestCodec.encode(manifest))
        return CliOutput.success(
            root,
            "init",
            JsonObject(mapOf("manifest" to JsonPrimitive(path.toString()))),
            if (existing != null && !force) "Completed draft publication metadata in $path" else "Created $path",
        )
    }
}

@CommandLine.Command(name = "add", description = ["Add or update a direct dependency."])
internal class AddCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", paramLabel = "GROUP:NAME@REQUIREMENT")
    private lateinit var coordinate: String

    @CommandLine.Option(names = ["--feature"], paramLabel = "NAME")
    private var features: MutableList<String> = mutableListOf()

    @CommandLine.Option(names = ["--optional"])
    private var optional: Boolean = false

    override fun execute(): Int {
        val services = McfpmServices(root)
        val files = services.projectFiles()
        val manifest = services.loadManifest(files.manifest)
        val parsed = parseDependencyCoordinate(coordinate)
        val dependency = parsed.copy(
            features = features.distinct().sorted(),
            optional = optional,
        )
        val dependencies = manifest.dependencies.filterNot { it.packageId == dependency.packageId } + dependency
        atomicWrite(
            files.manifest,
            PackageManifestCodec.encode(manifest.copy(dependencies = dependencies).invalidateSignature().normalized()),
        )
        return CliOutput.success(
            root,
            "add",
            JsonObject(
                mapOf(
                    "package" to JsonPrimitive(dependency.packageId.value),
                    "requirement" to JsonPrimitive(dependency.requirement.expression),
                ),
            ),
            "Added ${dependency.packageId} ${dependency.requirement}",
        )
    }
}

@CommandLine.Command(name = "remove", description = ["Remove a direct dependency."])
internal class RemoveCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", paramLabel = "GROUP:NAME")
    private lateinit var packageId: String

    override fun execute(): Int {
        val id = PackageId.parse(packageId)
        val services = McfpmServices(root)
        val files = services.projectFiles()
        val manifest = services.loadManifest(files.manifest)
        require(manifest.dependencies.any { it.packageId == id }) { "$id is not a direct dependency" }
        atomicWrite(
            files.manifest,
            PackageManifestCodec.encode(
                manifest.copy(dependencies = manifest.dependencies.filterNot { it.packageId == id }).invalidateSignature(),
            ),
        )
        return CliOutput.success(root, "remove", JsonObject(mapOf("package" to JsonPrimitive(id.value))), "Removed $id")
    }
}

@CommandLine.Command(name = "resolve", description = ["Resolve mcfpm.toml and write deterministic mcfpm.lock."])
internal class ResolveCommand : CliCallable() {
    @CommandLine.Option(names = ["--profile"])
    private var profile: ConsumerProfile? = null

    @CommandLine.Option(names = ["--minecraft-version"])
    private var minecraftVersion: String? = null

    override fun execute(): Int = resolveProject(root, profile, minecraftVersion, "resolve")
}

@CommandLine.Command(name = "update", description = ["Re-resolve dependencies at their newest compatible versions."])
internal class UpdateCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "GROUP:NAME")
    private var packageId: String? = null

    @CommandLine.Option(names = ["--profile"])
    private var profile: ConsumerProfile? = null

    @CommandLine.Option(names = ["--minecraft-version"])
    private var minecraftVersion: String? = null

    override fun execute(): Int {
        packageId?.let { requested ->
            val id = PackageId.parse(requested)
            val manifest = McfpmServices(root).loadManifest()
            require(manifest.dependencies.any { it.packageId == id }) { "$id is not a direct dependency" }
        }
        return resolveProject(root, profile, minecraftVersion, "update")
    }
}

private fun resolveProject(
    root: McfpmCommand,
    profile: ConsumerProfile?,
    minecraftVersion: String?,
    command: String,
): Int {
    val services = McfpmServices(root)
    val files = services.projectFiles()
    val manifest = services.loadManifest(files.manifest)
    val request = ResolveRequest(
        roots = manifest.dependencies.filterNot { it.optional }.map { dependency ->
            RootRequirement(dependency.packageId, dependency.requirement, dependency.features)
        },
        consumerProfile = profile ?: manifest.tool.consumerProfile,
        minecraftVersion = minecraftVersion ?: manifest.minecraft,
    )
    return services.client(services.configuredRegistry()).resolve(request).foldCli(root) { graph ->
        if (!root.dryRun) atomicWrite(files.lockfile, LockfileCodec.encode(graph))
        if (!root.json) {
            graph.diagnostics.forEach { diagnostic ->
                root.err().println("${diagnostic.code.stableCode}: ${diagnostic.message}")
            }
        }
        CliOutput.success(
            root,
            command,
            JsonObject(
                linkedMapOf(
                    "lockfile" to JsonPrimitive(files.lockfile.toString()),
                    "written" to JsonPrimitive(!root.dryRun),
                    "graph" to CanonicalJson.format.encodeToJsonElement(ResolvedGraph.serializer(), graph),
                ),
            ),
            if (root.dryRun) "Resolved ${graph.packages.size} package(s); lockfile was not written" else
                "Resolved ${graph.packages.size} package(s) to ${files.lockfile}",
        )
    }
}

@CommandLine.Command(name = "fetch", description = ["Fetch every artifact selected by mcfpm.lock."])
internal class FetchCommand : CliCallable() {
    @CommandLine.Option(names = ["--lock"], paramLabel = "PATH")
    private var lockfile: Path? = null

    override fun execute(): Int {
        val services = McfpmServices(root)
        val (graph, client) = services.readGraphAndClient(lockfile)
        return client.fetch(graph, root.offline).foldCli(root) { fetched ->
            val artifacts = fetched.artifacts.entries.sortedBy { it.key.toString() }.map { (reference, path) ->
                JsonObject(
                    linkedMapOf(
                        "package" to JsonPrimitive(reference.packageId.value),
                        "type" to JsonPrimitive(reference.type.value),
                        "classifier" to JsonPrimitive(reference.classifier),
                        "path" to JsonPrimitive(path.toString()),
                    ),
                )
            }
            CliOutput.success(
                root,
                "fetch",
                JsonObject(mapOf("artifacts" to JsonArray(artifacts))),
                "Fetched ${artifacts.size} artifact(s)",
            )
        }
    }
}

@CommandLine.Command(name = "tree", description = ["Display the resolved dependency tree."])
internal class TreeCommand : CliCallable() {
    @CommandLine.Option(names = ["--lock"], paramLabel = "PATH")
    private var lockfile: Path? = null

    override fun execute(): Int {
        val graph = McfpmServices(root).loadLock(lockfile ?: McfpmServices(root).projectFiles().lockfile)
        val packages = graph.packages.associateBy { it.packageId }
        val edges = graph.edges.groupBy { it.from }.mapValues { (_, value) -> value.map { it.to }.sorted() }
        val lines = mutableListOf<String>()
        fun visit(id: PackageId, prefix: String, seen: Set<PackageId>) {
            val version = packages[id]?.version ?: return
            lines += "$prefix$id@$version"
            if (id in seen) return
            edges[id].orEmpty().forEach { visit(it, "$prefix  ", seen + id) }
        }
        graph.roots.sorted().forEach { visit(it, "", emptySet()) }
        val nodes = graph.packages.sortedBy { it.packageId }.map { resolved ->
            JsonObject(
                mapOf(
                    "package" to JsonPrimitive(resolved.packageId.value),
                    "version" to JsonPrimitive(resolved.version.toString()),
                    "dependencies" to JsonArray(edges[resolved.packageId].orEmpty().map { JsonPrimitive(it.value) }),
                ),
            )
        }
        return CliOutput.success(root, "tree", JsonObject(mapOf("nodes" to JsonArray(nodes))), lines.joinToString("\n"))
    }
}

@CommandLine.Command(name = "why", description = ["Show why a package is present in the lock graph."])
internal class WhyCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", paramLabel = "GROUP:NAME")
    private lateinit var packageId: String

    @CommandLine.Option(names = ["--lock"], paramLabel = "PATH")
    private var lockfile: Path? = null

    override fun execute(): Int {
        val target = PackageId.parse(packageId)
        val services = McfpmServices(root)
        val graph = services.loadLock(lockfile ?: services.projectFiles().lockfile)
        require(graph.packages.any { it.packageId == target }) { "$target is not present in the lock graph" }
        val adjacency = graph.edges.groupBy { it.from }.mapValues { (_, edges) -> edges.map { it.to }.sorted() }
        val queue = ArrayDeque<List<PackageId>>()
        graph.roots.sorted().forEach { queue += listOf(it) }
        var found: List<PackageId>? = null
        val seen = mutableSetOf<PackageId>()
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val current = path.last()
            if (current == target) {
                found = path
                break
            }
            if (seen.add(current)) adjacency[current].orEmpty().forEach { queue += path + it }
        }
        val path = requireNotNull(found) { "No path from a root to $target" }
        return CliOutput.success(
            root,
            "why",
            JsonObject(mapOf("path" to JsonArray(path.map { JsonPrimitive(it.value) }))),
            path.joinToString(" -> "),
        )
    }
}
