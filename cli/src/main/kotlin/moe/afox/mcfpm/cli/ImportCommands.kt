package moe.afox.mcfpm.cli

import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.ArtifactSource
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.Dependency
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.SpdxLicense
import moe.afox.mcfpm.model.VersionRequirement
import moe.afox.mcfpm.core.FrozenImportCandidate
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.ImportCandidateCodec
import moe.afox.mcfpm.core.ImportCandidateDocument
import moe.afox.mcfpm.core.ImportCandidatePayload
import moe.afox.mcfpm.core.ImportCandidateSource
import moe.afox.mcfpm.publish.nexus.NexusComponentPublisher
import moe.afox.mcfpm.publish.nexus.NexusPublication
import moe.afox.mcfpm.publish.nexus.NexusPublishResult
import moe.afox.mcfpm.publish.nexus.NexusPublishState
import moe.afox.mcfpm.publish.nexus.NexusTarget
import moe.afox.mcfpm.repository.maven.MavenRepositoryCredentials
import moe.afox.mcfpm.source.github.GitHubImportCandidate
import moe.afox.mcfpm.source.github.GitHubImportLock
import moe.afox.mcfpm.source.github.GitHubImportLockCodec
import moe.afox.mcfpm.source.github.GitHubImportRecipe
import moe.afox.mcfpm.source.github.GitHubImportRecipeCodec
import moe.afox.mcfpm.source.github.GitHubImportRequest
import moe.afox.mcfpm.source.github.GitHubIntegrityException
import moe.afox.mcfpm.source.github.GitHubNetworkException
import moe.afox.mcfpm.source.github.GitHubRepository
import moe.afox.mcfpm.source.github.GitHubSourceClient
import moe.afox.mcfpm.source.github.GitHubSourceMode
import moe.afox.mcfpm.source.github.LockedGitHubSource
import moe.afox.mcfpm.source.github.LockedPackageCoordinate
import moe.afox.mcfpm.source.github.RecipePackage
import moe.afox.mcfpm.source.github.RecipeRepository
import moe.afox.mcfpm.source.github.RecipeSource
import moe.afox.mcfpm.source.url.UrlImportRequest
import moe.afox.mcfpm.source.url.UrlSourceClient
import moe.afox.mcfpm.source.url.UrlSourceException
import moe.afox.mcfpm.source.url.UrlSourceIntegrityException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import picocli.CommandLine

@CommandLine.Command(
    name = "import",
    description = ["Import a statically audited upstream pack into a configured Maven repository."],
    subcommands = [GitHubImportCommand::class, UrlImportCommand::class, ImportPublishCommand::class],
)
internal class ImportCommand : CliCallable() {
    override fun execute(): Int {
        spec.commandLine().usage(root.out())
        return CliExitCode.SUCCESS.value
    }
}

@CommandLine.Command(
    name = "github",
    description = ["Audit a GitHub release ZIP or commit archive and publish one Minecraft pack through Nexus."],
    mixinStandardHelpOptions = true,
)
internal class GitHubImportCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "OWNER/REPOSITORY")
    private var repositorySlug: String? = null

    @CommandLine.Option(names = ["--recipe"], paramLabel = "TOML")
    private var recipePath: Path? = null

    @CommandLine.Option(names = ["--tag", "--ref"], required = true, paramLabel = "TAG_OR_COMMIT")
    private lateinit var reference: String

    @CommandLine.Option(names = ["--source"], paramLabel = "release-asset|archive")
    private var sourceMode: String? = null

    @CommandLine.Option(names = ["--asset"], paramLabel = "EXACT_ZIP_NAME")
    private var asset: String? = null

    @CommandLine.Option(names = ["--subdir"], paramLabel = "PATH")
    private var subdirectory: String? = null

    @CommandLine.Option(names = ["--nested-zip"], paramLabel = "PATH")
    private var nestedZip: String? = null

    @CommandLine.Option(names = ["--package"], paramLabel = "GROUP:NAME")
    private var packageId: String? = null

    @CommandLine.Option(names = ["--version"], paramLabel = "SEMVER")
    private var version: String? = null

    @CommandLine.Option(names = ["--license"], paramLabel = "SPDX")
    private var license: String? = null

    @CommandLine.Option(names = ["--expected-sha256"], paramLabel = "SHA256")
    private var expectedSha256: String? = null

    @CommandLine.Option(names = ["--type"], paramLabel = "PAYLOAD_TYPE")
    private var payloadType: String? = null

    @CommandLine.Option(names = ["--classifier"])
    private var classifier: String? = null

    @CommandLine.Option(names = ["--minecraft"])
    private var minecraft: String? = null

    @CommandLine.Option(names = ["--repository"], paramLabel = "ID")
    private var repositoryId: String? = null

    @CommandLine.Option(names = ["--dependency"], paramLabel = "GROUP:NAME@REQUIREMENT")
    private var dependencies: MutableList<String> = mutableListOf()

    @CommandLine.Option(names = ["--github-token-env"], paramLabel = "ENV")
    private var githubTokenEnvironment: String? = null

    @CommandLine.Option(names = ["--write-recipe"], paramLabel = "TOML")
    private var writeRecipe: Path? = null

    @CommandLine.Option(names = ["--lock-output"], defaultValue = "mcfpm-import.lock", paramLabel = "JSON")
    private lateinit var lockOutput: Path

    @CommandLine.Option(names = ["--report"], defaultValue = "build/mcfpm/import-report.json", paramLabel = "JSON")
    private lateinit var reportOutput: Path

    @CommandLine.Option(names = ["--audit-only"], description = ["Freeze a deterministic candidate without uploading it."])
    private var auditOnly: Boolean = false

    @CommandLine.Option(names = ["--candidate-output"], paramLabel = "PATH")
    private var candidateOutput: Path? = null

    override fun execute(): Int {
        require(!root.offline) { "GitHub import cannot run with --offline" }
        require(!auditOnly || candidateOutput != null) { "--candidate-output is required with --audit-only" }
        val recipe = recipePath?.let { path ->
            GitHubImportRecipeCodec.decode(Files.readAllBytes(resolve(path)))
        }
        val githubRepository = repositorySlug?.let(GitHubRepository::parse)
            ?: recipe?.source?.repository
            ?: throw IllegalArgumentException("OWNER/REPOSITORY or --recipe is required")
        val mode = sourceMode?.let(::parseMode) ?: recipe?.source?.mode ?: GitHubSourceMode.RELEASE_ASSET
        val tokenEnvironment = githubTokenEnvironment ?: recipe?.source?.githubTokenEnvironment ?: "GITHUB_TOKEN"
        require(ENVIRONMENT.matches(tokenEnvironment)) { "Invalid GitHub token environment variable: $tokenEnvironment" }
        require(mode == GitHubSourceMode.RELEASE_ASSET || asset == null) { "--asset is valid only with --source release-asset" }
        val request = GitHubImportRequest(
            repository = githubRepository,
            reference = reference,
            mode = mode,
            asset = if (mode == GitHubSourceMode.RELEASE_ASSET) asset ?: recipe?.source?.asset else null,
            subdirectory = subdirectory ?: recipe?.source?.subdirectory,
            nestedZip = nestedZip ?: recipe?.source?.nestedZip,
            explicitLicense = license ?: recipe?.packageConfiguration?.license,
            expectedSha256 = expectedSha256,
            token = System.getenv(tokenEnvironment),
        )
        val candidate = try {
            GitHubSourceClient().import(request)
        } catch (exception: GitHubIntegrityException) {
            return failure(DiagnosticCode.INTEGRITY_FAILURE, exception.message.orEmpty())
        } catch (exception: GitHubNetworkException) {
            return failure(DiagnosticCode.NETWORK_FAILURE, exception.message.orEmpty())
        }

        val selectedType = payloadType?.let(PayloadType::parse)
            ?: recipe?.packageConfiguration?.type
            ?: candidate.payloadType
        require(selectedType == candidate.payloadType) {
            "Inspected payload type is ${candidate.payloadType}; requested type was $selectedType"
        }
        val selectedClassifier = classifier ?: recipe?.packageConfiguration?.classifier ?: candidate.classifier
        val selectedPackage = packageId?.let(PackageId::parse)
            ?: recipe?.packageConfiguration?.packageId
            ?: githubRepository.defaultPackageId()
        val selectedVersion = version?.let(SemVer::parse) ?: inferVersion(reference)
        require(!selectedVersion.toString().endsWith("-SNAPSHOT", ignoreCase = true)) {
            "GitHub import version must be an immutable release SemVer"
        }
        val selectedRepositoryId = repositoryId ?: recipe?.repository?.id
        SpdxLicense.requireIdentifier(candidate.license)
        val dependencyMap = recipe?.dependencies.orEmpty().toMutableMap()
        dependencies.forEach { declaration ->
            val (id, requirement) = parseDependency(declaration)
            dependencyMap[id] = requirement
        }
        val artifact = artifact(candidate, selectedType, selectedClassifier)
        val manifest = PackageManifest(
            packageId = selectedPackage,
            version = selectedVersion,
            license = candidate.license,
            minecraft = minecraft ?: recipe?.packageConfiguration?.minecraft,
            dependencies = dependencyMap.toSortedMap().map { (id, requirement) -> Dependency(id, requirement) },
            artifacts = listOf(artifact),
        )

        val frozen = frozenCandidate(candidate, manifest)
        if (auditOnly) {
            val path = resolve(requireNotNull(candidateOutput))
            atomicWrite(path, ImportCandidateCodec.encode(frozen))
            return CliOutput.success(
                root,
                "import github",
                JsonObject(
                    linkedMapOf(
                        "state" to JsonPrimitive("audited"),
                        "package" to JsonPrimitive(manifest.packageId.value),
                        "version" to JsonPrimitive(manifest.version.toString()),
                        "candidate" to JsonPrimitive(path.toString()),
                        "source" to JsonPrimitive(candidate.sourceUri.toString()),
                        "requestUrl" to JsonPrimitive(candidate.requestUri.toString()),
                        "finalUrl" to JsonPrimitive(candidate.finalUri.toString()),
                        "rawSha256" to JsonPrimitive(candidate.rawSha256),
                        "rawSize" to JsonPrimitive(candidate.rawSize),
                        "normalizedSha256" to JsonPrimitive(candidate.normalizedSha256),
                        "normalizedSize" to JsonPrimitive(candidate.normalizedSize),
                        "payloadType" to JsonPrimitive(manifest.artifacts.single().type.value),
                        "classifier" to JsonPrimitive(manifest.artifacts.single().classifier),
                        "selectionPath" to JsonPrimitive(candidate.selectedPath),
                    ),
                ),
                "Audited ${manifest.packageId}@${manifest.version}; froze candidate at $path",
            )
        }
        val selectedRepository = selectedRepositoryId
            ?: throw IllegalArgumentException("--repository or recipe [repository].id is required")

        val configured = McfpmServices(root).configuredRepository(selectedRepository)
        val credentials = configured.credentials
            ?: throw IllegalArgumentException("Repository $selectedRepository has no configured credentials")
        val optionPrefix = "repository.$selectedRepository."
        val target = NexusTarget.derive(
            selectedRepository,
            configured.uri,
            configured.options["${optionPrefix}nexus-api"]?.let(URI::create),
            configured.options["${optionPrefix}nexus-name"],
        )
        val publication = NexusPublication(manifest, candidate.payload, githubRepository.projectUri)
        val publisher = NexusComponentPublisher(target, credentials)
        val preview = try {
            publisher.publish(publication, dryRun = true)
        } catch (exception: Exception) {
            return failure(DiagnosticCode.PUBLISH_VALIDATION_FAILED, exception.message.orEmpty())
        }
        if (!root.dryRun && preview.state != NexusPublishState.ALREADY_PRESENT && !confirm(manifest, target)) {
            return CliOutput.argumentFailure(root, "GitHub import cancelled")
        }
        val published = if (root.dryRun) {
            preview
        } else {
            try {
                publisher.publish(publication)
            } catch (exception: Exception) {
                return failure(DiagnosticCode.PUBLISH_VALIDATION_FAILED, exception.message.orEmpty())
            }
        }
        val lock = lock(candidate, manifest, selectedRepository)
        val recipeOutput = effectiveRecipe(request, candidate, manifest, selectedRepository, dependencyMap, tokenEnvironment)
        val report = report(candidate, manifest, target, published)
        val lockPath = resolve(lockOutput)
        val reportPath = resolve(reportOutput)
        if (!root.dryRun) {
            atomicWrite(lockPath, GitHubImportLockCodec.encode(lock))
            atomicWrite(reportPath, CanonicalJson.encode(JsonElement.serializer(), JsonObject(report)))
            writeRecipe?.let { atomicWrite(resolve(it), GitHubImportRecipeCodec.encode(recipeOutput)) }
        }
        return CliOutput.success(
            root,
            "import github",
            JsonObject(
                report + mapOf(
                    "lock" to JsonPrimitive(lockPath.toString()),
                    "report" to JsonPrimitive(reportPath.toString()),
                    "written" to JsonPrimitive(!root.dryRun),
                ),
            ),
            if (root.dryRun) {
                "Audited ${manifest.packageId}@${manifest.version}; dry run did not upload or write files"
            } else {
                "${published.state.name.lowercase()}: ${manifest.packageId}@${manifest.version} in ${target.repositoryName}"
            },
        )
    }

    private fun frozenCandidate(candidate: GitHubImportCandidate, manifest: PackageManifest): FrozenImportCandidate =
        FrozenImportCandidate(
            document = ImportCandidateDocument(
                source = ImportCandidateSource(
                    kind = if (candidate.mode == GitHubSourceMode.RELEASE_ASSET) "github-release-asset" else "github-archive",
                    requestUrl = candidate.requestUri.toString(),
                    finalUrl = candidate.finalUri.toString(),
                    rawSha256 = candidate.rawSha256,
                    rawSize = candidate.rawSize,
                    immutableVersion = candidate.assetId?.let { "release:${candidate.releaseId}/asset:$it" }
                        ?: candidate.commit,
                    selectionPath = candidate.selectedPath,
                    selectedRoot = candidate.selectedRoot.takeIf(String::isNotEmpty),
                    nestedZip = candidate.selectedNestedZip,
                    upstreamId = candidate.assetId?.toString() ?: candidate.repository.slug,
                    revision = candidate.commit,
                    releaseId = candidate.releaseId,
                    assetId = candidate.assetId,
                    assetName = candidate.assetName,
                ),
                packageId = manifest.packageId,
                version = manifest.version,
                license = manifest.license,
                minecraft = manifest.minecraft,
                dependencies = manifest.dependencies,
                payload = ImportCandidatePayload(
                    type = manifest.artifacts.single().type,
                    classifier = manifest.artifacts.single().classifier,
                    normalizedSha256 = candidate.normalizedSha256,
                    normalizedSize = candidate.normalizedSize,
                ),
            ),
            payload = candidate.payload,
        )

    private fun confirm(manifest: PackageManifest, target: NexusTarget): Boolean {
        if (root.yes) return true
        if (root.json || System.getenv("CI") != null || System.console() == null) {
            throw IllegalArgumentException("Publishing to Nexus requires --yes in JSON, CI, or non-interactive mode")
        }
        root.err().print("Publish ${manifest.packageId}@${manifest.version} to ${target.repositoryName}? [y/N] ")
        root.err().flush()
        return System.console().readLine()?.trim()?.lowercase() in setOf("y", "yes")
    }

    private fun artifact(candidate: GitHubImportCandidate, type: PayloadType, selectedClassifier: String): ArtifactDescriptor {
        val immutableVersion = candidate.assetId?.let { "release:${candidate.releaseId}/asset:$it" } ?: candidate.commit
        return ArtifactDescriptor(
            type = type,
            classifier = selectedClassifier,
            sha256 = candidate.normalizedSha256,
            size = candidate.normalizedSize,
            source = ArtifactSource(
                kind = if (candidate.mode == GitHubSourceMode.RELEASE_ASSET) "github-release-asset" else "github-archive",
                uri = candidate.sourceUri.toString(),
                immutableVersion = immutableVersion,
                redistributionLicense = candidate.license,
                upstreamId = candidate.assetId?.toString() ?: candidate.repository.slug,
                revision = candidate.commit,
                path = candidate.selectedPath,
                sha256 = candidate.rawSha256,
                size = candidate.rawSize,
            ),
        )
    }

    private fun lock(candidate: GitHubImportCandidate, manifest: PackageManifest, selectedRepository: String): GitHubImportLock =
        GitHubImportLock(
            source = LockedGitHubSource(
                repository = candidate.repository.slug,
                mode = candidate.mode,
                reference = candidate.reference,
                releaseId = candidate.releaseId,
                assetId = candidate.assetId,
                assetName = candidate.assetName,
                commit = candidate.commit,
                sourceUri = candidate.sourceUri.toString(),
                rawSha256 = candidate.rawSha256,
                rawSize = candidate.rawSize,
                selectedPath = candidate.selectedPath,
            ),
            packageCoordinate = LockedPackageCoordinate(
                packageId = manifest.packageId,
                version = manifest.version,
                repositoryId = selectedRepository,
                type = manifest.artifacts.single().type,
                classifier = manifest.artifacts.single().classifier,
                sha256 = manifest.artifacts.single().sha256,
                size = manifest.artifacts.single().size,
            ),
        )

    private fun effectiveRecipe(
        request: GitHubImportRequest,
        candidate: GitHubImportCandidate,
        manifest: PackageManifest,
        selectedRepository: String,
        dependencyMap: Map<PackageId, VersionRequirement>,
        tokenEnvironment: String,
    ): GitHubImportRecipe = GitHubImportRecipe(
        source = RecipeSource(
            repository = request.repository,
            mode = request.mode,
            asset = candidate.assetName,
            subdirectory = candidate.selectedRoot.takeIf(String::isNotEmpty),
            nestedZip = candidate.selectedNestedZip,
            githubTokenEnvironment = tokenEnvironment,
        ),
        packageConfiguration = RecipePackage(
            packageId = manifest.packageId,
            license = manifest.license,
            type = manifest.artifacts.single().type,
            classifier = manifest.artifacts.single().classifier,
            minecraft = manifest.minecraft,
        ),
        repository = RecipeRepository(selectedRepository),
        dependencies = dependencyMap,
    )

    private fun report(
        candidate: GitHubImportCandidate,
        manifest: PackageManifest,
        target: NexusTarget,
        published: NexusPublishResult,
    ): Map<String, JsonElement> = linkedMapOf(
        "state" to JsonPrimitive(published.state.name.lowercase()),
        "package" to JsonPrimitive(manifest.packageId.value),
        "version" to JsonPrimitive(manifest.version.toString()),
        "repository" to JsonPrimitive(target.repositoryId),
        "source" to JsonObject(
            linkedMapOf(
                "repository" to JsonPrimitive(candidate.repository.slug),
                "mode" to JsonPrimitive(if (candidate.mode == GitHubSourceMode.RELEASE_ASSET) "release-asset" else "archive"),
                "reference" to JsonPrimitive(candidate.reference),
                "commit" to JsonPrimitive(candidate.commit),
                "asset" to (candidate.assetName?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?)),
                "rawSha256" to JsonPrimitive(candidate.rawSha256),
                "rawSize" to JsonPrimitive(candidate.rawSize),
                "selectedPath" to JsonPrimitive(candidate.selectedPath),
            ),
        ),
        "artifact" to JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive(manifest.artifacts.single().type.value),
                "classifier" to JsonPrimitive(manifest.artifacts.single().classifier),
                "sha256" to JsonPrimitive(manifest.artifacts.single().sha256),
                "size" to JsonPrimitive(manifest.artifacts.single().size),
            ),
        ),
        "descriptorSha256" to JsonPrimitive(published.descriptorSha256),
        "dependencies" to JsonArray(manifest.dependencies.map { JsonPrimitive("${it.packageId}@${it.requirement.expression}") }),
    )

    private fun parseMode(value: String): GitHubSourceMode = when (value.lowercase()) {
        "release", "release-asset" -> GitHubSourceMode.RELEASE_ASSET
        "archive" -> GitHubSourceMode.ARCHIVE
        else -> throw IllegalArgumentException("--source must be release-asset or archive")
    }

    private fun inferVersion(value: String): SemVer = SemVer.parseOrNull(value.removePrefix("v"))
        ?: throw IllegalArgumentException("Cannot infer SemVer from $value; pass --version")

    private fun parseDependency(value: String): Pair<PackageId, VersionRequirement> {
        val separator = value.lastIndexOf('@')
        require(separator > 0 && separator < value.lastIndex) { "Dependency must use GROUP:NAME@REQUIREMENT" }
        return PackageId.parse(value.substring(0, separator)) to VersionRequirement.parse(value.substring(separator + 1))
    }

    private fun resolve(path: Path): Path =
        (if (path.isAbsolute) path else root.workingDirectory.resolve(path)).toAbsolutePath().normalize()

    private fun failure(code: DiagnosticCode, message: String): Int = CliOutput.failure(
        root,
        listOf(Diagnostic(code, DiagnosticSeverity.ERROR, message)),
    )

    private companion object {
        val ENVIRONMENT: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

@CommandLine.Command(
    name = "url",
    description = ["Audit a public HTTPS ZIP and freeze it as an import candidate."],
)
internal class UrlImportCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", paramLabel = "HTTPS_URL")
    private lateinit var sourceUrl: URI

    @CommandLine.Option(names = ["--package"], required = true, paramLabel = "GROUP:NAME")
    private lateinit var packageId: String

    @CommandLine.Option(names = ["--version"], required = true, paramLabel = "SEMVER")
    private lateinit var version: String

    @CommandLine.Option(names = ["--license"], required = true, paramLabel = "SPDX")
    private lateinit var license: String

    @CommandLine.Option(names = ["--type"], paramLabel = "PAYLOAD_TYPE")
    private var payloadType: String? = null

    @CommandLine.Option(names = ["--classifier"])
    private var classifier: String? = null

    @CommandLine.Option(names = ["--minecraft"], paramLabel = "RANGE")
    private var minecraft: String? = null

    @CommandLine.Option(names = ["--dependency"], paramLabel = "GROUP:NAME@REQUIREMENT")
    private var dependencies: MutableList<String> = mutableListOf()

    @CommandLine.Option(names = ["--subdir"], paramLabel = "PATH")
    private var subdirectory: String? = null

    @CommandLine.Option(names = ["--nested-zip"], paramLabel = "PATH")
    private var nestedZip: String? = null

    @CommandLine.Option(names = ["--expected-sha256"], paramLabel = "SHA256")
    private var expectedSha256: String? = null

    @CommandLine.Option(names = ["--audit-only"], required = true, description = ["Required: URL imports are audit-only until published from a candidate."])
    private var auditOnly: Boolean = false

    @CommandLine.Option(names = ["--candidate-output"], defaultValue = "candidate.mcfpm-import", paramLabel = "PATH")
    private lateinit var candidateOutput: Path

    override fun execute(): Int {
        require(auditOnly) { "URL imports must use --audit-only" }
        require(!root.offline) { "HTTPS URL import cannot run with --offline" }
        val id = PackageId.parse(packageId)
        val parsedVersion = SemVer.parse(version)
        require(!parsedVersion.toString().endsWith("-SNAPSHOT", ignoreCase = true)) {
            "URL import version must be an immutable release SemVer"
        }
        SpdxLicense.requireIdentifier(license)
        val downloaded = try {
            UrlSourceClient().import(
                UrlImportRequest(
                    uri = sourceUrl,
                    expectedSha256 = expectedSha256,
                    subdirectory = subdirectory,
                    nestedZip = nestedZip,
                ),
            )
        } catch (exception: UrlSourceIntegrityException) {
            return failure(DiagnosticCode.INTEGRITY_FAILURE, exception.message.orEmpty())
        } catch (exception: UrlSourceException) {
            return failure(DiagnosticCode.NETWORK_FAILURE, exception.message.orEmpty())
        }
        val type = payloadType?.let(PayloadType::parse) ?: downloaded.payloadType
        require(type == downloaded.payloadType) {
            "Inspected payload type is ${downloaded.payloadType}; requested type was $type"
        }
        val selectedClassifier = classifier ?: downloaded.classifier
        val dependencyValues = dependencies.map(::parseImportDependency)
        val document = ImportCandidateDocument(
            source = ImportCandidateSource(
                kind = "url",
                requestUrl = downloaded.requestUri.toString(),
                finalUrl = downloaded.finalUri.toString(),
                rawSha256 = downloaded.rawSha256,
                rawSize = downloaded.rawSize,
                immutableVersion = "sha256:${downloaded.rawSha256}",
                selectionPath = downloaded.selectedPath,
                selectedRoot = downloaded.selectedRoot.takeIf(String::isNotEmpty),
                nestedZip = downloaded.selectedNestedZip,
            ),
            packageId = id,
            version = parsedVersion,
            license = license,
            minecraft = minecraft,
            dependencies = dependencyValues.sortedBy(Dependency::packageId),
            payload = ImportCandidatePayload(
                type = type,
                classifier = selectedClassifier,
                normalizedSha256 = downloaded.normalizedSha256,
                normalizedSize = downloaded.normalizedSize,
            ),
        )
        val frozen = FrozenImportCandidate(document, downloaded.payload)
        val output = resolve(candidateOutput)
        atomicWrite(output, ImportCandidateCodec.encode(frozen))
        return CliOutput.success(
            root,
            "import url",
            JsonObject(
                linkedMapOf(
                    "state" to JsonPrimitive("audited"),
                    "package" to JsonPrimitive(id.value),
                    "version" to JsonPrimitive(parsedVersion.toString()),
                    "candidate" to JsonPrimitive(output.toString()),
                    "requestUrl" to JsonPrimitive(downloaded.requestUri.toString()),
                    "finalUrl" to JsonPrimitive(downloaded.finalUri.toString()),
                    "rawSha256" to JsonPrimitive(downloaded.rawSha256),
                    "rawSize" to JsonPrimitive(downloaded.rawSize),
                    "normalizedSha256" to JsonPrimitive(downloaded.normalizedSha256),
                    "normalizedSize" to JsonPrimitive(downloaded.normalizedSize),
                    "payloadType" to JsonPrimitive(type.value),
                    "classifier" to JsonPrimitive(selectedClassifier),
                    "selectionPath" to JsonPrimitive(downloaded.selectedPath),
                ),
            ),
            "Audited $id@$parsedVersion; froze candidate at $output",
        )
    }

    private fun resolve(path: Path): Path =
        (if (path.isAbsolute) path else root.workingDirectory.resolve(path)).toAbsolutePath().normalize()

    private fun failure(code: DiagnosticCode, message: String): Int = CliOutput.failure(
        root,
        listOf(Diagnostic(code, DiagnosticSeverity.ERROR, message)),
    )
}

@CommandLine.Command(
    name = "publish",
    description = ["Publish a previously audited .mcfpm-import candidate without re-downloading its source."],
    mixinStandardHelpOptions = true,
)
internal class ImportPublishCommand : CliCallable() {
    @CommandLine.Option(names = ["--candidate"], required = true, paramLabel = "PATH")
    private lateinit var candidatePath: Path

    @CommandLine.Option(names = ["--repository-name"], required = true, paramLabel = "NAME")
    private lateinit var repositoryName: String

    @CommandLine.Option(names = ["--username-env"], required = true, paramLabel = "ENV")
    private lateinit var usernameEnvironment: String

    @CommandLine.Option(names = ["--password-env"], required = true, paramLabel = "ENV")
    private lateinit var passwordEnvironment: String

    override fun execute(): Int {
        require(root.yes) { "Publishing an import candidate requires --yes" }
        val repositoryUrl = root.repositories.singleOrNull()?.let(URI::create)
            ?: throw IllegalArgumentException("--repository-url HTTPS_URL is required exactly once")
        require(
            repositoryUrl.scheme.equals("https", ignoreCase = true) &&
                !repositoryUrl.host.isNullOrBlank() && repositoryUrl.userInfo == null,
        ) {
            "--repository-url must use HTTPS"
        }
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(repositoryName)) {
            "--repository-name must be a single Maven repository name"
        }
        require(ENVIRONMENT.matches(usernameEnvironment) && ENVIRONMENT.matches(passwordEnvironment)) {
            "Credential environment variable names are invalid"
        }
        val username = System.getenv(usernameEnvironment)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Credential environment variable $usernameEnvironment is missing")
        val password = System.getenv(passwordEnvironment)?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Credential environment variable $passwordEnvironment is missing")
        val candidate = ImportCandidateCodec.decode(Files.readAllBytes(resolve(candidatePath)))
        val manifest = candidate.manifest()
        val target = NexusTarget.derive(
            repositoryId = repositoryName,
            mavenRepositoryUri = repositoryUrl,
            repositoryNameOverride = repositoryName,
        )
        val publication = NexusPublication(manifest, candidate.payload, URI.create(candidate.document.source.finalUrl))
        val result = try {
            NexusComponentPublisher(target, MavenRepositoryCredentials(username, password)).publish(
                publication,
                dryRun = root.dryRun,
            )
        } catch (exception: Exception) {
            return CliOutput.failure(
                root,
                listOf(Diagnostic(DiagnosticCode.PUBLISH_VALIDATION_FAILED, DiagnosticSeverity.ERROR, exception.message.orEmpty())),
            )
        }
        return CliOutput.success(
            root,
            "import publish",
            JsonObject(
                linkedMapOf(
                    "state" to JsonPrimitive(result.state.name.lowercase()),
                    "package" to JsonPrimitive(manifest.packageId.value),
                    "version" to JsonPrimitive(manifest.version.toString()),
                    "repository" to JsonPrimitive(repositoryName),
                    "descriptorSha256" to JsonPrimitive(result.descriptorSha256),
                    "candidateSha256" to JsonPrimitive(Hashing.sha256(Files.readAllBytes(resolve(candidatePath)))),
                    "payloadSha256" to JsonPrimitive(manifest.artifacts.single().sha256),
                    "payloadSize" to JsonPrimitive(manifest.artifacts.single().size),
                ),
            ),
            "${result.state.name.lowercase()}: ${manifest.packageId}@${manifest.version} in $repositoryName",
        )
    }

    private fun resolve(path: Path): Path =
        (if (path.isAbsolute) path else root.workingDirectory.resolve(path)).toAbsolutePath().normalize()

    private companion object {
        val ENVIRONMENT: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

private fun parseImportDependency(value: String): Dependency {
    val separator = value.lastIndexOf('@')
    require(separator > 0 && separator < value.lastIndex) { "Dependency must use GROUP:NAME@REQUIREMENT" }
    return Dependency(
        packageId = PackageId.parse(value.substring(0, separator)),
        requirement = VersionRequirement.parse(value.substring(separator + 1)),
    )
}
