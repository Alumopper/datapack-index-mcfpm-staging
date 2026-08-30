package moe.afox.mcfpm.publish.central

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import moe.afox.mcfpm.model.McfpmResult
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CentralPortalClientTest {
    @Test
    fun `default validates without release and explicit release publishes`() {
        val uploads = AtomicInteger()
        val released = ConcurrentHashMap.newKeySet<String>()
        val credentials = CentralCredentials("user", "password")
        val expectedAuthorization = "Bearer " + Base64.getEncoder()
            .encodeToString("user:password".toByteArray(StandardCharsets.UTF_8))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/publisher/upload") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals(expectedAuthorization, exchange.requestHeaders.getFirst("Authorization"))
            assertTrue(exchange.requestURI.query.contains("publishingType=USER_MANAGED"))
            assertTrue(exchange.requestBody.readAllBytes().decodeToString().contains("name=\"bundle\""))
            respond(exchange, 201, "deployment-${uploads.incrementAndGet()}")
        }
        server.createContext("/api/v1/publisher/status") { exchange ->
            val id = exchange.requestURI.query.substringAfter("id=")
            val state = if (id in released) "PUBLISHED" else "VALIDATED"
            respond(exchange, 200, """{"deploymentId":"$id","deploymentState":"$state"}""")
        }
        server.createContext("/api/v1/publisher/deployment/") { exchange ->
            released += exchange.requestURI.path.substringAfterLast('/')
            respond(exchange, 204, "")
        }
        server.start()
        try {
            val endpoint = URI.create("http://127.0.0.1:${server.address.port}/")
            val client = CentralPortalClient(endpoint)
            val validated = assertIs<McfpmResult.Success<CentralDeployment>>(
                client.publish(byteArrayOf(1, 2, 3), credentials, "validation", pollInterval = Duration.ZERO),
            ).value
            assertEquals(DeploymentState.VALIDATED, validated.state)
            assertFalse(validated.released)

            val published = assertIs<McfpmResult.Success<CentralDeployment>>(
                client.publish(byteArrayOf(4, 5, 6), credentials, "release", release = true, pollInterval = Duration.ZERO),
            ).value
            assertEquals(DeploymentState.PUBLISHED, published.state)
            assertTrue(published.released)
            assertEquals(setOf("deployment-2"), released)
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}
