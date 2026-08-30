package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.UUID

public data class CachedArtifact(
    public val path: Path,
    public val sha256: String,
    public val size: Long,
    public val cacheHit: Boolean,
    public val etag: String? = null,
)

public class ContentAddressedCache(
    root: Path,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    private val root: Path = root.toAbsolutePath().normalize()
    private val contentRoot: Path = this.root.resolve("sha256")
    private val lockRoot: Path = this.root.resolve("locks")
    private val metadataRoot: Path = this.root.resolve("metadata")

    public fun fetch(
        uri: URI,
        expectedSha256: String,
        expectedSize: Long,
        offline: Boolean = false,
        requestHeaders: Map<String, String> = emptyMap(),
    ): McfpmResult<CachedArtifact> = try {
        fetchUnchecked(uri, expectedSha256, expectedSize, offline, requestHeaders)
    } catch (exception: Exception) {
        failure(
            DiagnosticCode.NETWORK_FAILURE,
            "Unable to access the content cache: ${exception.message ?: exception::class.simpleName}",
            mapOf("uri" to uri.toString()),
        )
    }

    private fun fetchUnchecked(
        uri: URI,
        expectedSha256: String,
        expectedSize: Long,
        offline: Boolean,
        requestHeaders: Map<String, String>,
    ): McfpmResult<CachedArtifact> {
        if (!SHA256_PATTERN.matches(expectedSha256) || expectedSize < 0) {
            return failure(DiagnosticCode.INVALID_ARGUMENT, "Invalid expected artifact checksum or size")
        }
        Files.createDirectories(contentRoot.resolve(expectedSha256.substring(0, 2)))
        Files.createDirectories(lockRoot)
        Files.createDirectories(metadataRoot)
        val destination = contentPath(expectedSha256)
        val lockFile = lockRoot.resolve("$expectedSha256.lock")
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (isValid(destination, expectedSha256, expectedSize)) {
                    return McfpmResult.Success(
                        CachedArtifact(destination, expectedSha256, expectedSize, cacheHit = true, etag = readEtag(expectedSha256)),
                    )
                }
                if (Files.exists(destination)) Files.delete(destination)
                if (offline) {
                    return failure(
                        DiagnosticCode.OFFLINE_MISS,
                        "Artifact $expectedSha256 is not available in the content cache",
                        mapOf("uri" to uri.toString()),
                    )
                }

                val temporary = destination.resolveSibling("$expectedSha256.${UUID.randomUUID()}.part")
                return try {
                    val etag = download(uri, temporary, requestHeaders)
                    val actualSize = Files.size(temporary)
                    val actualSha256 = Hashing.sha256(temporary)
                    if (actualSize != expectedSize || actualSha256 != expectedSha256) {
                        failure(
                            DiagnosticCode.INTEGRITY_FAILURE,
                            "Downloaded artifact failed integrity verification",
                            mapOf(
                                "expectedSha256" to expectedSha256,
                                "actualSha256" to actualSha256,
                                "expectedSize" to expectedSize.toString(),
                                "actualSize" to actualSize.toString(),
                            ),
                        )
                    } else {
                        atomicMove(temporary, destination)
                        if (etag != null) writeEtag(expectedSha256, etag)
                        McfpmResult.Success(CachedArtifact(destination, expectedSha256, expectedSize, cacheHit = false, etag = etag))
                    }
                } catch (exception: Exception) {
                    failure(
                        DiagnosticCode.NETWORK_FAILURE,
                        "Unable to fetch artifact: ${exception.message ?: exception::class.simpleName}",
                        mapOf("uri" to uri.toString()),
                    )
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        }
    }

    public fun contentPath(sha256: String): Path =
        contentRoot.resolve(sha256.substring(0, 2)).resolve(sha256)

    public fun verify(sha256: String, size: Long): Boolean =
        isValid(contentPath(sha256), sha256, size)

    private fun download(
        uri: URI,
        destination: Path,
        requestHeaders: Map<String, String>,
    ): String? = when (uri.scheme.lowercase()) {
        "file" -> {
            Files.copy(Path.of(uri), destination)
            null
        }
        "http", "https" -> downloadHttp(uri, destination, requestHeaders, redirectCount = 0)
        else -> throw IllegalArgumentException("Unsupported artifact URI scheme: ${uri.scheme}")
    }

    private fun downloadHttp(
        uri: URI,
        destination: Path,
        requestHeaders: Map<String, String>,
        redirectCount: Int,
    ): String? {
            val builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "mcfpm/${PubGrubResolver.RESOLVER_VERSION}")
            requestHeaders.toSortedMap().forEach { (name, value) -> builder.header(name, value) }
            val request = builder
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() in REDIRECT_STATUS_CODES) {
                response.body().close()
                require(redirectCount < MAX_REDIRECTS) { "Too many HTTP redirects" }
                val location = response.headers().firstValue("Location").orElseThrow {
                    IllegalStateException("HTTP redirect does not contain a Location header")
                }
                val target = uri.resolve(location).normalize()
                require(target.scheme in setOf("http", "https")) { "HTTP redirect uses an unsupported scheme" }
                require(!(uri.scheme == "https" && target.scheme == "http")) { "Refusing an HTTPS downgrade redirect" }
                val forwardedHeaders = if (sameOrigin(uri, target)) requestHeaders else emptyMap()
                return downloadHttp(target, destination, forwardedHeaders, redirectCount + 1)
            }
            if (response.statusCode() !in 200..299) {
                response.body().close()
                throw IllegalStateException("HTTP ${response.statusCode()}")
            }
            response.body().use { input ->
                Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use(input::copyTo)
            }
            return response.headers().firstValue("ETag").orElse(null)
    }

    private fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun isValid(path: Path, sha256: String, size: Long): Boolean =
        Files.isRegularFile(path) && Files.size(path) == size && Hashing.sha256(path) == sha256

    private fun readEtag(sha256: String): String? =
        metadataRoot.resolve("$sha256.etag").takeIf(Files::isRegularFile)?.let(Files::readString)

    private fun writeEtag(sha256: String, etag: String) {
        val destination = metadataRoot.resolve("$sha256.etag")
        val temporary = metadataRoot.resolve("$sha256.${UUID.randomUUID()}.etag.part")
        try {
            Files.writeString(temporary, etag, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            atomicMove(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun atomicMove(source: Path, destination: Path) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun <T> failure(
        code: DiagnosticCode,
        message: String,
        context: Map<String, String> = emptyMap(),
    ): McfpmResult<T> = McfpmResult.Failure(
        listOf(Diagnostic(code, DiagnosticSeverity.ERROR, message, context)),
    )

    private companion object {
        const val MAX_REDIRECTS: Int = 5
        val REDIRECT_STATUS_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)
        val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    }
}
