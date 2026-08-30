package moe.afox.mcfpm.cli

import moe.afox.mcfpm.core.ArtifactGraphFetcher
import moe.afox.mcfpm.core.ArtifactVerifier
import moe.afox.mcfpm.core.ContentAddressedCache
import moe.afox.mcfpm.core.DefaultMcfpmClient
import moe.afox.mcfpm.core.FileTrustStore
import moe.afox.mcfpm.core.LockfileCodec
import moe.afox.mcfpm.core.PackageManifestCodec
import moe.afox.mcfpm.core.PubGrubResolver
import moe.afox.mcfpm.core.RepositoryBinding
import moe.afox.mcfpm.core.RepositoryRegistry
import moe.afox.mcfpm.minecraft.MinecraftInstallContextDetector
import moe.afox.mcfpm.minecraft.MinecraftInstallExecutor
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.model.ToolConfiguration
import moe.afox.mcfpm.repository.maven.MavenPackageRepository
import moe.afox.mcfpm.repository.maven.MavenRepositoryAuthentication
import moe.afox.mcfpm.repository.maven.MavenRepositoryCredentials
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class ProjectFiles(
    val root: Path,
    val manifest: Path,
    val lockfile: Path,
)

internal data class ConfiguredRepository(
    val id: String,
    val uri: URI,
    val credentials: MavenRepositoryCredentials?,
    val options: Map<String, String>,
)

internal class McfpmServices(
    private val root: McfpmCommand,
    private val environment: (String) -> String? = System::getenv,
) {
    val cache: ContentAddressedCache by lazy { ContentAddressedCache(root.cacheDirectory) }
    val trustStore: FileTrustStore by lazy { FileTrustStore(root.trustStore) }
    val contextDetector: MinecraftInstallContextDetector = MinecraftInstallContextDetector()

    fun projectFiles(start: Path = root.workingDirectory): ProjectFiles {
        val projectRoot = findAncestor(start, "mcfpm.toml")
            ?: throw IllegalArgumentException("No mcfpm.toml was found in this directory or its ancestors")
        return ProjectFiles(projectRoot, projectRoot.resolve("mcfpm.toml"), projectRoot.resolve("mcfpm.lock"))
    }

    fun loadManifest(path: Path = projectFiles().manifest): PackageManifest =
        PackageManifestCodec.decode(Files.readAllBytes(path))

    fun loadLock(path: Path = projectFiles().lockfile): ResolvedGraph {
        require(Files.isRegularFile(path)) { "Lockfile does not exist: $path" }
        return LockfileCodec.decode(Files.readAllBytes(path))
    }

    fun configuredRegistry(): RepositoryRegistry {
        val projectTool = runCatching { loadManifest().tool }.getOrNull()
        val repositories = repositoryDeclarations(projectTool).mapValuesTo(linkedMapOf()) { (id, uri) ->
            createRepository(id, uri, projectTool)
        }
        val bindingMap = projectTool?.bindings.orEmpty().toMutableMap()
        root.bindings.forEach { declaration ->
            val (group, repository) = splitAssignment(declaration, "binding")
            bindingMap[group] = repository
        }
        val bindings = bindingMap.toSortedMap().map { (group, repository) -> RepositoryBinding(group, repository) }
        val defaultPriority = effectiveDefaultRepositoryPriority(projectTool)
        return RepositoryRegistry(
            repositories.values,
            bindings,
            defaultRepositoryId = defaultPriority.first(),
            defaultRepositoryIds = defaultPriority,
        )
    }

    fun configuredRepository(id: String): ConfiguredRepository {
        val projectTool = runCatching { loadManifest().tool }.getOrNull()
        val uri = repositoryDeclarations(projectTool)[id]
            ?: throw IllegalArgumentException("Repository is not declared: $id")
        return ConfiguredRepository(
            id = id,
            uri = URI.create(uri),
            credentials = repositoryCredentials(id, projectTool),
            options = projectTool?.options.orEmpty(),
        )
    }

    fun lockedRegistry(graph: ResolvedGraph): RepositoryRegistry {
        if (graph.packages.isEmpty()) return configuredRegistry()
        val projectTool = runCatching { loadManifest().tool }.getOrNull()
        val declaredRepositories = repositoryDeclarations(projectTool)
            .entries
            .associateBy({ normalizeRepositoryUri(it.value) }, { it.key })
        val repositoriesByUri = linkedMapOf<String, MavenPackageRepository>()
        graph.packages.map { it.repositoryUrl }.distinct().sorted().forEachIndexed { index, uri ->
            val normalizedUri = normalizeRepositoryUri(uri)
            val declaredId = declaredRepositories[normalizedUri]
            val credentials = declaredId?.let { repositoryCredentials(it, projectTool) }
            repositoriesByUri[normalizedUri] = MavenPackageRepository(
                id = "locked-$index",
                baseUri = URI.create(uri),
                credentials = credentials,
            )
        }
        val lockedPackageRepositories = graph.packages.associate { lockedPackage ->
            lockedPackage.packageId to repositoriesByUri.getValue(normalizeRepositoryUri(lockedPackage.repositoryUrl)).id
        }
        return RepositoryRegistry(
            repositoriesByUri.values,
            emptyList(),
            repositoriesByUri.values.first().id,
            lockedPackageRepositories = lockedPackageRepositories,
        )
    }

    fun client(registry: RepositoryRegistry): DefaultMcfpmClient = DefaultMcfpmClient(
        resolver = PubGrubResolver(registry),
        fetcher = ArtifactGraphFetcher(registry, cache),
        verifier = ArtifactVerifier(trustStore),
        installer = MinecraftInstallExecutor(),
    )

    fun readGraphAndClient(lockfile: Path? = null): Pair<ResolvedGraph, DefaultMcfpmClient> {
        val graph = loadLock(lockfile ?: projectFiles().lockfile)
        return graph to client(lockedRegistry(graph))
    }

    fun validateLockMatchesManifest(manifest: PackageManifest, graph: ResolvedGraph): McfpmResult<Unit> {
        val declared = manifest.dependencies.filterNot { it.optional }.map { it.packageId }.sorted()
        val locked = graph.roots.sorted()
        return if (declared == locked) {
            McfpmResult.Success(Unit)
        } else {
            McfpmResult.Failure(
                listOf(
                    Diagnostic(
                        DiagnosticCode.RESOLUTION_FAILED,
                        DiagnosticSeverity.ERROR,
                        "mcfpm.lock roots do not match mcfpm.toml dependencies; run mcfpm resolve",
                        mapOf(
                            "declared" to declared.joinToString(),
                            "locked" to locked.joinToString(),
                        ),
                    ),
                ),
            )
        }
    }

    private fun repositoryDeclarations(projectTool: ToolConfiguration?): LinkedHashMap<String, String> {
        val repositories = linkedMapOf(
            "afox" to AFOX_MAVEN_URI,
            "central" to MavenPackageRepository.MAVEN_CENTRAL_URI.toString(),
        )
        projectTool?.repositories.orEmpty().toSortedMap().forEach { (id, uri) ->
            require(id !in BUILT_IN_REPOSITORIES) { "The built-in repository $id cannot be replaced" }
            repositories[id] = uri
        }
        root.repositories.forEach { declaration ->
            val (id, uri) = splitAssignment(declaration, "repository")
            require(id !in BUILT_IN_REPOSITORIES) { "The built-in repository $id cannot be replaced" }
            repositories[id] = uri
        }
        return repositories
    }

    private fun effectiveDefaultRepositoryPriority(projectTool: ToolConfiguration?): List<String> {
        root.defaultRepository?.let { return listOf(it) }
        return when {
            projectTool == null -> listOf("afox", "central")
            projectTool.defaultRepository == "afox" -> listOf("afox", "central")
            else -> listOf(projectTool.defaultRepository)
        }
    }

    private fun createRepository(
        id: String,
        uri: String,
        projectTool: ToolConfiguration?,
    ): MavenPackageRepository = MavenPackageRepository(
        id = id,
        baseUri = URI.create(uri),
        credentials = repositoryCredentials(id, projectTool),
    )

    private fun repositoryCredentials(id: String, projectTool: ToolConfiguration?): MavenRepositoryCredentials? {
        return MavenRepositoryAuthentication.credentialsFromEnvironment(
            id,
            projectTool?.options.orEmpty(),
            environment,
        )
    }

    fun searchCentral(query: String, rows: Int, allVersions: Boolean = false): JsonObject {
        require(rows in 1..100) { "rows must be between 1 and 100" }
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val core = if (allVersions) "&core=gav" else ""
        val uri = URI.create("https://search.maven.org/solrsearch/select?q=$encoded$core&rows=$rows&wt=json")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("User-Agent", "mcfpm/1")
            .GET()
            .build()
        return try {
            val response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            require(response.statusCode() in 200..299) { "Central Search returned HTTP ${response.statusCode()}" }
            moe.afox.mcfpm.model.CanonicalJson.format.parseToJsonElement(response.body()).jsonObject
        } catch (exception: Exception) {
            throw CliDiagnosticException(
                listOf(
                    Diagnostic(
                        DiagnosticCode.NETWORK_FAILURE,
                        DiagnosticSeverity.ERROR,
                        "Unable to search Maven Central: ${exception.message}",
                    ),
                ),
            )
        }
    }

    fun centralDocuments(response: JsonObject): JsonArray =
        response.getValue("response").jsonObject.getValue("docs").jsonArray

    fun centralDocumentSummary(document: JsonObject): JsonObject = JsonObject(
        linkedMapOf(
            "package" to JsonPrimitive(
                "${document.getValue("g").jsonPrimitive.content}:${document.getValue("a").jsonPrimitive.content}",
            ),
            "version" to JsonPrimitive(
                document["v"]?.jsonPrimitive?.content ?: document["latestVersion"]?.jsonPrimitive?.content.orEmpty(),
            ),
            "packaging" to JsonPrimitive(document["p"]?.jsonPrimitive?.content ?: ""),
            "timestamp" to JsonPrimitive(document["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L),
        ),
    )

    companion object {
        const val AFOX_MAVEN_URI: String = "https://nexus.mcfpp.top/repository/maven-releases/"
        val BUILT_IN_REPOSITORIES: Set<String> = setOf("afox", "central")

        private val HTTP: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        fun findAncestor(start: Path, marker: String): Path? {
            var cursor: Path? = start.toAbsolutePath().normalize()
            while (cursor != null) {
                if (Files.exists(cursor.resolve(marker))) return cursor
                cursor = cursor.parent
            }
            return null
        }

        private fun splitAssignment(value: String, label: String): Pair<String, String> {
            val separator = value.indexOf('=')
            require(separator > 0 && separator < value.lastIndex) { "$label must use NAME=VALUE syntax" }
            return value.substring(0, separator) to value.substring(separator + 1)
        }

        private fun normalizeRepositoryUri(value: String): String {
            val normalized = URI.create(value).normalize().toString()
            return if (normalized.endsWith('/')) normalized else "$normalized/"
        }
    }
}

internal fun PackageManifest.invalidateSignature(): PackageManifest = copy(
    signatureFingerprint = null,
    signatureAlgorithm = null,
    signingPublicKey = null,
    signature = null,
)
