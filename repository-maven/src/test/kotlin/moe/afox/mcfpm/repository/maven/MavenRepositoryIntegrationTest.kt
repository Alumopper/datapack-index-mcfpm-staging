package moe.afox.mcfpm.repository.maven

import com.sun.net.httpserver.HttpServer
import moe.afox.mcfpm.core.ArtifactGraphFetcher
import moe.afox.mcfpm.core.ArtifactVerifier
import moe.afox.mcfpm.core.ContentAddressedCache
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.InMemoryTrustStore
import moe.afox.mcfpm.core.PubGrubResolver
import moe.afox.mcfpm.core.RepositoryRegistry
import moe.afox.mcfpm.core.ResolveRequest
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.core.RootRequirement
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.ConsumerProfile
import moe.afox.mcfpm.model.Dependency
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.VersionRequirement
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MavenRepositoryIntegrationTest {
    @Test
    fun `central fallback is retained as the locked source during a fresh fetch`() {
        val temporary = createTempDirectory("mcfpm-maven-fallback")
        val afoxRoot = Files.createDirectories(temporary.resolve("afox"))
        val centralRoot = Files.createDirectories(temporary.resolve("central"))
        val packageId = PackageId.parse("example:fallback")
        val version = SemVer.parse("1.0.0")
        publishedPackage(centralRoot, packageId.value, PayloadType.MINECRAFT_DATAPACK)

        val afox = MavenPackageRepository("afox", afoxRoot.toUri())
        val central = MavenPackageRepository("central", centralRoot.toUri())
        val registry = RepositoryRegistry(
            listOf(afox, central),
            defaultRepositoryId = "afox",
            defaultRepositoryIds = listOf("afox", "central"),
        )
        val graph = assertIs<McfpmResult.Success<ResolvedGraph>>(
            PubGrubResolver(registry).resolve(
                ResolveRequest(listOf(RootRequirement(packageId, VersionRequirement.exact(version)))),
            ),
        ).value

        assertEquals(central.baseUri.toString(), graph.packages.single().repositoryUrl)
        val fetched = assertIs<McfpmResult.Success<moe.afox.mcfpm.core.FetchedGraph>>(
            ArtifactGraphFetcher(registry, ContentAddressedCache(temporary.resolve("cache"))).fetch(graph),
        ).value
        assertEquals(1, fetched.artifacts.size)
    }

    @Test
    fun `resolves fetches verifies and then works offline after repository disappears`() {
        val temporary = createTempDirectory("mcfpm-maven-integration")
        val repositoryRoot = temporary.resolve("repository")
        Files.createDirectories(repositoryRoot)
        val c = publishedPackage(repositoryRoot, "example:c", PayloadType.MCFPP_LIBRARY)
        val b = publishedPackage(
            repositoryRoot,
            "example:b",
            PayloadType.MINECRAFT_RESOURCEPACK,
            listOf(Dependency(c.packageId, VersionRequirement.parse("^1.0.0"))),
        )
        val a = publishedPackage(
            repositoryRoot,
            "example:a",
            PayloadType.MINECRAFT_DATAPACK,
            listOf(Dependency(b.packageId, VersionRequirement.parse("^1.0.0"))),
        )
        val repository = MavenPackageRepository("local", repositoryRoot.toUri())
        val registry = RepositoryRegistry(listOf(repository), defaultRepositoryId = repository.id)
        val resolver = PubGrubResolver(registry)

        val graph = assertIs<McfpmResult.Success<ResolvedGraph>>(
            resolver.resolve(
                ResolveRequest(
                    listOf(RootRequirement(a.packageId, VersionRequirement.parse("^1.0.0"))),
                    ConsumerProfile.ALL,
                ),
            ),
        ).value
        assertEquals(listOf("example:c", "example:b", "example:a"), graph.loadOrder.map { it.packageId.value })

        val fetcher = ArtifactGraphFetcher(registry, ContentAddressedCache(temporary.resolve("cache")))
        val fetched = assertIs<McfpmResult.Success<moe.afox.mcfpm.core.FetchedGraph>>(
            fetcher.fetch(graph),
        ).value
        assertIs<McfpmResult.Success<*>>(ArtifactVerifier(InMemoryTrustStore()).verify(fetched))

        Files.move(repositoryRoot, temporary.resolve("repository-removed"), StandardCopyOption.ATOMIC_MOVE)
        val offline = assertIs<McfpmResult.Success<moe.afox.mcfpm.core.FetchedGraph>>(
            fetcher.fetch(graph, offline = true),
        ).value
        assertEquals(3, offline.artifacts.size)
        assertTrue(offline.artifacts.values.all(Files::isRegularFile))
    }

    @Test
    fun `artifact URI follows Maven classifier layout`() {
        val repository = MavenPackageRepository("central", MavenPackageRepository.MAVEN_CENTRAL_URI)
        val descriptor = ArtifactDescriptor(
            PayloadType.MINECRAFT_DATAPACK,
            "datapack",
            sha256 = "a".repeat(64),
            size = 1,
        )

        assertEquals(
            "https://repo.maven.apache.org/maven2/io/github/example/demo/1.2.3/demo-1.2.3-datapack.zip",
            repository.artifactUri(PackageId.parse("io.github.example:demo"), SemVer.parse("1.2.3"), descriptor).toString(),
        )
    }

    @Test
    fun `authenticated timestamped snapshot resolves fetches verifies and works offline`() {
        val temporary = createTempDirectory("mcfpm-authenticated-snapshot")
        val packageId = PackageId.parse("example.private:github-pack")
        val version = SemVer.parse("1.0.0-SNAPSHOT")
        val timestampedVersion = "1.0.0-20260828.123456-1"
        val payload = ReproducibleZip.fromEntries(
            listOf(
                "pack.mcmeta" to
                    """{"pack":{"pack_format":48,"description":"Authenticated snapshot"}}""".encodeToByteArray(),
                "data/example/functions/load.mcfunction" to "say authenticated".encodeToByteArray(),
            ),
        )
        val artifact = ArtifactDescriptor(
            type = PayloadType.MINECRAFT_DATAPACK,
            classifier = "datapack",
            sha256 = Hashing.sha256(payload),
            size = payload.size.toLong(),
        )
        val manifest = PackageManifest(
            packageId = packageId,
            version = version,
            license = "MIT",
            artifacts = listOf(artifact),
        )
        val descriptor = CanonicalJson.encodeManifest(manifest)
        val groupPath = packageId.group.replace('.', '/')
        val packagePath = "/$groupPath/${packageId.name}"
        val versionPath = "$packagePath/$version"
        val resources = mapOf(
            "$packagePath/maven-metadata.xml" to packageMetadata(packageId, version),
            "$versionPath/maven-metadata.xml" to snapshotMetadata(packageId, version, timestampedVersion),
            "$versionPath/${packageId.name}-$timestampedVersion.mcfpkg" to descriptor,
            "$versionPath/${packageId.name}-$timestampedVersion-datapack.zip" to payload,
        )
        val username = "test-user"
        val password = "test-password"
        val authorization = "Basic " + Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
        )
        val requests = ConcurrentLinkedQueue<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val receivedAuthorization = exchange.requestHeaders.getFirst("Authorization")
            requests += path to receivedAuthorization
            val body = resources[path]
            val status = when {
                receivedAuthorization != authorization -> 401
                body == null -> 404
                else -> 200
            }
            val response = body.takeIf { status == 200 } ?: byteArrayOf()
            exchange.sendResponseHeaders(status, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        try {
            val baseUri = URI.create("http://127.0.0.1:${server.address.port}/")
            val credentials = MavenRepositoryCredentials(username, password)
            val repository = MavenPackageRepository("private", baseUri, credentials = credentials)
            val registry = RepositoryRegistry(listOf(repository), defaultRepositoryId = repository.id)
            val graph = assertIs<McfpmResult.Success<ResolvedGraph>>(
                PubGrubResolver(registry).resolve(
                    ResolveRequest(
                        listOf(RootRequirement(packageId, VersionRequirement.exact(version))),
                        ConsumerProfile.MINECRAFT_DATAPACK,
                    ),
                ),
            ).value

            val fetcher = ArtifactGraphFetcher(registry, ContentAddressedCache(temporary.resolve("cache")))
            val fetched = assertIs<McfpmResult.Success<moe.afox.mcfpm.core.FetchedGraph>>(
                fetcher.fetch(graph),
            ).value
            assertIs<McfpmResult.Success<*>>(ArtifactVerifier(InMemoryTrustStore()).verify(fetched))
            assertTrue(requests.any { it.first.endsWith("$timestampedVersion.mcfpkg") })
            assertTrue(requests.any { it.first.endsWith("$timestampedVersion-datapack.zip") })
            assertTrue(requests.all { it.second == authorization })
            assertTrue(repository.requestHeaders(URI.create("https://example.invalid/artifact.zip")).isEmpty())
            assertTrue(username !in credentials.toString())
            assertTrue(password !in credentials.toString())

            val unauthenticated = MavenPackageRepository("unauthenticated", baseUri)
            assertIs<McfpmResult.Failure>(unauthenticated.versions(packageId))

            server.stop(0)
            val freshRepository = MavenPackageRepository("private", baseUri, credentials = credentials)
            val freshRegistry = RepositoryRegistry(listOf(freshRepository), defaultRepositoryId = freshRepository.id)
            val freshFetcher = ArtifactGraphFetcher(freshRegistry, ContentAddressedCache(temporary.resolve("cache")))
            assertIs<McfpmResult.Success<*>>(freshFetcher.fetch(graph, offline = true))
        } finally {
            server.stop(0)
        }
    }

    private fun publishedPackage(
        repositoryRoot: Path,
        id: String,
        type: PayloadType,
        dependencies: List<Dependency> = emptyList(),
    ): PackageManifest {
        val packageId = PackageId.parse(id)
        val version = SemVer.parse("1.0.0")
        val classifier = when (type) {
            PayloadType.MINECRAFT_DATAPACK -> "datapack"
            PayloadType.MINECRAFT_RESOURCEPACK -> "resourcepack"
            PayloadType.MCFPP_LIBRARY -> "mcfpp"
            else -> "payload"
        }
        val payload = if (type == PayloadType.MCFPP_LIBRARY) {
            ReproducibleZip.fromEntries(
                listOf(
                    "bin.mclib" to "library".encodeToByteArray(),
                    "module.json" to "{}".encodeToByteArray(),
                ),
            )
        } else {
            ReproducibleZip.fromEntries(
                listOf(
                    "pack.mcmeta" to """{"pack":{"pack_format":48,"description":"$id"}}""".encodeToByteArray(),
                    "assets/example/data.txt" to id.encodeToByteArray(),
                ),
            )
        }
        val artifact = ArtifactDescriptor(
            type = type,
            classifier = classifier,
            sha256 = Hashing.sha256(payload),
            size = payload.size.toLong(),
        )
        val manifest = PackageManifest(
            packageId = packageId,
            version = version,
            license = "Apache-2.0",
            dependencies = dependencies,
            artifacts = listOf(artifact),
        )
        val versionDirectory = repositoryRoot
            .resolve(packageId.group.replace('.', '/'))
            .resolve(packageId.name)
            .resolve(version.toString())
        Files.createDirectories(versionDirectory)
        Files.write(versionDirectory.resolve("${packageId.name}-$version.mcfpkg"), CanonicalJson.encodeManifest(manifest))
        Files.write(versionDirectory.resolve("${packageId.name}-$version-$classifier.zip"), payload)
        return manifest
    }

    private fun packageMetadata(packageId: PackageId, version: SemVer): ByteArray = """
        <metadata>
          <groupId>${packageId.group}</groupId>
          <artifactId>${packageId.name}</artifactId>
          <versioning><versions><version>$version</version></versions></versioning>
        </metadata>
    """.trimIndent().encodeToByteArray()

    private fun snapshotMetadata(
        packageId: PackageId,
        version: SemVer,
        timestampedVersion: String,
    ): ByteArray = """
        <metadata>
          <groupId>${packageId.group}</groupId>
          <artifactId>${packageId.name}</artifactId>
          <version>$version</version>
          <versioning>
            <snapshot><timestamp>20260828.123456</timestamp><buildNumber>1</buildNumber></snapshot>
            <snapshotVersions>
              <snapshotVersion><extension>mcfpkg</extension><value>$timestampedVersion</value></snapshotVersion>
              <snapshotVersion><classifier>datapack</classifier><extension>zip</extension><value>$timestampedVersion</value></snapshotVersion>
            </snapshotVersions>
          </versioning>
        </metadata>
    """.trimIndent().encodeToByteArray()
}
