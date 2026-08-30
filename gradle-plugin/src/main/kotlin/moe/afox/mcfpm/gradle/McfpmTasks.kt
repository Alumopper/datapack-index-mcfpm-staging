package moe.afox.mcfpm.gradle

import moe.afox.mcfpm.core.ArtifactGraphFetcher
import moe.afox.mcfpm.core.ArtifactVerifier
import moe.afox.mcfpm.core.BundleBuilder
import moe.afox.mcfpm.core.ContentAddressedCache
import moe.afox.mcfpm.core.FetchedGraph
import moe.afox.mcfpm.core.FileTrustStore
import moe.afox.mcfpm.core.InMemoryTrustStore
import moe.afox.mcfpm.core.KoreLockGraphAdapter
import moe.afox.mcfpm.core.LockfileCodec
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.ManifestSigner
import moe.afox.mcfpm.core.PackageManifestCodec
import moe.afox.mcfpm.core.PubGrubResolver
import moe.afox.mcfpm.core.RepositoryRegistry
import moe.afox.mcfpm.core.RepositoryBinding
import moe.afox.mcfpm.core.ResolveRequest
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.core.RootRequirement
import moe.afox.mcfpm.model.ConsumerProfile
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PayloadRef
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.repository.maven.MavenPackageRepository
import moe.afox.mcfpm.repository.maven.MavenRepositoryAuthentication
import moe.afox.mcfpm.repository.maven.MavenRepositoryCredentials
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
public abstract class McfpmResolveTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val manifestFile: RegularFileProperty

    @get:Input
    public abstract val consumerProfile: Property<String>

    @get:Input
    public abstract val repositoryUrl: Property<String>

    @get:OutputFile
    public abstract val lockfile: RegularFileProperty

    @TaskAction
    public fun resolve() {
        val manifest = PackageManifestCodec.decode(Files.readAllBytes(manifestFile.get().asFile.toPath()))
        val registry = projectRegistry(manifest, repositoryUrl.get())
        val request = ResolveRequest(
            roots = manifest.dependencies
                .filterNot { it.optional }
                .map { RootRequirement(it.packageId, it.requirement, it.features) },
            consumerProfile = parseProfile(consumerProfile.get()),
            minecraftVersion = manifest.minecraft,
        )
        val graph = success(PubGrubResolver(registry).resolve(request))
        graph.diagnostics.forEach { diagnostic ->
            logger.warn("${diagnostic.code.stableCode}: ${diagnostic.message}")
        }
        val output = lockfile.get().asFile.toPath()
        Files.createDirectories(output.parent)
        Files.write(output, LockfileCodec.encode(graph))
    }
}

@CacheableTask
public abstract class McfpmFetchTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val manifestFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val lockfile: RegularFileProperty

    @get:Input
    public abstract val repositoryUrl: Property<String>

    @get:Input
    public abstract val offline: Property<Boolean>

    @get:OutputDirectory
    public abstract val cacheDirectory: DirectoryProperty

    @get:OutputFile
    public abstract val reportFile: RegularFileProperty

    @TaskAction
    public fun fetch() {
        val manifest = PackageManifestCodec.decode(Files.readAllBytes(manifestFile.get().asFile.toPath()))
        val graph = LockfileCodec.decode(Files.readAllBytes(lockfile.get().asFile.toPath()))
        val registry = lockedRegistry(graph, manifest)
        val cachePath = cacheDirectory.get().asFile.toPath()
        Files.createDirectories(cachePath)
        val fetched = success(
            ArtifactGraphFetcher(registry, ContentAddressedCache(cachePath))
                .fetch(graph, offline.get()),
        )
        val report = fetched.artifacts.entries.sortedBy { it.key.toString() }.joinToString(
            prefix = "{\"schema\":1,\"artifacts\":[",
            postfix = "]}",
            separator = ",",
        ) { (reference, path) ->
            "{\"package\":\"${reference.packageId.value}\",\"type\":\"${reference.type.value}\",\"classifier\":\"${reference.classifier}\",\"path\":\"${escape(path.toString())}\"}"
        }
        write(reportFile.get().asFile.toPath(), report.encodeToByteArray())
    }
}

@CacheableTask
public abstract class McfpmVerifyTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val lockfile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val cacheDirectory: DirectoryProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val trustFile: RegularFileProperty

    @get:OutputFile
    public abstract val reportFile: RegularFileProperty

    @TaskAction
    public fun verify() {
        val graph = LockfileCodec.decode(Files.readAllBytes(lockfile.get().asFile.toPath()))
        val cache = ContentAddressedCache(cacheDirectory.get().asFile.toPath())
        val paths = graph.packages.flatMap { resolvedPackage ->
            resolvedPackage.artifacts.map { artifact ->
                PayloadRef(resolvedPackage.packageId, artifact.type, artifact.classifier) to cache.contentPath(artifact.sha256)
            }
        }.toMap()
        val trust = trustFile.orNull?.asFile?.toPath()?.takeIf(Files::isRegularFile)
            ?.let(::FileTrustStore)
            ?: InMemoryTrustStore()
        success(ArtifactVerifier(trust).verify(FetchedGraph(graph, paths)))
        write(reportFile.get().asFile.toPath(), "{\"schema\":1,\"verified\":true}\n".encodeToByteArray())
    }
}

@CacheableTask
public abstract class McfpmBundleTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val lockfile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val cacheDirectory: DirectoryProperty

    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    @TaskAction
    public fun bundle() {
        val graph = LockfileCodec.decode(Files.readAllBytes(lockfile.get().asFile.toPath()))
        val cache = ContentAddressedCache(cacheDirectory.get().asFile.toPath())
        val paths = graph.packages.flatMap { resolvedPackage ->
            resolvedPackage.artifacts.map { artifact ->
                PayloadRef(resolvedPackage.packageId, artifact.type, artifact.classifier) to cache.contentPath(artifact.sha256)
            }
        }.toMap()
        val bundle = success(BundleBuilder.build(FetchedGraph(graph, paths)))
        val output = outputDirectory.get().asFile.toPath()
        Files.createDirectories(output)
        bundle.files.forEach { (name, bytes) -> write(output.resolve(name), bytes) }
        write(output.resolve("mcfpm-bundle.json"), bundle.manifest)
    }
}

@CacheableTask
public abstract class McfpmPackPayloadTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val sourceDirectory: DirectoryProperty

    @get:Input
    public abstract val payloadType: Property<String>

    @get:Input
    public abstract val classifier: Property<String>

    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    @TaskAction
    public fun pack() {
        val bytes = ReproducibleZip.fromDirectory(sourceDirectory.get().asFile.toPath())
        ReproducibleZip.verify(
            bytes,
            requirePackMetadata = payloadType.get() in setOf("minecraft.datapack", "minecraft.resourcepack"),
        )
        write(outputFile.get().asFile.toPath(), bytes)
    }
}

@CacheableTask
public abstract class McfpmKoreBindingsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val lockfile: RegularFileProperty

    @get:Input
    public abstract val packageName: Property<String>

    @get:Input
    public abstract val aliases: MapProperty<String, String>

    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    @TaskAction
    public fun generate() {
        val graph = LockfileCodec.decode(Files.readAllBytes(lockfile.get().asFile.toPath()))
        val parsedAliases = aliases.get().mapKeys { (id, _) -> moe.afox.mcfpm.model.PackageId.parse(id) }
        val source = KoreLockGraphAdapter.generateKotlin(graph, packageName.get(), parsedAliases)
        write(outputFile.get().asFile.toPath(), source.encodeToByteArray())
    }
}

@CacheableTask
public abstract class McfpmPublishValidationTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val manifestFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val lockfile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val payloadFiles: ConfigurableFileCollection

    @get:OutputFile
    public abstract val reportFile: RegularFileProperty

    @TaskAction
    public fun validatePublication() {
        val manifest = PackageManifestCodec.decode(Files.readAllBytes(manifestFile.get().asFile.toPath()))
        LockfileCodec.decode(Files.readAllBytes(lockfile.get().asFile.toPath()))
        require(manifest.license.isNotBlank()) { "Package license is required for publication" }
        require(ManifestSigner.isSigned(manifest) && ManifestSigner.verify(manifest)) {
            "The canonical package descriptor requires a valid Ed25519 signature"
        }
        require(manifest.artifacts.isNotEmpty()) { "At least one payload is required for publication" }
        val filesByClassifier = payloadFiles.files.associateBy { file -> file.name.substringBeforeLast('.') }
        manifest.artifacts.forEach { artifact ->
            val file = filesByClassifier[artifact.classifier]
                ?: throw GradleException("Missing packed payload for classifier ${artifact.classifier}")
            val path = file.toPath()
            require(Files.size(path) == artifact.size && Hashing.sha256(path) == artifact.sha256) {
                "Packed payload ${artifact.classifier} does not match mcfpm.toml"
            }
        }
        val undeclared = filesByClassifier.keys - manifest.artifacts.map { it.classifier }.toSet()
        require(undeclared.isEmpty()) { "Undeclared packed payload classifiers: ${undeclared.sorted().joinToString()}" }
        write(reportFile.get().asFile.toPath(), "{\"schema\":1,\"publishable\":true}\n".encodeToByteArray())
    }
}

internal fun parseProfile(value: String): ConsumerProfile = when (value) {
    "all" -> ConsumerProfile.ALL
    "minecraft.datapack", "datapack" -> ConsumerProfile.MINECRAFT_DATAPACK
    "minecraft.resourcepack", "resourcepack" -> ConsumerProfile.MINECRAFT_RESOURCEPACK
    "compiler.mcfpp", "mcfpp" -> ConsumerProfile.MCFPP
    "jvm.plugin", "plugin" -> ConsumerProfile.JVM_PLUGIN
    else -> throw GradleException("Unknown Mcfpm consumer profile: $value")
}

internal fun <T> success(result: McfpmResult<T>): T = when (result) {
    is McfpmResult.Success -> result.value
    is McfpmResult.Failure -> throw GradleException(
        result.diagnostics.joinToString(System.lineSeparator()) { "${it.code.stableCode}: ${it.message}" },
    )
}

internal fun write(path: Path, bytes: ByteArray) {
    Files.createDirectories(path.parent)
    Files.write(path, bytes)
}

private fun escape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

private fun projectRegistry(manifest: PackageManifest, fallbackRepository: String): RepositoryRegistry {
    val repositories = linkedMapOf(
        "afox" to MavenPackageRepository(
            id = "afox",
            baseUri = URI.create("https://nexus.mcfpp.top/repository/maven-releases/"),
            credentials = repositoryCredentials("afox", manifest),
        ),
        "central" to MavenPackageRepository(
            id = "central",
            baseUri = URI.create(fallbackRepository),
            credentials = repositoryCredentials("central", manifest),
        ),
    )
    manifest.tool.repositories.toSortedMap().forEach { (id, uri) ->
        require(id !in setOf("afox", "central")) { "The built-in repository $id cannot be replaced" }
        repositories[id] = MavenPackageRepository(
            id = id,
            baseUri = URI.create(uri),
            credentials = repositoryCredentials(id, manifest),
        )
    }
    val bindings = manifest.tool.bindings.toSortedMap().map { (group, repository) ->
        RepositoryBinding(group, repository)
    }
    val priority = if (manifest.tool.defaultRepository == "afox") listOf("afox", "central")
    else listOf(manifest.tool.defaultRepository)
    return RepositoryRegistry(
        repositories.values,
        bindings,
        defaultRepositoryId = priority.first(),
        defaultRepositoryIds = priority,
    )
}

private fun lockedRegistry(graph: ResolvedGraph, manifest: PackageManifest): RepositoryRegistry {
    val sources = graph.packages.map { normalizeRepository(it.repositoryUrl) }.distinct().sorted()
    if (sources.isEmpty()) {
        val central = MavenPackageRepository("central", MavenPackageRepository.MAVEN_CENTRAL_URI)
        return RepositoryRegistry(listOf(central), defaultRepositoryId = central.id)
    }
    val declaredRepositories = buildMap {
        put(normalizeRepository("https://nexus.mcfpp.top/repository/maven-releases/"), "afox")
        put(normalizeRepository(MavenPackageRepository.MAVEN_CENTRAL_URI.toString()), "central")
        manifest.tool.repositories.forEach { (id, uri) -> put(normalizeRepository(uri), id) }
    }
    val repositories = sources.mapIndexed { index, uri ->
        val declaredId = declaredRepositories[normalizeRepository(uri)]
        MavenPackageRepository(
            id = "locked-$index",
            baseUri = URI.create(uri),
            credentials = declaredId?.let { repositoryCredentials(it, manifest) },
        )
    }
    val repositoriesByUri = repositories.associateBy { normalizeRepository(it.baseUri.toString()) }
    val lockedPackageRepositories = graph.packages.associate { lockedPackage ->
        lockedPackage.packageId to repositoriesByUri.getValue(normalizeRepository(lockedPackage.repositoryUrl)).id
    }
    return RepositoryRegistry(
        repositories,
        emptyList(),
        repositories.first().id,
        lockedPackageRepositories = lockedPackageRepositories,
    )
}

private fun repositoryCredentials(id: String, manifest: PackageManifest): MavenRepositoryCredentials? =
    MavenRepositoryAuthentication.credentialsFromEnvironment(id, manifest.tool.options)

private fun normalizeRepository(value: String): String {
    val normalized = URI.create(value).normalize().toString()
    return if (normalized.endsWith('/')) normalized else "$normalized/"
}
