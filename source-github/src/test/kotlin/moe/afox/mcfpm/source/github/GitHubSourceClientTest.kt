package moe.afox.mcfpm.source.github

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.PayloadType
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubSourceClientTest {
    @Test
    fun `imports a release asset and verifies GitHub digest`() {
        val payload = pack()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/repos/acme/demo/commits/v1.2.3") { it.json("""{"sha":"${"a".repeat(40)}"}""") }
        server.createContext("/repos/acme/demo/license") { it.json("""{"license":{"spdx_id":"MIT"}}""") }
        server.createContext("/repos/acme/demo/releases/tags/v1.2.3") { exchange ->
            exchange.json(
                """{"id":11,"assets":[{"id":7,"name":"demo.zip","size":${payload.size},"digest":"sha256:${Hashing.sha256(payload)}","browser_download_url":"https://github.com/acme/demo/releases/download/v1.2.3/demo.zip"}]}""",
            )
        }
        server.createContext("/repos/acme/demo/releases/assets/7") { it.bytes(payload, "application/zip") }
        server.start()
        try {
            val candidate = GitHubSourceClient(apiBaseUri = base(server)).import(
                GitHubImportRequest(GitHubRepository("acme", "demo"), "v1.2.3", asset = "demo.zip"),
            )
            assertEquals(7, candidate.assetId)
            assertEquals("MIT", candidate.license)
            assertEquals(PayloadType.MINECRAFT_DATAPACK, candidate.payloadType)
            assertEquals(Hashing.sha256(payload), candidate.rawSha256)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `pins archive to commit and strips authorization on a cross origin redirect`() {
        val downloadAuthorization = AtomicReference<String?>()
        val downloadServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val archive = ReproducibleZip.fromEntries(
            ReproducibleZip.readEntries(pack()).entries.map { (name, bytes) -> "demo-sha/datapack/$name" to bytes },
        )
        downloadServer.createContext("/archive.zip") { exchange ->
            downloadAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.bytes(archive, "application/zip")
        }
        downloadServer.start()
        val apiServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sha = "b".repeat(40)
        apiServer.createContext("/repos/acme/demo/commits/main") { it.json("""{"sha":"$sha"}""") }
        apiServer.createContext("/repos/acme/demo/license") { it.json("""{"license":{"spdx_id":"MIT"}}""") }
        apiServer.createContext("/repos/acme/demo/zipball/$sha") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${downloadServer.address.port}/archive.zip")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        apiServer.start()
        try {
            val candidate = GitHubSourceClient(apiBaseUri = base(apiServer)).import(
                GitHubImportRequest(
                    GitHubRepository("acme", "demo"),
                    "main",
                    mode = GitHubSourceMode.ARCHIVE,
                    subdirectory = "datapack",
                    token = "secret-token",
                ),
            )
            assertEquals(sha, candidate.commit)
            assertEquals("datapack", candidate.selectedRoot)
            assertNull(downloadAuthorization.get())
        } finally {
            apiServer.stop(0)
            downloadServer.stop(0)
        }
    }

    private fun pack(): ByteArray = ReproducibleZip.fromEntries(
        listOf(
            "pack.mcmeta" to """{"pack":{"pack_format":48,"description":"fixture"}}""".encodeToByteArray(),
            "data/example/function/load.mcfunction" to "say hello".encodeToByteArray(),
        ),
    )

    private fun base(server: HttpServer): URI = URI.create("http://127.0.0.1:${server.address.port}/")

    private fun HttpExchange.json(value: String) {
        bytes(value.toByteArray(StandardCharsets.UTF_8), "application/json")
    }

    private fun HttpExchange.bytes(value: ByteArray, contentType: String) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(200, value.size.toLong())
        responseBody.use { it.write(value) }
    }
}
