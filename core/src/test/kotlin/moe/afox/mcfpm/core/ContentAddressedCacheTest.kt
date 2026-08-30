package moe.afox.mcfpm.core

import com.sun.net.httpserver.HttpServer
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.McfpmResult
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContentAddressedCacheTest {
    @Test
    fun `offline fetch succeeds after source is removed`() {
        val temporary = createTempDirectory("mcfpm-cache-test")
        val source = temporary.resolve("source.zip")
        val content = "immutable artifact".encodeToByteArray()
        Files.write(source, content)
        val sha256 = Hashing.sha256(content)
        val cache = ContentAddressedCache(temporary.resolve("cache"))

        val first = assertIs<McfpmResult.Success<CachedArtifact>>(
            cache.fetch(source.toUri(), sha256, content.size.toLong()),
        ).value
        Files.delete(source)
        val second = assertIs<McfpmResult.Success<CachedArtifact>>(
            cache.fetch(source.toUri(), sha256, content.size.toLong(), offline = true),
        ).value

        assertFalse(first.cacheHit)
        assertTrue(second.cacheHit)
        assertContentEquals(content, Files.readAllBytes(second.path))
    }

    @Test
    fun `corrupt cache is rejected offline and healed online`() {
        val temporary = createTempDirectory("mcfpm-cache-heal-test")
        val source = temporary.resolve("source.bin")
        val content = ByteArray(32) { it.toByte() }
        Files.write(source, content)
        val sha256 = Hashing.sha256(content)
        val cache = ContentAddressedCache(temporary.resolve("cache"))
        val cached = assertIs<McfpmResult.Success<CachedArtifact>>(
            cache.fetch(source.toUri(), sha256, content.size.toLong()),
        ).value
        Files.write(cached.path, byteArrayOf(1, 2, 3))

        val offline = assertIs<McfpmResult.Failure>(
            cache.fetch(source.toUri(), sha256, content.size.toLong(), offline = true),
        )
        assertEquals(DiagnosticCode.OFFLINE_MISS, offline.diagnostics.single().code)
        val healed = assertIs<McfpmResult.Success<CachedArtifact>>(
            cache.fetch(source.toUri(), sha256, content.size.toLong()),
        ).value

        assertContentEquals(content, Files.readAllBytes(healed.path))
    }

    @Test
    fun `cross origin redirect does not forward caller headers`() {
        val temporary = createTempDirectory("mcfpm-cache-redirect-test")
        val content = "redirected artifact".encodeToByteArray()
        val receivedAuthorization = AtomicReference<String?>()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/artifact") { exchange ->
            receivedAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        target.start()
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/artifact")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        source.start()

        try {
            val result = ContentAddressedCache(temporary.resolve("cache")).fetch(
                URI.create("http://127.0.0.1:${source.address.port}/redirect"),
                Hashing.sha256(content),
                content.size.toLong(),
                requestHeaders = mapOf("Authorization" to "Basic must-not-leak"),
            )
            assertIs<McfpmResult.Success<CachedArtifact>>(result)
            assertEquals(null, receivedAuthorization.get())
        } finally {
            source.stop(0)
            target.stop(0)
        }
    }
}
