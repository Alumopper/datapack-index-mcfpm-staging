package moe.afox.mcfpm.source.url

import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.source.github.MinecraftPackInspector
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

public open class UrlSourceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
public class UrlSourceIntegrityException(message: String) : UrlSourceException(message)

public data class UrlDownloadLimits(
    public val maxBytes: Long = 128L * 1024L * 1024L,
    public val maxRedirects: Int = 5,
)

public data class UrlImportRequest(
    public val uri: URI,
    public val expectedSha256: String? = null,
    public val subdirectory: String? = null,
    public val nestedZip: String? = null,
)

public data class UrlImportCandidate(
    public val requestUri: URI,
    public val finalUri: URI,
    public val rawSha256: String,
    public val rawSize: Long,
    public val selectedPath: String,
    public val selectedRoot: String,
    public val selectedNestedZip: String?,
    public val payloadType: moe.afox.mcfpm.model.PayloadType,
    public val classifier: String,
    public val normalizedSha256: String,
    public val normalizedSize: Long,
    public val payload: ByteArray,
)

public class UrlSourceClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val inspector: MinecraftPackInspector = MinecraftPackInspector(),
    private val limits: UrlDownloadLimits = UrlDownloadLimits(),
) {
    init {
        require(limits.maxBytes in 1..MAX_BYTES) { "URL download limit must be between 1 byte and 128 MiB" }
        require(limits.maxRedirects in 0..MAX_REDIRECTS) { "URL redirect limit must be between 0 and 5" }
    }

    public fun import(request: UrlImportRequest): UrlImportCandidate {
        requireHttps(request.uri, "source URL")
        request.expectedSha256?.let {
            require(SHA256.matches(it)) { "Expected URL SHA-256 must be 64 hexadecimal characters" }
        }
        val downloaded = download(request.uri)
        val rawSha256 = Hashing.sha256(downloaded.bytes)
        if (request.expectedSha256 != null && !request.expectedSha256.equals(rawSha256, ignoreCase = true)) {
            throw UrlSourceIntegrityException(
                "Downloaded URL content SHA-256 does not match --expected-sha256",
            )
        }
        val inspected = try {
            inspector.inspect(downloaded.bytes, request.subdirectory, request.nestedZip)
        } catch (exception: Exception) {
            throw UrlSourceIntegrityException("Unable to inspect HTTPS ZIP: ${exception.message}")
        }
        return UrlImportCandidate(
            requestUri = request.uri,
            finalUri = downloaded.finalUri,
            rawSha256 = rawSha256,
            rawSize = downloaded.bytes.size.toLong(),
            selectedPath = inspected.displayPath,
            selectedRoot = inspected.rootPath,
            selectedNestedZip = inspected.nestedZip,
            payloadType = inspected.type,
            classifier = inspected.classifier,
            normalizedSha256 = Hashing.sha256(inspected.payload),
            normalizedSize = inspected.payload.size.toLong(),
            payload = inspected.payload,
        )
    }

    private fun download(uri: URI): DownloadedBytes {
        var current = uri
        repeat(limits.maxRedirects + 1) { redirect ->
            val request = HttpRequest.newBuilder(current)
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "application/zip, application/octet-stream")
                .header("User-Agent", "mcfpm/1")
                .GET()
                .build()
            val response = try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (exception: Exception) {
                throw UrlSourceException("Unable to download HTTPS source $current: ${exception.message}", exception)
            }
            response.body().use { body ->
                if (response.statusCode() in REDIRECTS) {
                    if (redirect >= limits.maxRedirects) {
                        throw UrlSourceException("HTTPS redirect limit exceeded for $uri")
                    }
                    val location = response.headers().firstValue("Location").orElseThrow {
                        UrlSourceException("HTTPS redirect omitted Location for $current")
                    }
                    current = current.resolve(location)
                    requireHttps(current, "redirect target")
                    return@repeat
                }
                if (response.statusCode() !in 200..299) {
                    throw UrlSourceException("HTTPS source returned HTTP ${response.statusCode()} for $current")
                }
                response.headers().firstValueAsLong("Content-Length").ifPresent { size ->
                    if (size > limits.maxBytes) throw UrlSourceIntegrityException("HTTPS response exceeds 128 MiB")
                }
                return DownloadedBytes(current, readLimited(body))
            }
        }
        throw UrlSourceException("HTTPS redirect limit exceeded for $uri")
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > limits.maxBytes) throw UrlSourceIntegrityException("HTTPS response exceeds 128 MiB")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun requireHttps(uri: URI, label: String) {
        require(
            uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() && uri.userInfo == null,
        ) { "$label must use a public HTTPS URL" }
    }

    private data class DownloadedBytes(val finalUri: URI, val bytes: ByteArray)

    private companion object {
        val REDIRECTS: Set<Int> = setOf(301, 302, 303, 307, 308)
        val SHA256: Regex = Regex("[0-9a-fA-F]{64}")
        const val MAX_BYTES: Long = 128L * 1024L * 1024L
        const val MAX_REDIRECTS: Int = 5
    }
}
