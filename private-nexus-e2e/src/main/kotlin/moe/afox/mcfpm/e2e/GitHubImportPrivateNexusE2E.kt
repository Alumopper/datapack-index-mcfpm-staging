package moe.afox.mcfpm.e2e

import moe.afox.mcfpm.core.ArtifactGraphFetcher
import moe.afox.mcfpm.core.ArtifactVerifier
import moe.afox.mcfpm.core.ContentAddressedCache
import moe.afox.mcfpm.core.InMemoryTrustStore
import moe.afox.mcfpm.core.PackageManifestCodec
import moe.afox.mcfpm.core.PubGrubResolver
import moe.afox.mcfpm.core.RepositoryRegistry
import moe.afox.mcfpm.core.ResolveRequest
import moe.afox.mcfpm.core.RootRequirement
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.ArtifactSource
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.ConsumerProfile
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.ToolConfiguration
import moe.afox.mcfpm.model.VersionRequirement
import moe.afox.mcfpm.publish.nexus.NexusComponentPublisher
import moe.afox.mcfpm.publish.nexus.NexusPublication
import moe.afox.mcfpm.publish.nexus.NexusTarget
import moe.afox.mcfpm.repository.maven.MavenPackageRepository
import moe.afox.mcfpm.repository.maven.MavenRepositoryCredentials
import moe.afox.mcfpm.source.github.GitHubImportCandidate
import moe.afox.mcfpm.source.github.GitHubImportRequest
import moe.afox.mcfpm.source.github.GitHubRepository
import moe.afox.mcfpm.source.github.GitHubSourceClient
import moe.afox.mcfpm.source.github.GitHubSourceMode
import moe.afox.mcfpm.source.github.GitHubImportLockCodec
import moe.afox.mcfpm.source.github.GitHubImportRecipeCodec
import moe.afox.mcfpm.cli.runMcfpm
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public object GitHubImportPrivateNexusE2E {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 3) { "Expected VERSION REPOSITORY_URL OUTPUT_DIRECTORY" }
        val version = SemVer.parse(args[0])
        require(!version.toString().endsWith("-SNAPSHOT")) { "Nexus Components API E2E requires an immutable release version" }
        val repositoryUri = URI.create(args[1])
        val outputDirectory = Path.of(args[2])
        Files.createDirectories(outputDirectory)
        val credentials = MavenRepositoryCredentials(
            requiredEnvironment("MCFPM_E2E_NEXUS_USERNAME"),
            requiredEnvironment("MCFPM_E2E_NEXUS_PASSWORD"),
        )
        val target = NexusTarget.derive("private-snapshots", repositoryUri)
        val source = GitHubSourceClient()
        val token = System.getenv("GITHUB_TOKEN")

        val buildersWand = source.import(
            GitHubImportRequest(
                repository = GitHubRepository("Elemend", "Builders-Wand"),
                reference = "v2.1.2",
                asset = "builders_wand_2.1.2.zip",
                token = token,
            ),
        ).also { candidate ->
            require(candidate.releaseId == 349229420L) { "Builders Wand release identity changed" }
            require(candidate.assetId == 467370214L) { "Builders Wand asset identity changed" }
            require(candidate.rawSha256 == "bc6aae315aff8e1b0fabaff7009e438279545b66bdf0e0d9d004c88faff3c8d6") {
                "Builders Wand release asset digest changed"
            }
        }
        val noCreeper = source.import(
            GitHubImportRequest(
                repository = GitHubRepository("hallettj", "no_creeper_griefing"),
                reference = "79106469e359491f195628360ab50adf03c96965",
                mode = GitHubSourceMode.ARCHIVE,
                subdirectory = "datapack",
                token = token,
            ),
        ).also { candidate ->
            require(candidate.commit == "79106469e359491f195628360ab50adf03c96965") {
                "No Creeper Griefing commit resolution changed"
            }
        }
        val candidates = listOf(
            PackageId.parse("moe.afox.mcfpm.e2e:builders-wand-import") to buildersWand,
            PackageId.parse("moe.afox.mcfpm.e2e:no-creeper-import") to noCreeper,
        )
        val publisher = NexusComponentPublisher(target, credentials)
        val states = candidates.associate { (id, candidate) ->
            val manifest = manifest(id, version, candidate)
            val result = publisher.publish(NexusPublication(manifest, candidate.payload, candidate.repository.projectUri))
            id to result.state
        }

        val repository = MavenPackageRepository("private-snapshots", repositoryUri, credentials = credentials)
        val registry = RepositoryRegistry(listOf(repository), defaultRepositoryId = repository.id)
        val graph = requireSuccess(
            PubGrubResolver(registry).resolve(
                ResolveRequest(
                    roots = candidates.map { (id, _) -> RootRequirement(id, VersionRequirement.exact(version)) },
                    consumerProfile = ConsumerProfile.MINECRAFT_DATAPACK,
                ),
            ),
        )
        require(graph.packages.map { it.packageId }.toSet() == candidates.map { it.first }.toSet()) {
            "Maven-only consumer did not resolve both GitHub imports"
        }
        val cache = ContentAddressedCache(outputDirectory.resolve("cache"))
        val fetcher = ArtifactGraphFetcher(registry, cache)
        val online = requireSuccess(fetcher.fetch(graph))
        requireSuccess(ArtifactVerifier(InMemoryTrustStore()).verify(online))
        val offline = requireSuccess(fetcher.fetch(graph, offline = true))
        require(offline.artifacts.size == candidates.size) { "Offline verification missed an imported payload" }
        require(offline.artifacts.values.all(Files::isRegularFile)) { "Offline cache contains a missing payload" }
        val cliState = verifyCliNoOp(version, repositoryUri, outputDirectory)

        val report = JsonObject(
            linkedMapOf(
                "schema" to JsonPrimitive(1),
                "repository" to JsonPrimitive(repositoryUri.toString()),
                "version" to JsonPrimitive(version.toString()),
                "offlineVerified" to JsonPrimitive(true),
                "cliState" to JsonPrimitive(cliState),
                "packages" to JsonArray(
                    candidates.map { (id, candidate) ->
                        JsonObject(
                            linkedMapOf(
                                "id" to JsonPrimitive(id.value),
                                "state" to JsonPrimitive(states.getValue(id).name.lowercase()),
                                "source" to JsonPrimitive(candidate.repository.slug),
                                "commit" to JsonPrimitive(candidate.commit),
                                "rawSha256" to JsonPrimitive(candidate.rawSha256),
                                "payloadSha256" to JsonPrimitive(candidate.normalizedSha256),
                            ),
                        )
                    },
                ),
            ),
        )
        Files.write(
            outputDirectory.resolve("github-import-e2e-report.json"),
            CanonicalJson.encode(JsonElement.serializer(), report),
        )
        println("GitHub import E2E published/resolved ${candidates.size} packages and verified offline reuse.")
    }

    private fun verifyCliNoOp(version: SemVer, repositoryUri: URI, outputDirectory: Path): String {
        val workspace = outputDirectory.resolve("cli-workspace")
        Files.createDirectories(workspace)
        val repositoryId = "private-releases"
        val projectManifest = PackageManifest(
            packageId = PackageId.parse("moe.afox.mcfpm.e2e:cli-import-runner"),
            version = SemVer.parse("1.0.0"),
            license = "Apache-2.0",
            tool = ToolConfiguration(
                defaultRepository = repositoryId,
                repositories = mapOf(repositoryId to repositoryUri.toString()),
                bindings = mapOf("moe.afox.mcfpm.e2e" to repositoryId),
                options = mapOf(
                    "repository.$repositoryId.username-env" to "MCFPM_E2E_NEXUS_USERNAME",
                    "repository.$repositoryId.password-env" to "MCFPM_E2E_NEXUS_PASSWORD",
                ),
            ),
        )
        Files.write(workspace.resolve("mcfpm.toml"), PackageManifestCodec.encode(projectManifest))
        val stdout = StringWriter()
        val stderr = StringWriter()
        val exitCode = runMcfpm(
            arrayOf(
                "--json",
                "--yes",
                "import",
                "github",
                "Elemend/Builders-Wand",
                "--tag",
                "v2.1.2",
                "--asset",
                "builders_wand_2.1.2.zip",
                "--package",
                "moe.afox.mcfpm.e2e:builders-wand-import",
                "--version",
                version.toString(),
                "--repository",
                repositoryId,
                "--write-recipe",
                "mcfpm-import.toml",
            ),
            workingDirectory = workspace,
            out = PrintWriter(stdout, true),
            err = PrintWriter(stderr, true),
        )
        require(exitCode == 0) {
            "CLI import E2E failed with exit $exitCode: ${stderr.toString().trim()} ${stdout.toString().trim()}"
        }
        require(stderr.toString().isEmpty()) { "JSON CLI import emitted stderr output" }
        val data = CanonicalJson.format.parseToJsonElement(stdout.toString().trim()).jsonObject
            .getValue("data").jsonObject
        val state = data.getValue("state").jsonPrimitive.content
        require(state == "already_present") { "CLI import did not converge to an idempotent no-op: $state" }
        GitHubImportLockCodec.decode(Files.readAllBytes(workspace.resolve("mcfpm-import.lock")))
        GitHubImportRecipeCodec.decode(Files.readAllBytes(workspace.resolve("mcfpm-import.toml")))
        CanonicalJson.format.parseToJsonElement(
            Files.readString(workspace.resolve("build/mcfpm/import-report.json")),
        ).jsonObject
        return state
    }

    private fun manifest(id: PackageId, version: SemVer, candidate: GitHubImportCandidate): PackageManifest {
        val immutableVersion = candidate.assetId?.let { "release:${candidate.releaseId}/asset:$it" } ?: candidate.commit
        return PackageManifest(
            packageId = id,
            version = version,
            license = candidate.license,
            artifacts = listOf(
                ArtifactDescriptor(
                    type = candidate.payloadType,
                    classifier = candidate.classifier,
                    sha256 = candidate.normalizedSha256,
                    size = candidate.normalizedSize,
                    source = ArtifactSource(
                        kind = if (candidate.mode == GitHubSourceMode.RELEASE_ASSET) {
                            "github-release-asset"
                        } else {
                            "github-archive"
                        },
                        uri = candidate.sourceUri.toString(),
                        immutableVersion = immutableVersion,
                        redistributionLicense = candidate.license,
                        upstreamId = candidate.assetId?.toString() ?: candidate.repository.slug,
                        revision = candidate.commit,
                        path = candidate.selectedPath,
                        sha256 = candidate.rawSha256,
                        size = candidate.rawSize,
                    ),
                ),
            ),
        )
    }

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotEmpty) ?: error("Required environment variable $name is missing")

    private fun <T> requireSuccess(result: McfpmResult<T>): T = when (result) {
        is McfpmResult.Success -> result.value
        is McfpmResult.Failure -> error(result.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" })
    }
}
