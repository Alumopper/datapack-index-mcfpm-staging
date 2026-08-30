package moe.afox.mcfpm.publish.nexus

import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.repository.maven.MavenPackageRepository
import moe.afox.mcfpm.repository.maven.MavenRepositoryCredentials
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

public data class NexusTarget(
    public val repositoryId: String,
    public val repositoryName: String,
    public val mavenRepositoryUri: URI,
    public val componentsApiUri: URI,
) {
    init {
        require(repositoryId.isNotBlank()) { "Nexus repository ID is required" }
        require(repositoryName.isNotBlank()) { "Nexus repository name is required" }
        require(mavenRepositoryUri.scheme in setOf("http", "https")) { "Nexus Maven repository must use HTTP(S)" }
        require(componentsApiUri.scheme in setOf("http", "https")) { "Nexus Components API must use HTTP(S)" }
    }

    public companion object {
        public fun derive(
            repositoryId: String,
            mavenRepositoryUri: URI,
            componentsApiOverride: URI? = null,
            repositoryNameOverride: String? = null,
        ): NexusTarget {
            val normalized = directoryUri(mavenRepositoryUri)
            val marker = "/repository/"
            val markerIndex = normalized.path.indexOf(marker)
            require(markerIndex >= 0 || componentsApiOverride != null) {
                "Cannot derive Nexus Components API from $normalized; configure repository.$repositoryId.nexus-api"
            }
            val repositoryName = repositoryNameOverride ?: run {
                val suffix = normalized.path.substring(markerIndex + marker.length).trim('/')
                require(suffix.isNotEmpty() && '/' !in suffix) {
                    "Cannot derive a single Nexus repository name from $normalized; configure repository.$repositoryId.nexus-name"
                }
                suffix
            }
            val api = componentsApiOverride ?: URI(
                normalized.scheme,
                normalized.userInfo,
                normalized.host,
                normalized.port,
                normalized.path.substring(0, markerIndex) + "/service/rest/v1/components",
                null,
                null,
            )
            val query = "repository=" + URLEncoder.encode(repositoryName, StandardCharsets.UTF_8)
            val apiWithRepository = URI(api.scheme, api.userInfo, api.host, api.port, api.path, query, null)
            return NexusTarget(repositoryId, repositoryName, normalized, apiWithRepository)
        }

        private fun directoryUri(uri: URI): URI =
            if (uri.toString().endsWith('/')) uri.normalize() else URI.create("${uri.normalize()}/")
    }
}

public data class NexusPublication(
    public val manifest: PackageManifest,
    public val payload: ByteArray,
    public val projectUri: URI,
) {
    init {
        require(manifest.artifacts.size == 1) { "Nexus import publication requires exactly one payload" }
        val artifact = manifest.artifacts.single()
        require(!artifact.executable) { "GitHub import does not publish executable payloads" }
        require(artifact.extension == "zip") { "GitHub import publishes ZIP payloads only" }
        require(artifact.size == payload.size.toLong()) { "Payload size does not match its descriptor" }
        require(artifact.sha256 == Hashing.sha256(payload)) { "Payload SHA-256 does not match its descriptor" }
    }
}

public enum class NexusPublishState {
    UPLOADED,
    ALREADY_PRESENT,
    RECONCILED,
    DRY_RUN,
}

public data class NexusPublishResult(
    public val state: NexusPublishState,
    public val descriptorSha256: String,
)

public class NexusComponentPublisher(
    private val target: NexusTarget,
    private val credentials: MavenRepositoryCredentials,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    public fun publish(
        publication: NexusPublication,
        dryRun: Boolean = false,
    ): NexusPublishResult {
        require(!publication.manifest.version.toString().endsWith("-SNAPSHOT", ignoreCase = true)) {
            "Nexus Components API does not support Maven snapshot uploads; use an immutable release version"
        }
        val descriptor = CanonicalJson.encodeManifest(publication.manifest)
        val descriptorSha256 = Hashing.sha256(descriptor)
        when (existing(publication, descriptor)) {
            Existing.IDENTICAL -> return NexusPublishResult(NexusPublishState.ALREADY_PRESENT, descriptorSha256)
            Existing.DIFFERENT -> {
                throw IllegalArgumentException("Release coordinate already contains different bytes and cannot be replaced")
            }
            Existing.ABSENT -> Unit
        }
        if (dryRun) return NexusPublishResult(NexusPublishState.DRY_RUN, descriptorSha256)

        val body = multipart(publication, descriptor)
        val request = credentials.applyTo(
            HttpRequest.newBuilder(target.componentsApiUri)
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "multipart/form-data; boundary=${body.boundary}")
                .header("Accept", "application/json")
                .header("User-Agent", "mcfpm/1"),
        ).POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes)).build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (exception: Exception) {
            if (existing(publication, descriptor) == Existing.IDENTICAL) {
                return NexusPublishResult(NexusPublishState.RECONCILED, descriptorSha256)
            }
            throw IllegalStateException("Nexus upload outcome is unknown: ${exception.message}", exception)
        }
        require(response.statusCode() == 204) {
            val details = response.body().toString(StandardCharsets.UTF_8)
                .take(2_048)
                .replace(Regex("[\\p{Cc}&&[^\\r\\n\\t]]"), "?")
                .trim()
            "Nexus Components API returned HTTP ${response.statusCode()}" +
                details.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()
        }
        require(existing(publication, descriptor) == Existing.IDENTICAL) {
            "Nexus returned success but the published descriptor or payload could not be read back exactly"
        }
        return NexusPublishResult(NexusPublishState.UPLOADED, descriptorSha256)
    }

    private fun existing(publication: NexusPublication, expectedDescriptor: ByteArray): Existing {
        val manifest = publication.manifest
        val repository = MavenPackageRepository(
            target.repositoryId,
            target.mavenRepositoryUri,
            httpClient,
            credentials,
        )
        val existing = when (val result = repository.manifest(manifest.packageId, manifest.version)) {
            is McfpmResult.Success -> result.value
            is McfpmResult.Failure -> {
                val messages = result.diagnostics.joinToString("; ") { it.message }
                if ("HTTP 404" in messages) return Existing.ABSENT
                throw IllegalStateException(messages)
            }
        }
        if (!existing.descriptorBytes.contentEquals(expectedDescriptor)) return Existing.DIFFERENT
        val artifact = manifest.artifacts.single()
        val uri = repository.artifactUri(manifest.packageId, manifest.version, artifact)
        val request = credentials.applyTo(
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "mcfpm/1"),
        ).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() in 200..299) { "Unable to verify existing Nexus payload: HTTP ${response.statusCode()}" }
        val bytes = response.body()
        if (bytes.size.toLong() != artifact.size || Hashing.sha256(bytes) != artifact.sha256) return Existing.DIFFERENT
        return Existing.IDENTICAL
    }

    private fun multipart(publication: NexusPublication, descriptor: ByteArray): MultipartBody {
        val manifest = publication.manifest
        val artifact = manifest.artifacts.single()
        val boundary = "mcfpm-${UUID.randomUUID()}"
        val parts = listOf(
            textPart(boundary, "maven2.groupId", manifest.packageId.group),
            textPart(boundary, "maven2.artifactId", manifest.packageId.name),
            textPart(boundary, "maven2.version", manifest.version.toString()),
            textPart(boundary, "maven2.generate-pom", "false"),
            filePart(boundary, "maven2.asset1", "${manifest.packageId.name}-${manifest.version}.mcfpkg", "application/json", descriptor),
            textPart(boundary, "maven2.asset1.extension", "mcfpkg"),
            filePart(boundary, "maven2.asset2", "${manifest.packageId.name}-${manifest.version}-${artifact.classifier}.zip", "application/zip", publication.payload),
            textPart(boundary, "maven2.asset2.extension", "zip"),
            textPart(boundary, "maven2.asset2.classifier", artifact.classifier),
            filePart(boundary, "maven2.asset3", "${manifest.packageId.name}-${manifest.version}.pom", "application/xml", pom(publication)),
            textPart(boundary, "maven2.asset3.extension", "pom"),
        )
        val ending = "--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        val size = parts.sumOf(ByteArray::size) + ending.size
        val bytes = ByteArray(size)
        var offset = 0
        (parts + ending).forEach { part ->
            part.copyInto(bytes, offset)
            offset += part.size
        }
        return MultipartBody(boundary, bytes)
    }

    private fun pom(publication: NexusPublication): ByteArray {
        val manifest = publication.manifest
        val license = xml(manifest.license)
        return """<?xml version="1.0" encoding="UTF-8"?>
            |<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
            |  <modelVersion>4.0.0</modelVersion>
            |  <groupId>${xml(manifest.packageId.group)}</groupId>
            |  <artifactId>${xml(manifest.packageId.name)}</artifactId>
            |  <version>${xml(manifest.version.toString())}</version>
            |  <packaging>mcfpkg</packaging>
            |  <name>${xml(manifest.packageId.value)}</name>
            |  <description>Statically audited Minecraft pack imported from GitHub by Mcfpm.</description>
            |  <url>${xml(publication.projectUri.toString())}</url>
            |  <licenses><license><name>$license</name><url>https://spdx.org/licenses/$license.html</url></license></licenses>
            |</project>
            |""".trimMargin().toByteArray(StandardCharsets.UTF_8)
    }

    private fun textPart(boundary: String, name: String, value: String): ByteArray =
        ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            .toByteArray(StandardCharsets.UTF_8)

    private fun filePart(boundary: String, name: String, filename: String, contentType: String, bytes: ByteArray): ByteArray {
        val header = (
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n" +
                "Content-Type: $contentType\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        val trailer = "\r\n".toByteArray(StandardCharsets.UTF_8)
        return header + bytes + trailer
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private enum class Existing { ABSENT, IDENTICAL, DIFFERENT }
    private data class MultipartBody(val boundary: String, val bytes: ByteArray)
}
