package moe.afox.mcfpm.publish.central

import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class CentralCredentials(
    public val username: String,
    public val password: String,
) {
    public fun bearerToken(): String = Base64.getEncoder()
        .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
}

public enum class DeploymentState {
    PENDING,
    VALIDATING,
    VALIDATED,
    PUBLISHING,
    PUBLISHED,
    FAILED,
}

public data class CentralDeployment(
    public val id: String,
    public val state: DeploymentState,
    public val released: Boolean,
)

public class CentralPortalClient(
    endpoint: URI = URI.create("https://central.sonatype.com/"),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    private val endpoint: URI = if (endpoint.toString().endsWith('/')) endpoint else URI.create("$endpoint/")

    public fun publish(
        bundle: ByteArray,
        credentials: CentralCredentials,
        deploymentName: String,
        release: Boolean = false,
        timeout: Duration = Duration.ofMinutes(10),
        pollInterval: Duration = Duration.ofSeconds(2),
    ): McfpmResult<CentralDeployment> {
        val deploymentId = when (val upload = upload(bundle, credentials, deploymentName)) {
            is McfpmResult.Success -> upload.value
            is McfpmResult.Failure -> return upload
        }
        val deadline = System.nanoTime() + timeout.toNanos()
        var releaseRequested = false
        while (System.nanoTime() <= deadline) {
            val state = when (val status = status(deploymentId, credentials)) {
                is McfpmResult.Success -> status.value
                is McfpmResult.Failure -> return status
            }
            when (state) {
                DeploymentState.FAILED -> return failure("Central deployment $deploymentId failed validation")
                DeploymentState.VALIDATED -> {
                    if (!release) return McfpmResult.Success(CentralDeployment(deploymentId, state, released = false))
                    if (!releaseRequested) {
                        when (val released = release(deploymentId, credentials)) {
                            is McfpmResult.Success -> releaseRequested = true
                            is McfpmResult.Failure -> return released
                        }
                    }
                }
                DeploymentState.PUBLISHED ->
                    return McfpmResult.Success(CentralDeployment(deploymentId, state, released = true))
                else -> Unit
            }
            if (!pollInterval.isZero) {
                try {
                    Thread.sleep(pollInterval.toMillis())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return failure("Interrupted while waiting for Central deployment $deploymentId")
                }
            }
        }
        return failure("Timed out waiting for Central deployment $deploymentId")
    }

    public fun upload(
        bundle: ByteArray,
        credentials: CentralCredentials,
        deploymentName: String,
    ): McfpmResult<String> = runCatching {
        val boundary = "mcfpm-${UUID.randomUUID()}"
        val body = multipart(boundary, bundle)
        val name = URLEncoder.encode(deploymentName, StandardCharsets.UTF_8)
        val request = requestBuilder(
            endpoint.resolve("api/v1/publisher/upload?name=$name&publishingType=USER_MANAGED"),
            credentials,
        )
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        require(response.statusCode() == 201) { "Central upload returned HTTP ${response.statusCode()}: ${response.body()}" }
        response.body().trim().also { require(it.isNotBlank()) { "Central upload returned an empty deployment ID" } }
    }.fold(
        onSuccess = { McfpmResult.Success(it) },
        onFailure = { failure("Unable to upload Central bundle: ${it.message}") },
    )

    public fun status(
        deploymentId: String,
        credentials: CentralCredentials,
    ): McfpmResult<DeploymentState> = runCatching {
        val id = URLEncoder.encode(deploymentId, StandardCharsets.UTF_8)
        val request = requestBuilder(endpoint.resolve("api/v1/publisher/status?id=$id"), credentials)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        require(response.statusCode() in 200..299) { "Central status returned HTTP ${response.statusCode()}" }
        val json = CanonicalJson.format.parseToJsonElement(response.body()).jsonObject
        DeploymentState.valueOf(json.getValue("deploymentState").jsonPrimitive.content)
    }.fold(
        onSuccess = { McfpmResult.Success(it) },
        onFailure = { failure("Unable to read Central deployment status: ${it.message}") },
    )

    public fun release(
        deploymentId: String,
        credentials: CentralCredentials,
    ): McfpmResult<Unit> = runCatching {
        val request = requestBuilder(endpoint.resolve("api/v1/publisher/deployment/$deploymentId"), credentials)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        require(response.statusCode() == 204) { "Central release returned HTTP ${response.statusCode()}" }
        Unit
    }.fold(
        onSuccess = { McfpmResult.Success(Unit) },
        onFailure = { failure("Unable to release Central deployment: ${it.message}") },
    )

    private fun requestBuilder(uri: URI, credentials: CentralCredentials): HttpRequest.Builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer ${credentials.bearerToken()}")
            .header("User-Agent", "mcfpm/1")

    private fun multipart(boundary: String, bundle: ByteArray): ByteArray {
        val prefix = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"bundle\"; filename=\"central-bundle.zip\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        return prefix + bundle + suffix
    }

    private fun <T> failure(message: String): McfpmResult<T> = McfpmResult.Failure(
        listOf(
            Diagnostic(
                DiagnosticCode.PUBLISH_VALIDATION_FAILED,
                DiagnosticSeverity.ERROR,
                message,
            ),
        ),
    )
}
