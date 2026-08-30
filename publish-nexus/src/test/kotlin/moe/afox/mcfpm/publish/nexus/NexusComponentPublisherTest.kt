package moe.afox.mcfpm.publish.nexus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.repository.maven.MavenRepositoryCredentials
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NexusComponentPublisherTest {
    @Test
    fun `uploads descriptor payload and pom through the Components API`() {
        val publication = publication()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val posted = AtomicReference<ByteArray>()
        val authorization = AtomicReference<String>()
        val uploaded = AtomicBoolean()
        server.createContext("/repository/releases/") { exchange ->
            when {
                !uploaded.get() -> exchange.empty(404)
                exchange.requestURI.path.endsWith(".mcfpkg") ->
                    exchange.bytes(CanonicalJson.encodeManifest(publication.manifest), "application/json")
                exchange.requestURI.path.endsWith("-datapack.zip") -> exchange.bytes(publication.payload, "application/zip")
                else -> exchange.empty(404)
            }
        }
        server.createContext("/service/rest/v1/components") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            posted.set(exchange.requestBody.readAllBytes())
            uploaded.set(true)
            exchange.empty(204)
        }
        server.start()
        try {
            val result = publisher(server).publish(publication)
            assertEquals(NexusPublishState.UPLOADED, result.state)
            assertEquals(
                "Basic " + Base64.getEncoder().encodeToString("user:password".toByteArray(StandardCharsets.UTF_8)),
                authorization.get(),
            )
            val body = posted.get().toString(StandardCharsets.ISO_8859_1)
            assertTrue(body.contains("name=\"maven2.groupId\"\r\n\r\nmoe.afox.fixtures"))
            assertTrue(body.contains("name=\"maven2.asset1.extension\"\r\n\r\nmcfpkg"))
            assertTrue(body.contains("filename=\"demo-1.2.3.mcfpkg\""))
            assertTrue(body.contains("filename=\"demo-1.2.3-datapack.zip\""))
            assertTrue(body.contains("filename=\"demo-1.2.3.pom\""))
            assertTrue(body.contains("name=\"maven2.asset2.classifier\"\r\n\r\ndatapack"))
            assertTrue(body.contains("name=\"maven2.asset3.extension\"\r\n\r\npom"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `identical coordinate is a no-op and differing release is rejected`() {
        val publication = publication()
        val descriptor = AtomicReference(CanonicalJson.encodeManifest(publication.manifest))
        val posts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/repository/releases/") { exchange ->
            when {
                exchange.requestURI.path.endsWith(".mcfpkg") -> exchange.bytes(descriptor.get(), "application/json")
                exchange.requestURI.path.endsWith("-datapack.zip") -> exchange.bytes(publication.payload, "application/zip")
                else -> exchange.empty(404)
            }
        }
        server.createContext("/service/rest/v1/components") { exchange ->
            posts.incrementAndGet()
            exchange.empty(204)
        }
        server.start()
        try {
            assertEquals(NexusPublishState.ALREADY_PRESENT, publisher(server).publish(publication).state)
            assertEquals(0, posts.get())

            descriptor.set(CanonicalJson.encodeManifest(publication.manifest.copy(license = "Apache-2.0")))
            assertFailsWith<IllegalArgumentException> { publisher(server).publish(publication) }
            assertEquals(0, posts.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects snapshots before contacting the Components API`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            val snapshot = publication().let { publication ->
                publication.copy(manifest = publication.manifest.copy(version = SemVer.parse("1.2.3-SNAPSHOT")))
            }
            val failure = assertFailsWith<IllegalArgumentException> { publisher(server).publish(snapshot) }
            assertTrue(failure.message.orEmpty().contains("does not support Maven snapshot"))
        } finally {
            server.stop(0)
        }
    }

    private fun publisher(server: HttpServer): NexusComponentPublisher {
        val repository = URI.create("http://127.0.0.1:${server.address.port}/repository/releases/")
        return NexusComponentPublisher(
            NexusTarget.derive("private-releases", repository),
            MavenRepositoryCredentials("user", "password"),
        )
    }

    private fun publication(): NexusPublication {
        val payload = ReproducibleZip.fromEntries(
            listOf(
                "pack.mcmeta" to """{"pack":{"pack_format":48,"description":"fixture"}}""".encodeToByteArray(),
                "data/example/function/load.mcfunction" to "say hello".encodeToByteArray(),
            ),
        )
        val manifest = PackageManifest(
            packageId = PackageId.parse("moe.afox.fixtures:demo"),
            version = SemVer.parse("1.2.3"),
            license = "MIT",
            artifacts = listOf(
                ArtifactDescriptor(
                    PayloadType.MINECRAFT_DATAPACK,
                    "datapack",
                    sha256 = Hashing.sha256(payload),
                    size = payload.size.toLong(),
                ),
            ),
        )
        return NexusPublication(manifest, payload, URI.create("https://github.com/acme/demo"))
    }

    private fun HttpExchange.bytes(value: ByteArray, contentType: String) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(200, value.size.toLong())
        responseBody.use { it.write(value) }
    }

    private fun HttpExchange.empty(status: Int) {
        sendResponseHeaders(status, -1)
        close()
    }
}
