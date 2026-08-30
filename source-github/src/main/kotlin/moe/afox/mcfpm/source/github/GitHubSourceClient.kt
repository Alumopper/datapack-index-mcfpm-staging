package moe.afox.mcfpm.source.github

import moe.afox.mcfpm.core.Hashing
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

public open class GitHubImportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
public class GitHubNetworkException(message: String, cause: Throwable? = null) : GitHubImportException(message, cause)
public class GitHubIntegrityException(message: String) : GitHubImportException(message)

public data class GitHubDownloadLimits(
    public val maxBytes: Long = 128L * 1024L * 1024L,
    public val maxJsonBytes: Long = 2L * 1024L * 1024L,
    public val maxRedirects: Int = 5,
)

public class GitHubSourceClient(
    private val apiBaseUri: URI = URI.create("https://api.github.com/"),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val inspector: MinecraftPackInspector = MinecraftPackInspector(),
    private val limits: GitHubDownloadLimits = GitHubDownloadLimits(),
) {
    init {
        require(apiBaseUri.isAbsolute && apiBaseUri.scheme in setOf("http", "https")) { "GitHub API base URI must be HTTP(S)" }
        require(limits.maxBytes in 1..MAX_BYTES) { "GitHub download limit must be between 1 byte and 128 MiB" }
        require(limits.maxJsonBytes in 1..limits.maxBytes) { "GitHub JSON limit must be positive and no larger than the download limit" }
        require(limits.maxRedirects in 0..MAX_REDIRECTS) { "GitHub redirect limit must be between 0 and 5" }
    }

    public fun import(request: GitHubImportRequest): GitHubImportCandidate {
        val commit = resolveCommit(request.repository, request.reference, request.token)
        val license = request.explicitLicense?.takeIf(String::isNotBlank)
            ?: detectedLicense(request.repository, request.token)
            ?: throw IllegalArgumentException(
                "GitHub did not report a recognized repository SPDX license; pass --license explicitly",
            )
        return when (request.mode) {
            GitHubSourceMode.RELEASE_ASSET -> importReleaseAsset(request, commit, license)
            GitHubSourceMode.ARCHIVE -> importArchive(request, commit, license)
        }
    }

    public fun resolveCommit(repository: GitHubRepository, reference: String, token: String? = null): String {
        val commit = getJson(
            api("repos/${repository.owner}/${repository.name}/commits/${encodePathSegment(reference)}"),
            token,
        )
        val sha = commit["sha"]?.jsonPrimitive?.content.orEmpty()
        require(SHA.matches(sha)) { "GitHub returned an invalid commit SHA for ${repository.slug}@$reference" }
        return sha.lowercase()
    }

    public fun detectedLicense(repository: GitHubRepository, token: String? = null): String? {
        val response = runCatching {
            getJson(api("repos/${repository.owner}/${repository.name}/license"), token)
        }.getOrElse { cause ->
            if (cause is GitHubNetworkException && cause.message?.contains("HTTP 404") == true) return null
            throw cause
        }
        return response["license"]?.jsonObject?.get("spdx_id")?.jsonPrimitive?.contentOrNull
            ?.takeUnless { it == "NOASSERTION" || it == "OTHER" || it.isBlank() }
    }

    private fun importReleaseAsset(
        request: GitHubImportRequest,
        commit: String,
        license: String,
    ): GitHubImportCandidate {
        val release = getJson(
            api("repos/${request.repository.owner}/${request.repository.name}/releases/tags/${encodePathSegment(request.reference)}"),
            request.token,
        )
        val releaseId = release.requireLong("id")
        val zipAssets = release["assets"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it.requireString("name").lowercase().endsWith(".zip") }
            .sortedBy { it.requireString("name") }
        val matching = request.asset?.let { asset -> zipAssets.filter { it.requireString("name") == asset } } ?: zipAssets
        require(matching.size == 1) {
            val available = zipAssets.map { it.requireString("name") }
            when {
                zipAssets.isEmpty() -> "GitHub release ${request.reference} has no ZIP assets"
                matching.isEmpty() -> "Release asset ${request.asset} was not found; ZIP assets: ${available.joinToString()}"
                else -> "GitHub release has multiple ZIP assets; pass --asset exactly: ${available.joinToString()}"
            }
        }
        val asset = matching.single()
        val assetId = asset.requireLong("id")
        val assetName = asset.requireString("name")
        val expectedSize = asset.requireLong("size")
        val assetApi = api("repos/${request.repository.owner}/${request.repository.name}/releases/assets/$assetId")
        val downloaded = download(assetApi, request.token, "application/octet-stream")
        val raw = downloaded.bytes
        if (raw.size.toLong() != expectedSize) {
            throw GitHubIntegrityException("GitHub release asset size mismatch for $assetName")
        }
        val rawSha256 = Hashing.sha256(raw)
        verifyExpectedHash(request.expectedSha256, rawSha256)
        asset["digest"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { digest ->
            val expectedDigest = digest.removePrefix("sha256:")
            if (!expectedDigest.equals(rawSha256, ignoreCase = true)) {
                throw GitHubIntegrityException("GitHub release asset digest mismatch for $assetName")
            }
        }
        val inspected = inspector.inspect(raw, request.subdirectory, request.nestedZip)
        return candidate(
            request = request,
            commit = commit,
            license = license,
            releaseId = releaseId,
            assetId = assetId,
            assetName = assetName,
            sourceUri = URI.create(asset.requireString("browser_download_url")),
            requestUri = assetApi,
            finalUri = downloaded.finalUri,
            raw = raw,
            rawSha256 = rawSha256,
            inspected = inspected,
        )
    }

    private fun importArchive(
        request: GitHubImportRequest,
        commit: String,
        license: String,
    ): GitHubImportCandidate {
        val archiveApi = api("repos/${request.repository.owner}/${request.repository.name}/zipball/$commit")
        val downloaded = download(
            archiveApi,
            request.token,
            "application/zip",
        )
        val raw = downloaded.bytes
        val rawSha256 = Hashing.sha256(raw)
        verifyExpectedHash(request.expectedSha256, rawSha256)
        val inspected = inspector.inspect(
            raw,
            request.subdirectory,
            request.nestedZip,
            stripSingleRootDirectory = true,
        )
        return candidate(
            request = request,
            commit = commit,
            license = license,
            releaseId = null,
            assetId = null,
            assetName = null,
            sourceUri = URI.create("https://github.com/${request.repository.slug}/archive/$commit.zip"),
            requestUri = archiveApi,
            finalUri = downloaded.finalUri,
            raw = raw,
            rawSha256 = rawSha256,
            inspected = inspected,
        )
    }

    private fun candidate(
        request: GitHubImportRequest,
        commit: String,
        license: String,
        releaseId: Long?,
        assetId: Long?,
        assetName: String?,
        sourceUri: URI,
        requestUri: URI,
        finalUri: URI,
        raw: ByteArray,
        rawSha256: String,
        inspected: PackInspectionCandidate,
    ): GitHubImportCandidate = GitHubImportCandidate(
        repository = request.repository,
        mode = request.mode,
        reference = request.reference,
        releaseId = releaseId,
        assetId = assetId,
        assetName = assetName,
        commit = commit,
        sourceUri = sourceUri,
        requestUri = requestUri,
        finalUri = finalUri,
        rawSha256 = rawSha256,
        rawSize = raw.size.toLong(),
        license = license,
        selectedPath = inspected.displayPath,
        selectedRoot = inspected.rootPath,
        selectedNestedZip = inspected.nestedZip,
        payloadType = inspected.type,
        classifier = inspected.classifier,
        normalizedSha256 = Hashing.sha256(inspected.payload),
        normalizedSize = inspected.payload.size.toLong(),
        payload = inspected.payload,
    )

    private fun getJson(uri: URI, token: String?): JsonObject {
        val bytes = request(uri, token, "application/vnd.github+json", limits.maxJsonBytes).bytes
        return try {
            moe.afox.mcfpm.model.CanonicalJson.format.parseToJsonElement(
                bytes.toString(StandardCharsets.UTF_8),
            ).jsonObject
        } catch (exception: Exception) {
            throw GitHubNetworkException("GitHub returned invalid JSON for $uri", exception)
        }
    }

    private fun download(uri: URI, token: String?, accept: String): DownloadedBytes =
        request(uri, token, accept, limits.maxBytes)

    private fun request(uri: URI, token: String?, accept: String, maxBytes: Long): DownloadedBytes {
        var current = uri
        val initialOrigin = origin(uri)
        repeat(limits.maxRedirects + 1) { redirect ->
            val builder = HttpRequest.newBuilder(current)
                .timeout(Duration.ofMinutes(2))
                .header("Accept", accept)
                .header("User-Agent", "mcfpm/1")
                .header("X-GitHub-Api-Version", "2022-11-28")
            if (!token.isNullOrBlank() && origin(current) == initialOrigin) {
                builder.header("Authorization", "Bearer $token")
            }
            val response = try {
                httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            } catch (exception: Exception) {
                throw GitHubNetworkException("Unable to request GitHub resource $current: ${exception.message}", exception)
            }
            response.body().use { body ->
                if (response.statusCode() in REDIRECTS) {
                    if (redirect >= limits.maxRedirects) {
                        throw GitHubNetworkException("GitHub redirect limit exceeded for $uri")
                    }
                    val location = response.headers().firstValue("Location").orElseThrow {
                        GitHubNetworkException("GitHub redirect omitted Location for $current")
                    }
                    current = current.resolve(location)
                    return@repeat
                }
                if (response.statusCode() !in 200..299) {
                    throw GitHubNetworkException("GitHub returned HTTP ${response.statusCode()} for $current")
                }
                response.headers().firstValueAsLong("Content-Length").ifPresent { size ->
                    if (size > maxBytes) throw GitHubIntegrityException("GitHub response exceeds the configured download limit")
                }
                return DownloadedBytes(current, readLimited(body, maxBytes))
            }
        }
        throw GitHubNetworkException("GitHub redirect limit exceeded for $uri")
    }

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maxBytes) throw GitHubIntegrityException("GitHub response exceeds the configured download limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun verifyExpectedHash(expected: String?, actual: String) {
        if (expected != null && !expected.equals(actual, ignoreCase = true)) {
            throw GitHubIntegrityException("Downloaded GitHub content SHA-256 does not match --expected-sha256")
        }
    }

    private fun api(path: String): URI = directoryUri(apiBaseUri).resolve(path)

    private fun origin(uri: URI): String {
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "GitHub redirect must use an absolute HTTP(S) URI" }
        return "${uri.scheme.lowercase()}://${uri.host.lowercase()}:${effectivePort(uri)}"
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun encodePathSegment(value: String): String = value.toByteArray(StandardCharsets.UTF_8).joinToString("") { byte ->
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if (character.isLetterOrDigit() || character in "-._~") character.toString() else "%${unsigned.toString(16).uppercase().padStart(2, '0')}"
    }

    private fun JsonObject.requireString(key: String): String =
        this[key]?.jsonPrimitive?.content ?: throw GitHubNetworkException("GitHub response omitted $key")

    private fun JsonObject.requireLong(key: String): Long =
        requireString(key).toLongOrNull() ?: throw GitHubNetworkException("GitHub response has invalid $key")

    private data class DownloadedBytes(val finalUri: URI, val bytes: ByteArray)

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList().orEmpty()

    private companion object {
        val SHA: Regex = Regex("[0-9a-fA-F]{40}")
        val REDIRECTS: Set<Int> = setOf(301, 302, 303, 307, 308)
        const val MAX_BYTES: Long = 128L * 1024L * 1024L
        const val MAX_REDIRECTS: Int = 5

        fun directoryUri(uri: URI): URI = if (uri.toString().endsWith('/')) uri else URI.create("$uri/")
    }
}
