package moe.afox.mcfpm.e2e

import moe.afox.mcfpm.core.ArtifactGraphFetcher
import moe.afox.mcfpm.core.ArtifactVerifier
import moe.afox.mcfpm.core.ContentAddressedCache
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.InMemoryTrustStore
import moe.afox.mcfpm.core.PubGrubResolver
import moe.afox.mcfpm.core.RepositoryRegistry
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.core.ResolveRequest
import moe.afox.mcfpm.core.RootRequirement
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.ArtifactSource
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
import moe.afox.mcfpm.repository.maven.MavenPackageRepository
import moe.afox.mcfpm.repository.maven.MavenRepositoryCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_ARCHIVE_BYTES: Int = 16 * 1024 * 1024
private const val MAX_EXTRACTED_BYTES: Long = 64L * 1024L * 1024L
private const val MAX_ENTRY_BYTES: Int = 16 * 1024 * 1024

private data class GitHubFixture(
    val packageId: PackageId,
    val projectUrl: String,
    val commit: String,
    val archiveSha256: String,
    val packPrefix: String,
    val outputName: String,
    val dependencies: List<PackageId> = emptyList(),
) {
    val archiveUri: URI = URI.create("https://codeload.github.com/${projectUrl.removePrefix("https://github.com/")}/zip/$commit")
}

private val noCreeperGriefing = GitHubFixture(
    packageId = PackageId.parse("io.github.hallettj:no-creeper-griefing"),
    projectUrl = "https://github.com/hallettj/no_creeper_griefing",
    commit = "79106469e359491f195628360ab50adf03c96965",
    archiveSha256 = "a0517c71980c4eabfc7c67e285915d5a51999593be855fecd184aca5f126bacc",
    packPrefix = "no_creeper_griefing-79106469e359491f195628360ab50adf03c96965/datapack/",
    outputName = "no-creeper-griefing",
)

private val lifeSteal = GitHubFixture(
    packageId = PackageId.parse("io.github.jairussw:lifesteal"),
    projectUrl = "https://github.com/JairusSW/lifesteal",
    commit = "0bdf4d593f7af1c328e3226e526684e6e8405e8d",
    archiveSha256 = "e5d1165db2967b1be0c13be8b09716da7df5dce9ac2915a9c40fc42e85620d30",
    packPrefix = "lifesteal-0bdf4d593f7af1c328e3226e526684e6e8405e8d/",
    outputName = "lifesteal",
    dependencies = listOf(noCreeperGriefing.packageId),
)

private val fixtures: List<GitHubFixture> = listOf(noCreeperGriefing, lifeSteal)

public fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Expected prepare or verify mode" }
    when (args[0]) {
        "prepare" -> {
            require(args.size == 3) { "prepare requires VERSION OUTPUT_DIRECTORY" }
            prepareFixtures(SemVer.parse(args[1]), Path.of(args[2]))
        }
        "verify" -> {
            require(args.size == 4) { "verify requires VERSION REPOSITORY_URL OUTPUT_DIRECTORY" }
            verifyPublishedFixtures(SemVer.parse(args[1]), URI.create(args[2]), Path.of(args[3]))
        }
        else -> error("Unknown mode: ${args[0]}")
    }
}

private fun prepareFixtures(version: SemVer, outputDirectory: Path) {
    require(version.toString().endsWith("-SNAPSHOT")) { "Private Nexus E2E fixtures must use a snapshot version" }
    Files.createDirectories(outputDirectory)
    fixtures.forEach { fixture ->
        val archive = download(fixture.archiveUri)
        require(archive.size <= MAX_ARCHIVE_BYTES) { "GitHub archive is unexpectedly large for ${fixture.packageId}" }
        require(Hashing.sha256(archive) == fixture.archiveSha256) {
            "GitHub archive checksum mismatch for ${fixture.packageId} at ${fixture.commit}"
        }
        val entries = extractPackEntries(archive, fixture)
        require(entries.any { it.first == "pack.mcmeta" }) { "${fixture.packageId} does not contain pack.mcmeta" }
        require(entries.any { it.first.startsWith("data/") }) { "${fixture.packageId} does not contain datapack data" }
        val payload = ReproducibleZip.fromEntries(entries)
        val artifact = ArtifactDescriptor(
            type = PayloadType.MINECRAFT_DATAPACK,
            classifier = "datapack",
            sha256 = Hashing.sha256(payload),
            size = payload.size.toLong(),
            source = ArtifactSource(
                kind = "github-archive",
                uri = fixture.projectUrl,
                immutableVersion = fixture.commit,
                redistributionLicense = "MIT",
            ),
        )
        val manifest = PackageManifest(
            packageId = fixture.packageId,
            version = version,
            license = "MIT",
            dependencies = fixture.dependencies.map { dependency ->
                Dependency(dependency, VersionRequirement.exact(version))
            },
            artifacts = listOf(artifact),
        )
        Files.write(outputDirectory.resolve("${fixture.outputName}-datapack.zip"), payload)
        Files.write(outputDirectory.resolve("${fixture.outputName}.mcfpkg"), CanonicalJson.encodeManifest(manifest))
    }
    Files.write(
        outputDirectory.resolve("fixture-report.json"),
        CanonicalJson.encode(
            JsonElement.serializer(),
            JsonObject(
                mapOf(
                    "fixtures" to JsonArray(fixtures.map { JsonPrimitive("${it.packageId}@${it.commit}") }),
                    "version" to JsonPrimitive(version.toString()),
                ),
            ),
        ),
    )
}

private fun verifyPublishedFixtures(version: SemVer, repositoryUri: URI, outputDirectory: Path) {
    val username = requiredEnvironment("MCFPM_E2E_NEXUS_USERNAME")
    val password = requiredEnvironment("MCFPM_E2E_NEXUS_PASSWORD")
    val repository = MavenPackageRepository(
        id = "privateNexus",
        baseUri = repositoryUri,
        credentials = MavenRepositoryCredentials(username, password),
    )
    val registry = RepositoryRegistry(listOf(repository), defaultRepositoryId = repository.id)
    val graph = requireSuccess(
        PubGrubResolver(registry).resolve(
            ResolveRequest(
                roots = listOf(RootRequirement(lifeSteal.packageId, VersionRequirement.exact(version))),
                consumerProfile = ConsumerProfile.MINECRAFT_DATAPACK,
            ),
        ),
    )
    require(graph.packages.map { it.packageId }.toSet() == fixtures.map { it.packageId }.toSet()) {
        "Private Nexus resolution did not return both pinned GitHub fixtures"
    }
    val cache = ContentAddressedCache(outputDirectory.resolve("cache"))
    val fetcher = ArtifactGraphFetcher(registry, cache)
    val online = requireSuccess(fetcher.fetch(graph))
    requireSuccess(ArtifactVerifier(InMemoryTrustStore()).verify(online))
    val offline = requireSuccess(fetcher.fetch(graph, offline = true))
    require(offline.artifacts.size == fixtures.size) { "Offline cache did not contain every fixture" }
    require(offline.artifacts.values.all(Files::isRegularFile)) { "Offline cache contains a missing artifact" }

    Files.write(
        outputDirectory.resolve("e2e-report.json"),
        CanonicalJson.encode(
            JsonElement.serializer(),
            JsonObject(
                mapOf(
                    "artifactCount" to JsonPrimitive(offline.artifacts.size),
                    "offlineVerified" to JsonPrimitive(true),
                    "packages" to JsonArray(graph.packages.map { JsonPrimitive("${it.packageId}@${it.version}") }.sortedBy { it.content }),
                    "repository" to JsonPrimitive(repository.baseUri.toString()),
                    "version" to JsonPrimitive(version.toString()),
                ),
            ),
        ),
    )
    println("Private Nexus E2E verified ${graph.packages.size} packages and ${offline.artifacts.size} offline artifacts.")
}

private fun download(uri: URI): ByteArray {
    val request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofMinutes(2))
        .header("Accept", "application/zip")
        .header("User-Agent", "mcfpm-private-nexus-e2e/1")
        .GET()
        .build()
    val response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray())
    require(response.statusCode() in 200..299) { "GitHub returned HTTP ${response.statusCode()} for $uri" }
    return response.body()
}

private fun extractPackEntries(archive: ByteArray, fixture: GitHubFixture): List<Pair<String, ByteArray>> {
    val entries = mutableListOf<Pair<String, ByteArray>>()
    var extractedBytes = 0L
    ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val archivePath = entry.name
            require('\\' !in archivePath && '\u0000' !in archivePath) { "Unsafe GitHub archive entry: $archivePath" }
            if (!entry.isDirectory && archivePath.startsWith(fixture.packPrefix)) {
                val packPath = archivePath.removePrefix(fixture.packPrefix)
                require(packPath.isNotEmpty() && !packPath.startsWith('/') && packPath.split('/').none { it == ".." }) {
                    "Unsafe datapack entry: $archivePath"
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var entryBytes = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    entryBytes += count
                    require(entryBytes <= MAX_ENTRY_BYTES) { "Datapack entry is unexpectedly large: $packPath" }
                    output.write(buffer, 0, count)
                }
                extractedBytes += entryBytes
                require(extractedBytes <= MAX_EXTRACTED_BYTES) { "Datapack archive expands beyond the E2E safety limit" }
                entries += packPath to output.toByteArray()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    require(entries.map { it.first }.distinct().size == entries.size) { "Datapack archive contains duplicate entries" }
    return entries.sortedBy { it.first }
}

private fun requiredEnvironment(name: String): String =
    System.getenv(name)?.takeIf(String::isNotEmpty) ?: error("Required environment variable $name is missing")

private fun <T> requireSuccess(result: McfpmResult<T>): T = when (result) {
    is McfpmResult.Success -> result.value
    is McfpmResult.Failure -> error(result.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" })
}

private val HTTP: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()
