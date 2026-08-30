package moe.afox.mcfpm.source.url

import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.PayloadType
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UrlSourceClientTest {
    @Test
    fun `follows HTTPS redirects and imports a resource pack`() {
        val payload = resourcePack()
        val http = QueueHttpClient(
            response(302, "https://cdn.example.test/pack.zip"),
            response(200, body = payload),
        )

        val candidate = UrlSourceClient(httpClient = http).import(
            UrlImportRequest(URI.create("https://downloads.example.test/start")),
        )

        assertEquals(URI.create("https://downloads.example.test/start"), candidate.requestUri)
        assertEquals(URI.create("https://cdn.example.test/pack.zip"), candidate.finalUri)
        assertEquals(PayloadType.MINECRAFT_RESOURCEPACK, candidate.payloadType)
        assertEquals(Hashing.sha256(payload), candidate.rawSha256)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `rejects HTTP sources and HTTPS downgrade redirects`() {
        val http = QueueHttpClient(response(302, "http://cdn.example.test/pack.zip"))
        assertFailsWith<IllegalArgumentException> {
            UrlSourceClient(httpClient = http).import(UrlImportRequest(URI.create("http://downloads.example.test/pack.zip")))
        }
        assertFailsWith<IllegalArgumentException> {
            UrlSourceClient(httpClient = http).import(UrlImportRequest(URI.create("https://downloads.example.test/start")))
        }
    }

    @Test
    fun `enforces redirect and expected hash limits`() {
        val redirects = (0..5).map { response(302, "https://downloads.example.test/$it") }
        assertFailsWith<UrlSourceException> {
            UrlSourceClient(httpClient = QueueHttpClient(*redirects.toTypedArray())).import(
                UrlImportRequest(URI.create("https://downloads.example.test/start")),
            )
        }

        val payload = resourcePack()
        val uppercase = UrlSourceClient(
            httpClient = QueueHttpClient(response(200, body = payload)),
        ).import(
            UrlImportRequest(
                URI.create("https://downloads.example.test/pack.zip"),
                expectedSha256 = Hashing.sha256(payload).uppercase(),
            ),
        )
        assertEquals(Hashing.sha256(payload), uppercase.rawSha256)

        assertFailsWith<UrlSourceIntegrityException> {
            UrlSourceClient(httpClient = QueueHttpClient(response(200, body = payload))).import(
                UrlImportRequest(URI.create("https://downloads.example.test/pack.zip"), expectedSha256 = "0".repeat(64)),
            )
        }
    }

    @Test
    fun `rejects a response larger than the configured limit`() {
        val payload = resourcePack()
        val client = UrlSourceClient(
            httpClient = QueueHttpClient(response(200, body = payload)),
            limits = UrlDownloadLimits(maxBytes = payload.size.toLong() - 1, maxRedirects = 5),
        )
        assertFailsWith<UrlSourceIntegrityException> {
            client.import(UrlImportRequest(URI.create("https://downloads.example.test/pack.zip")))
        }
    }

    private fun resourcePack(): ByteArray = ReproducibleZip.fromEntries(
        listOf(
            "pack.mcmeta" to """{"pack":{"pack_format":48,"description":"fixture"}}""".encodeToByteArray(),
            "assets/example/lang/en_us.json" to "{}".encodeToByteArray(),
        ),
    )

    private fun response(status: Int, location: String? = null, body: ByteArray = byteArrayOf()): HttpResponse<InputStream> =
        FakeResponse(
            status,
            location,
            ByteArrayInputStream(body),
        )

    private class QueueHttpClient(vararg responses: HttpResponse<InputStream>) : HttpClient() {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<HttpRequest>()

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(1))
        override fun followRedirects(): HttpClient.Redirect = HttpClient.Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()

        @Suppress("UNCHECKED_CAST")
        override fun <T> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            requests += request
            return responses.removeFirst() as HttpResponse<T>
        }

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException())

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException())
    }

    private class FakeResponse(
        private val status: Int,
        location: String?,
        private val stream: ByteArrayInputStream,
    ) : HttpResponse<InputStream> {
        private val headers = HttpHeaders.of(
            if (location == null) emptyMap() else mapOf("Location" to listOf(location)),
        ) { _, _ -> true }
        private val request = HttpRequest.newBuilder(URI.create("https://downloads.example.test/response")).build()

        override fun statusCode(): Int = status
        override fun request(): HttpRequest = request
        override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()
        override fun headers(): HttpHeaders = headers
        override fun body(): InputStream = stream
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = request.uri()
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
