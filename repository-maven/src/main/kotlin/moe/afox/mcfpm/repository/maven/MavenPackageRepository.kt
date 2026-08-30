package moe.afox.mcfpm.repository.maven

import moe.afox.mcfpm.core.PackageRepository
import moe.afox.mcfpm.core.RepositoryManifest
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.SemVer
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

public class MavenPackageRepository(
    override val id: String,
    baseUri: URI,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val credentials: MavenRepositoryCredentials? = null,
) : PackageRepository {
    override val baseUri: URI = ensureDirectoryUri(baseUri)

    init {
        require(id.isNotBlank()) { "Repository ID is required" }
        require(this.baseUri.scheme in setOf("file", "http", "https")) {
            "Maven repository must use file, http, or https: ${this.baseUri}"
        }
    }

    override fun versions(packageId: PackageId): McfpmResult<List<SemVer>> = try {
        val versions = if (baseUri.scheme == "file") {
            val packageDirectory = Path.of(baseUri.resolve(packagePath(packageId)))
            if (!Files.isDirectory(packageDirectory)) {
                emptyList()
            } else {
                Files.list(packageDirectory).use { paths ->
                    paths
                        .filter(Files::isDirectory)
                        .map { it.fileName.toString() }
                        .map(SemVer::parseOrNull)
                        .filter { it != null }
                        .map { it ?: error("Filtered above") }
                        .toList()
                }
            }
        } else {
            val metadata = read(baseUri.resolve("${packagePath(packageId)}maven-metadata.xml"))
            parseMetadata(metadata)
        }
        McfpmResult.Success(versions.distinct().sorted())
    } catch (_: RepositoryNotFoundException) {
        McfpmResult.Success(emptyList())
    } catch (_: NoSuchFileException) {
        McfpmResult.Success(emptyList())
    } catch (exception: Exception) {
        failure(
            "Unable to enumerate Maven versions for $packageId: ${exception.message}",
            mapOf("repository" to baseUri.toString()),
        )
    }

    override fun manifest(packageId: PackageId, version: SemVer): McfpmResult<RepositoryManifest> = try {
        val uri = baseUri.resolve(descriptorPath(packageId, version))
        val bytes = read(uri)
        val manifest = CanonicalJson.decode(PackageManifest.serializer(), bytes)
        require(manifest.packageId == packageId && manifest.version == version) {
            "Descriptor coordinate ${manifest.packageId}@${manifest.version} does not match $packageId@$version"
        }
        require(CanonicalJson.encodeManifest(manifest).contentEquals(bytes)) {
            "Descriptor is not canonical UTF-8 JSON"
        }
        McfpmResult.Success(RepositoryManifest(manifest, bytes))
    } catch (exception: RepositoryNotFoundException) {
        failure(
            "Unable to read Maven descriptor for $packageId@$version: HTTP 404",
            mapOf("repository" to baseUri.toString(), FAILURE_KIND to NOT_FOUND),
        )
    } catch (_: NoSuchFileException) {
        failure(
            "Unable to read Maven descriptor for $packageId@$version: file does not exist",
            mapOf("repository" to baseUri.toString(), FAILURE_KIND to NOT_FOUND),
        )
    } catch (exception: Exception) {
        failure(
            "Unable to read Maven descriptor for $packageId@$version: ${exception.message}",
            mapOf("repository" to baseUri.toString()),
        )
    }

    override fun artifactUri(
        packageId: PackageId,
        version: SemVer,
        artifact: ArtifactDescriptor,
    ): URI = baseUri.resolve(
        "${versionPath(packageId, version)}${packageId.name}-${resolvedFileVersion(packageId, version, artifact.extension, artifact.classifier)}-${artifact.classifier}.${artifact.extension}",
    )

    override fun requestHeaders(uri: URI): Map<String, String> =
        if (credentials != null && owns(uri)) {
            mapOf("Authorization" to credentials.authorizationHeader)
        } else {
            emptyMap()
        }

    public fun descriptorUri(packageId: PackageId, version: SemVer): URI =
        baseUri.resolve(descriptorPath(packageId, version))

    private fun read(uri: URI): ByteArray = when (uri.scheme) {
        "file" -> Files.readAllBytes(Path.of(uri))
        "http", "https" -> {
            val builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "application/octet-stream, application/xml, application/json")
                .header("User-Agent", "mcfpm/1")
            requestHeaders(uri).forEach { (name, value) -> builder.header(name, value) }
            val request = builder
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() == 404) throw RepositoryNotFoundException(uri)
            require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} for $uri" }
            response.body()
        }
        else -> error("Unsupported repository scheme: ${uri.scheme}")
    }

    private fun parseMetadata(bytes: ByteArray): List<SemVer> {
        val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        require(document.documentElement?.nodeName == "metadata") {
            "Maven metadata root must be <metadata>"
        }
        require(document.getElementsByTagName("versioning").length == 1) {
            "Maven metadata must contain exactly one <versioning> element"
        }
        val nodes = document.getElementsByTagName("version")
        return (0 until nodes.length).mapNotNull { index -> SemVer.parseOrNull(nodes.item(index).textContent.trim()) }
    }

    private fun parseSnapshotMetadata(bytes: ByteArray): SnapshotMetadata {
        val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val snapshot = document.getElementsByTagName("snapshot").item(0)
        val snapshotValues = snapshot?.childNodes?.asSequence()
            ?.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
            ?.associate { it.nodeName to it.textContent.trim() }
            .orEmpty()
        val versions = linkedMapOf<SnapshotArtifact, String>()
        val nodes = document.getElementsByTagName("snapshotVersion")
        for (index in 0 until nodes.length) {
            val children = nodes.item(index).childNodes.asSequence()
                .filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
                .associate { it.nodeName to it.textContent.trim() }
            val extension = children["extension"] ?: continue
            val value = children["value"] ?: continue
            versions[SnapshotArtifact(extension, children["classifier"]?.takeIf(String::isNotBlank))] = value
        }
        return SnapshotMetadata(snapshotValues["timestamp"], snapshotValues["buildNumber"], versions)
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        return factory
    }

    private fun packagePath(packageId: PackageId): String =
        "${packageId.group.replace('.', '/')}/${packageId.name}/"

    private fun versionPath(packageId: PackageId, version: SemVer): String =
        "${packagePath(packageId)}$version/"

    private fun descriptorPath(packageId: PackageId, version: SemVer): String =
        "${versionPath(packageId, version)}${packageId.name}-${resolvedFileVersion(packageId, version, "mcfpkg", null)}.mcfpkg"

    private fun resolvedFileVersion(
        packageId: PackageId,
        version: SemVer,
        extension: String,
        classifier: String?,
    ): String {
        if (!version.toString().endsWith("-SNAPSHOT")) return version.toString()
        val metadata = snapshotMetadata.computeIfAbsent(packageId to version) {
            parseSnapshotMetadata(read(baseUri.resolve("${versionPath(packageId, version)}maven-metadata.xml")))
        }
        return metadata.versions[SnapshotArtifact(extension, classifier)]
            ?: metadata.fallbackVersion(version)
    }

    private fun owns(uri: URI): Boolean {
        val candidate = uri.normalize()
        val relative = baseUri.relativize(candidate)
        return !relative.isAbsolute && baseUri.resolve(relative).normalize() == candidate
    }

    private fun <T> failure(message: String, context: Map<String, String>): McfpmResult<T> =
        McfpmResult.Failure(
            listOf(
                Diagnostic(
                    DiagnosticCode.NETWORK_FAILURE,
                    DiagnosticSeverity.ERROR,
                    message,
                    context,
                ),
            ),
        )

    public companion object {
        public val MAVEN_CENTRAL_URI: URI = URI.create("https://repo.maven.apache.org/maven2/")

        private const val FAILURE_KIND: String = "repository-failure-kind"
        private const val NOT_FOUND: String = "not-found"

        private fun ensureDirectoryUri(uri: URI): URI =
            if (uri.toString().endsWith('/')) uri.normalize() else URI.create("${uri.normalize()}/")
    }

    private val snapshotMetadata: MutableMap<Pair<PackageId, SemVer>, SnapshotMetadata> = ConcurrentHashMap()
}

private class RepositoryNotFoundException(uri: URI) : RuntimeException("HTTP 404 for $uri")

public class MavenRepositoryCredentials(
    username: String,
    password: String,
) {
    internal val authorizationHeader: String

    init {
        require(username.isNotBlank()) { "Maven repository username is required" }
        require(password.isNotEmpty()) { "Maven repository password is required" }
        authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
        )
    }

    override fun toString(): String = "MavenRepositoryCredentials(***)"

    public fun applyTo(builder: HttpRequest.Builder): HttpRequest.Builder =
        builder.header("Authorization", authorizationHeader)
}

public object MavenRepositoryAuthentication {
    public fun credentialsFromEnvironment(
        repositoryId: String,
        options: Map<String, String>,
        environment: (String) -> String? = System::getenv,
    ): MavenRepositoryCredentials? {
        val usernameKey = "repository.$repositoryId.username-env"
        val passwordKey = "repository.$repositoryId.password-env"
        val usernameEnvironment = options[usernameKey]
        val passwordEnvironment = options[passwordKey]
        if (usernameEnvironment == null && passwordEnvironment == null) return null
        require(!usernameEnvironment.isNullOrBlank() && !passwordEnvironment.isNullOrBlank()) {
            "Private repository $repositoryId must configure both $usernameKey and $passwordKey"
        }
        require(ENVIRONMENT_NAME.matches(usernameEnvironment) && ENVIRONMENT_NAME.matches(passwordEnvironment)) {
            "Private repository $repositoryId uses an invalid credential environment variable name"
        }
        val username = environment(usernameEnvironment)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Credential environment variable $usernameEnvironment is missing")
        val password = environment(passwordEnvironment)?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Credential environment variable $passwordEnvironment is missing")
        return MavenRepositoryCredentials(username, password)
    }

    private val ENVIRONMENT_NAME: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")
}

private data class SnapshotArtifact(
    val extension: String,
    val classifier: String?,
)

private data class SnapshotMetadata(
    val timestamp: String?,
    val buildNumber: String?,
    val versions: Map<SnapshotArtifact, String>,
) {
    fun fallbackVersion(version: SemVer): String {
        require(!timestamp.isNullOrBlank() && !buildNumber.isNullOrBlank()) {
            "Snapshot metadata does not contain a matching artifact or timestamp/build number"
        }
        return version.toString().removeSuffix("SNAPSHOT") + "$timestamp-$buildNumber"
    }
}

private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> =
    (0 until length).asSequence().map(::item)
