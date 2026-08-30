package moe.afox.mcfpm.cli

import moe.afox.mcfpm.core.BundleBuilder
import moe.afox.mcfpm.core.ExternalArtifactIngester
import moe.afox.mcfpm.core.ExternalArtifactSpec
import moe.afox.mcfpm.core.ExternalSourceKind
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.ManifestSigner
import moe.afox.mcfpm.core.PackageManifestCodec
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.publish.central.CentralCredentials
import moe.afox.mcfpm.publish.central.CentralBundleInspector
import moe.afox.mcfpm.publish.central.CentralBundleBuilder
import moe.afox.mcfpm.publish.central.CentralBundleRequest
import moe.afox.mcfpm.publish.central.CentralDeveloper
import moe.afox.mcfpm.publish.central.CentralPomMetadata
import moe.afox.mcfpm.publish.central.CentralPublicationLayout
import moe.afox.mcfpm.publish.central.GpgDetachedSigner
import moe.afox.mcfpm.publish.central.CentralPortalClient
import java.net.URI
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import picocli.CommandLine

@CommandLine.Command(name = "search", description = ["Search Maven Central."], mixinStandardHelpOptions = true)
internal class SearchCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", paramLabel = "QUERY")
    private lateinit var query: String

    @CommandLine.Option(names = ["--rows"], defaultValue = "20")
    private var rows: Int = 20

    override fun execute(): Int {
        val services = McfpmServices(root)
        val documents = services.centralDocuments(services.searchCentral(query, rows))
        val summaries = documents.map { services.centralDocumentSummary(it.jsonObject) }
        val human = summaries.joinToString("\n") { item ->
            "${item.getValue("package").let { (it as JsonPrimitive).content }}@" +
                item.getValue("version").let { (it as JsonPrimitive).content }
        }
        return CliOutput.success(
            root,
            "search",
            JsonObject(mapOf("query" to JsonPrimitive(query), "results" to JsonArray(summaries))),
            human.ifEmpty { "No packages found" },
        )
    }
}

@CommandLine.Command(name = "info", description = ["Show versions indexed for a package on Maven Central."])
internal class InfoCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", paramLabel = "GROUP:NAME")
    private lateinit var packageId: String

    @CommandLine.Option(names = ["--rows"], defaultValue = "100")
    private var rows: Int = 100

    override fun execute(): Int {
        val id = PackageId.parse(packageId)
        val services = McfpmServices(root)
        val query = "g:${id.group} AND a:${id.name}"
        val documents = services.centralDocuments(services.searchCentral(query, rows, allVersions = true))
        val summaries = documents.map { services.centralDocumentSummary(it.jsonObject) }
        val versions = summaries.map { it.getValue("version") }.distinct()
        return CliOutput.success(
            root,
            "info",
            JsonObject(mapOf("package" to JsonPrimitive(id.value), "versions" to JsonArray(versions))),
            if (versions.isEmpty()) "No versions found for $id" else "$id: ${versions.joinToString { (it as JsonPrimitive).content }}",
        )
    }
}

@CommandLine.Command(
    name = "manifest",
    description = ["Inspect package manifests."],
    subcommands = [ManifestShowCommand::class, ManifestKeygenCommand::class, ManifestSignCommand::class],
)
internal class ManifestCommand : CliCallable() {
    override fun execute(): Int {
        spec.commandLine().usage(root.out())
        return CliExitCode.SUCCESS.value
    }
}

@CommandLine.Command(name = "keygen", description = ["Generate an Ed25519 descriptor-signing key pair."])
internal class ManifestKeygenCommand : CliCallable() {
    @CommandLine.Option(names = ["--private-key"], required = true, paramLabel = "PATH")
    private lateinit var privateKey: Path

    @CommandLine.Option(names = ["--public-key"], required = true, paramLabel = "PATH")
    private lateinit var publicKey: Path

    @CommandLine.Option(names = ["--force"])
    private var force: Boolean = false

    override fun execute(): Int {
        require(force || (!Files.exists(privateKey) && !Files.exists(publicKey))) {
            "Signing key file already exists; use --force to replace both files"
        }
        val keyPair = ManifestSigner.generateKeyPair()
        atomicWrite(privateKey, Base64.getEncoder().encode(keyPair.private.encoded))
        atomicWrite(publicKey, Base64.getEncoder().encode(keyPair.public.encoded))
        runCatching {
            Files.setPosixFilePermissions(
                privateKey,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        return CliOutput.success(
            root,
            "manifest keygen",
            JsonObject(
                mapOf(
                    "privateKey" to JsonPrimitive(privateKey.toAbsolutePath().normalize().toString()),
                    "publicKey" to JsonPrimitive(publicKey.toAbsolutePath().normalize().toString()),
                ),
            ),
            "Generated Ed25519 descriptor-signing key pair",
        )
    }
}

@CommandLine.Command(name = "sign", description = ["Sign a canonical package descriptor with Ed25519."])
internal class ManifestSignCommand : CliCallable() {
    @CommandLine.Option(names = ["--file"], paramLabel = "PATH")
    private var file: Path? = null

    @CommandLine.Option(names = ["--private-key"], required = true, paramLabel = "PATH")
    private lateinit var privateKey: Path

    @CommandLine.Option(names = ["--public-key"], required = true, paramLabel = "PATH")
    private lateinit var publicKey: Path

    override fun execute(): Int {
        val services = McfpmServices(root)
        val manifestPath = file ?: services.projectFiles().manifest
        val manifest = PackageManifestCodec.decode(Files.readAllBytes(manifestPath)).invalidateSignature()
        DraftProject.requirePublicationMetadata(manifest, "mcfpm manifest sign")
        val factory = KeyFactory.getInstance(ManifestSigner.ALGORITHM)
        val keyPair = KeyPair(
            factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(publicKey).trim()))),
            factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(privateKey).trim()))),
        )
        val signed = ManifestSigner.sign(manifest, keyPair)
        require(ManifestSigner.verify(signed)) { "Generated descriptor signature did not verify" }
        atomicWrite(manifestPath, PackageManifestCodec.encode(signed))
        return CliOutput.success(
            root,
            "manifest sign",
            JsonObject(
                mapOf(
                    "manifest" to JsonPrimitive(manifestPath.toAbsolutePath().normalize().toString()),
                    "fingerprint" to JsonPrimitive(requireNotNull(signed.signatureFingerprint)),
                ),
            ),
            "Signed $manifestPath with fingerprint ${signed.signatureFingerprint}",
        )
    }
}

@CommandLine.Command(name = "show", description = ["Show a local or repository package manifest."])
internal class ManifestShowCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "GROUP:NAME@VERSION")
    private var coordinate: String? = null

    @CommandLine.Option(names = ["--file"], paramLabel = "PATH")
    private var file: Path? = null

    @CommandLine.Option(names = ["--format"], defaultValue = "toml")
    private lateinit var format: String

    override fun execute(): Int {
        require(file == null || coordinate == null) { "Use either a coordinate or --file, not both" }
        val services = McfpmServices(root)
        val manifest = when {
            file != null -> PackageManifestCodec.decode(Files.readAllBytes(file))
            coordinate != null -> {
                val (id, version) = parseVersionCoordinate(requireNotNull(coordinate))
                when (val result = services.configuredRegistry().candidate(id, version)) {
                    is McfpmResult.Success -> result.value.manifest.manifest
                    is McfpmResult.Failure -> return CliOutput.failure(root, result.diagnostics)
                }
            }
            else -> services.loadManifest()
        }
        val data = CanonicalJson.format.encodeToJsonElement(PackageManifest.serializer(), manifest.normalized())
        val human = when (format.lowercase()) {
            "toml" -> PackageManifestCodec.encode(manifest).decodeToString().trimEnd()
            "json" -> CanonicalJson.encodeManifest(manifest).decodeToString()
            else -> throw IllegalArgumentException("format must be toml or json")
        }
        return CliOutput.success(root, "manifest show", data, human)
    }
}

@CommandLine.Command(
    name = "artifact",
    description = ["Inspect or fetch one payload artifact."],
    subcommands = [ArtifactFetchCommand::class],
)
internal class ArtifactCommand : CliCallable() {
    override fun execute(): Int {
        spec.commandLine().usage(root.out())
        return CliExitCode.SUCCESS.value
    }
}

@CommandLine.Command(name = "fetch", description = ["Fetch one artifact by exact coordinate and classifier."])
internal class ArtifactFetchCommand : CliCallable() {
    @CommandLine.Parameters(index = "0", paramLabel = "GROUP:NAME@VERSION")
    private lateinit var coordinate: String

    @CommandLine.Option(names = ["--classifier"], required = true)
    private lateinit var classifier: String

    @CommandLine.Option(names = ["--type"], paramLabel = "PAYLOAD_TYPE")
    private var type: String? = null

    @CommandLine.Option(names = ["--output"], required = true, paramLabel = "PATH")
    private lateinit var output: Path

    override fun execute(): Int {
        val (id, version) = parseVersionCoordinate(coordinate)
        val services = McfpmServices(root)
        val selected = when (val result = services.configuredRegistry().candidate(id, version)) {
            is McfpmResult.Success -> result.value
            is McfpmResult.Failure -> return CliOutput.failure(root, result.diagnostics)
        }
        val repository = selected.repository
        val manifest = selected.manifest.manifest
        val matching = manifest.artifacts.filter { artifact ->
            artifact.classifier == classifier && (type == null || artifact.type == PayloadType.parse(requireNotNull(type)))
        }
        require(matching.size == 1) { "Artifact selection must match exactly one payload" }
        val artifact = matching.single()
        val artifactUri = repository.artifactUri(id, version, artifact)
        return services.cache.fetch(
            artifactUri,
            artifact.sha256,
            artifact.size,
            root.offline,
            repository.requestHeaders(artifactUri),
        ).foldCli(root) { cached ->
            atomicWrite(output, Files.readAllBytes(cached.path))
            CliOutput.success(
                root,
                "artifact fetch",
                JsonObject(
                    mapOf(
                        "output" to JsonPrimitive(output.toAbsolutePath().normalize().toString()),
                        "sha256" to JsonPrimitive(artifact.sha256),
                        "size" to JsonPrimitive(artifact.size),
                    ),
                ),
                "Fetched ${id}@${version}:${artifact.classifier} to $output",
            )
        }
    }
}

@CommandLine.Command(name = "pack", description = ["Build a reproducible payload ZIP or ingest an immutable external artifact."])
internal class PackCommand : CliCallable() {
    @CommandLine.Option(names = ["--input"], paramLabel = "DIRECTORY")
    private var input: Path? = null

    @CommandLine.Option(names = ["--source-url"], paramLabel = "URI")
    private var sourceUrl: URI? = null

    @CommandLine.Option(names = ["--source-kind"], defaultValue = "URL")
    private lateinit var sourceKind: ExternalSourceKind

    @CommandLine.Option(names = ["--canonical-id"])
    private var canonicalId: String? = null

    @CommandLine.Option(names = ["--immutable-version"])
    private var immutableVersion: String? = null

    @CommandLine.Option(names = ["--expected-sha256"])
    private var expectedSha256: String? = null

    @CommandLine.Option(names = ["--expected-size"])
    private var expectedSize: Long? = null

    @CommandLine.Option(names = ["--source-license"])
    private var sourceLicense: String? = null

    @CommandLine.Option(names = ["--type"], required = true, paramLabel = "PAYLOAD_TYPE")
    private lateinit var type: String

    @CommandLine.Option(names = ["--classifier"], required = true)
    private lateinit var classifier: String

    @CommandLine.Option(names = ["--output"], required = true, paramLabel = "PATH")
    private lateinit var output: Path

    @CommandLine.Option(names = ["--register"], description = ["Update the nearest mcfpm.toml artifact entry."])
    private var register: Boolean = false

    override fun execute(): Int {
        require((input == null) != (sourceUrl == null)) { "Use exactly one of --input or --source-url" }
        val payloadType = PayloadType.parse(type)
        val services = McfpmServices(root)
        if (register) {
            DraftProject.requirePublicationMetadata(services.loadManifest(), "mcfpm pack --register")
        }
        val (bytes, descriptor) = if (input != null) {
            val packed = integrityOperation("Unable to pack payload") {
                ReproducibleZip.fromDirectory(requireNotNull(input)).also { bytes ->
                    ReproducibleZip.verify(bytes, requiresPackMetadata(payloadType))
                }
            }
            packed to ArtifactDescriptor(
                type = payloadType,
                classifier = classifier,
                sha256 = Hashing.sha256(packed),
                size = packed.size.toLong(),
            )
        } else {
            val specification = ExternalArtifactSpec(
                canonicalId = requireNotNull(canonicalId) { "--canonical-id is required for external ingestion" },
                sourceKind = sourceKind,
                uri = requireNotNull(sourceUrl),
                immutableVersion = requireNotNull(immutableVersion) { "--immutable-version is required" },
                expectedSha256 = requireNotNull(expectedSha256) { "--expected-sha256 is required" },
                expectedSize = requireNotNull(expectedSize) { "--expected-size is required" },
                redistributionLicense = requireNotNull(sourceLicense) { "--source-license is required" },
                type = payloadType,
                classifier = classifier,
            )
            when (val packed = ExternalArtifactIngester(services.cache).ingest(listOf(specification))) {
                is McfpmResult.Success -> packed.value.single().let { it.bytes to it.descriptor }
                is McfpmResult.Failure -> return CliOutput.failure(root, packed.diagnostics)
            }
        }
        atomicWrite(output, bytes)
        if (register) {
            val project = services.projectFiles()
            val manifest = services.loadManifest(project.manifest)
            val artifacts = manifest.artifacts.filterNot {
                it.type == descriptor.type && it.classifier == descriptor.classifier
            } + descriptor
            atomicWrite(
                project.manifest,
                PackageManifestCodec.encode(manifest.copy(artifacts = artifacts).invalidateSignature().normalized()),
            )
        }
        return CliOutput.success(
            root,
            "pack",
            JsonObject(
                mapOf(
                    "output" to JsonPrimitive(output.toAbsolutePath().normalize().toString()),
                    "artifact" to CanonicalJson.format.encodeToJsonElement(ArtifactDescriptor.serializer(), descriptor),
                    "registered" to JsonPrimitive(register),
                ),
            ),
            "Packed ${descriptor.type}:${descriptor.classifier} (${descriptor.size} bytes) to $output",
        )
    }
}

@CommandLine.Command(name = "verify", description = ["Verify a lock graph or a standalone payload file."])
internal class VerifyCommand : CliCallable() {
    @CommandLine.Option(names = ["--lock"], paramLabel = "PATH")
    private var lockfile: Path? = null

    @CommandLine.Option(names = ["--file"], paramLabel = "PATH")
    private var file: Path? = null

    @CommandLine.Option(names = ["--sha256"])
    private var sha256: String? = null

    @CommandLine.Option(names = ["--size"])
    private var size: Long? = null

    @CommandLine.Option(names = ["--pack-metadata"])
    private var packMetadata: Boolean = false

    override fun execute(): Int {
        if (file != null) {
            val path = requireNotNull(file)
            require(Files.isRegularFile(path)) { "Artifact does not exist: $path" }
            val bytes = Files.readAllBytes(path)
            integrityOperation("Artifact verification failed") {
                sha256?.let { require(Hashing.sha256(bytes) == it) { "Artifact SHA-256 does not match" } }
                size?.let { require(bytes.size.toLong() == it) { "Artifact size does not match" } }
                if (path.fileName.toString().endsWith(".zip")) ReproducibleZip.verify(bytes, packMetadata)
            }
            return CliOutput.success(
                root,
                "verify",
                JsonObject(mapOf("sha256" to JsonPrimitive(Hashing.sha256(bytes)), "size" to JsonPrimitive(bytes.size))),
                "Verified $path",
            )
        }
        val services = McfpmServices(root)
        val (graph, client) = services.readGraphAndClient(lockfile)
        return client.fetch(graph, root.offline).foldCli(root) { fetched ->
            client.verify(fetched).foldCli(root) { verified ->
                CliOutput.success(
                    root,
                    "verify",
                    JsonObject(mapOf("artifacts" to JsonPrimitive(verified.artifacts.size))),
                    "Verified ${verified.artifacts.size} artifact(s)",
                )
            }
        }
    }
}

@CommandLine.Command(name = "bundle", description = ["Create a non-merged multi-payload bundle directory."])
internal class BundleCommand : CliCallable() {
    @CommandLine.Option(names = ["--lock"], paramLabel = "PATH")
    private var lockfile: Path? = null

    @CommandLine.Option(names = ["--output"], defaultValue = "build/mcfpm/bundle", paramLabel = "DIRECTORY")
    private lateinit var output: Path

    override fun execute(): Int {
        val services = McfpmServices(root)
        val (graph, client) = services.readGraphAndClient(lockfile)
        return client.fetch(graph, root.offline).foldCli(root) { fetched ->
            client.verify(fetched).foldCli(root) { verified ->
                BundleBuilder.build(verified).foldCli(root) { bundle ->
                    Files.createDirectories(output)
                    bundle.files.toSortedMap().forEach { (name, bytes) -> atomicWrite(output.resolve(name), bytes) }
                    atomicWrite(output.resolve("mcfpm-bundle.json"), bundle.manifest)
                    val warnings = bundle.diagnostics.map { diagnostic -> JsonPrimitive(diagnostic.message) }
                    CliOutput.success(
                        root,
                        "bundle",
                        JsonObject(
                            mapOf(
                                "directory" to JsonPrimitive(output.toAbsolutePath().normalize().toString()),
                                "files" to JsonArray(bundle.files.keys.sorted().map(::JsonPrimitive)),
                                "warnings" to JsonArray(warnings),
                            ),
                        ),
                        "Created ${bundle.files.size} payload(s) in $output",
                    )
                }
            }
        }
    }
}

@CommandLine.Command(name = "publish", description = ["Upload a validated Central bundle and wait for validation."])
internal class PublishCommand : CliCallable() {
    @CommandLine.Option(names = ["--bundle"], paramLabel = "ZIP", description = ["Upload an already validated Central bundle."])
    private var bundle: Path? = null

    @CommandLine.Option(names = ["--manifest"], paramLabel = "PATH")
    private var manifestFile: Path? = null

    @CommandLine.Option(names = ["--lock"], paramLabel = "PATH")
    private var lockfile: Path? = null

    @CommandLine.Option(names = ["--artifact"], paramLabel = "CLASSIFIER=PATH")
    private var artifacts: MutableList<String> = mutableListOf()

    @CommandLine.Option(names = ["--output"], defaultValue = "build/mcfpm/central-bundle.zip", paramLabel = "ZIP")
    private lateinit var output: Path

    @CommandLine.Option(names = ["--signing-key"], description = ["OpenPGP key ID/fingerprint used by gpg."])
    private var signingKey: String? = null

    @CommandLine.Option(names = ["--gpg-executable"], defaultValue = "gpg")
    private lateinit var gpgExecutable: String

    @CommandLine.Option(names = ["--display-name"])
    private var displayName: String? = null

    @CommandLine.Option(names = ["--description"])
    private var description: String? = null

    @CommandLine.Option(names = ["--project-url"])
    private var projectUrl: String? = null

    @CommandLine.Option(names = ["--scm-url"])
    private var scmUrl: String? = null

    @CommandLine.Option(names = ["--license-name"])
    private var licenseName: String? = null

    @CommandLine.Option(names = ["--license-url"])
    private var licenseUrl: String? = null

    @CommandLine.Option(names = ["--developer"], paramLabel = "ID=NAME[:EMAIL]")
    private var developers: MutableList<String> = mutableListOf()

    @CommandLine.Option(names = ["--name"])
    private var deploymentName: String? = null

    @CommandLine.Option(names = ["--username"])
    private var username: String? = null

    @CommandLine.Option(names = ["--password"])
    private var password: String? = null

    @CommandLine.Option(names = ["--release"], description = ["Permanently publish after Central validation."])
    private var release: Boolean = false

    @CommandLine.Option(names = ["--prepare-only"], description = ["Generate and validate the signed Central bundle without uploading."])
    private var prepareOnly: Boolean = false

    @CommandLine.Option(names = ["--timeout-seconds"], defaultValue = "600")
    private var timeoutSeconds: Long = 600

    override fun execute(): Int {
        val prepared = bundle?.let { path ->
            require(Files.isRegularFile(path)) { "Central bundle does not exist: $path" }
            PreparedCentralBundle(Files.readAllBytes(path), path, deploymentName ?: path.fileName.toString().removeSuffix(".zip"))
        } ?: buildCentralBundle()
        val bundleBytes = prepared.bytes
        when (val validation = CentralBundleInspector.validate(bundleBytes)) {
            is McfpmResult.Success -> Unit
            is McfpmResult.Failure -> return CliOutput.failure(root, validation.diagnostics)
        }
        if (prepareOnly) {
            return CliOutput.success(
                root,
                "publish",
                JsonObject(
                    mapOf(
                        "bundle" to JsonPrimitive(prepared.path.toAbsolutePath().normalize().toString()),
                        "state" to JsonPrimitive("PREPARED"),
                        "released" to JsonPrimitive(false),
                    ),
                ),
                "Generated and validated Central bundle ${prepared.path}",
            )
        }
        val resolvedUsername = username ?: System.getenv("CENTRAL_USERNAME")
        val resolvedPassword = password ?: System.getenv("CENTRAL_PASSWORD")
        require(!resolvedUsername.isNullOrBlank() && !resolvedPassword.isNullOrBlank()) {
            "Central credentials require --username/--password or CENTRAL_USERNAME/CENTRAL_PASSWORD"
        }
        return CentralPortalClient().publish(
            bundleBytes,
            CentralCredentials(resolvedUsername, resolvedPassword),
            prepared.deploymentName,
            release,
            timeout = Duration.ofSeconds(timeoutSeconds),
        ).foldCli(root) { deployment ->
            CliOutput.success(
                root,
                "publish",
                JsonObject(
                    mapOf(
                        "deploymentId" to JsonPrimitive(deployment.id),
                        "state" to JsonPrimitive(deployment.state.name),
                        "released" to JsonPrimitive(deployment.released),
                    ),
                ),
                if (deployment.released) "Published Central deployment ${deployment.id}" else
                    "Central deployment ${deployment.id} validated; rerun with --release to publish permanently",
            )
        }
    }

    private fun buildCentralBundle(): PreparedCentralBundle {
        val services = McfpmServices(root)
        val project = runCatching { services.projectFiles() }.getOrNull()
        val manifestPath = manifestFile ?: project?.manifest
            ?: throw IllegalArgumentException("--manifest is required outside an Mcfpm project")
        val lockPath = lockfile ?: project?.lockfile
            ?: throw IllegalArgumentException("--lock is required outside an Mcfpm project")
        val manifest = PackageManifestCodec.decode(Files.readAllBytes(manifestPath))
        DraftProject.requirePublicationMetadata(manifest, "mcfpm publish")
        val artifactBytes = if (artifacts.isEmpty()) {
            manifest.artifacts.associate { artifact ->
                val path = manifestPath.toAbsolutePath().normalize().parent
                    .resolve("build/mcfpm/payloads/${artifact.classifier}.${artifact.extension}")
                require(Files.isRegularFile(path)) {
                    "Missing payload $path; pass --artifact ${artifact.classifier}=PATH"
                }
                artifact.classifier to Files.readAllBytes(path)
            }
        } else {
            val classifiers = artifacts.map { declaration -> declaration.substringBefore('=') }
            require(classifiers.distinct().size == classifiers.size) { "Each --artifact classifier may be declared only once" }
            artifacts.associate { declaration ->
                val separator = declaration.indexOf('=')
                require(separator > 0 && separator < declaration.lastIndex) { "--artifact must use CLASSIFIER=PATH" }
                val classifier = declaration.substring(0, separator)
                val path = Path.of(declaration.substring(separator + 1))
                require(Files.isRegularFile(path)) { "Payload does not exist: $path" }
                classifier to Files.readAllBytes(path)
            }
        }
        val options = manifest.tool.options
        val pom = CentralPomMetadata(
            displayName = displayName ?: options["publish.display-name"] ?: manifest.packageId.name,
            description = requiredMetadata("description", description, options["publish.description"]),
            projectUrl = requiredMetadata("project-url", projectUrl, options["publish.project-url"]),
            scmUrl = requiredMetadata("scm-url", scmUrl, options["publish.scm-url"]),
            licenseName = licenseName ?: options["publish.license-name"] ?: manifest.license,
            licenseUrl = requiredMetadata("license-url", licenseUrl, options["publish.license-url"]),
            developers = parseDevelopers(
                if (developers.isEmpty()) options["publish.developer"]?.let(::listOf).orEmpty() else developers,
            ),
        )
        val key = signingKey ?: options["publish.signing-key"]
            ?: throw IllegalArgumentException("--signing-key or tool option publish.signing-key is required")
        validatePublicationArtifacts(manifest, artifactBytes)
        val signer = GpgDetachedSigner(gpgExecutable)
        val unsignedFiles = CentralPublicationLayout.files(manifest, artifactBytes, pom)
        val signatures = when (val result = signer.sign(unsignedFiles, key)) {
            is McfpmResult.Success -> result.value
            is McfpmResult.Failure -> throw CliDiagnosticException(result.diagnostics)
        }
        val request = CentralBundleRequest(
            manifest = manifest,
            lockfile = Files.readAllBytes(lockPath),
            artifactsByClassifier = artifactBytes,
            signaturesByFileName = signatures,
            pom = pom,
        )
        val bytes = when (val result = CentralBundleBuilder(signer).build(request)) {
            is McfpmResult.Success -> result.value
            is McfpmResult.Failure -> throw CliDiagnosticException(result.diagnostics)
        }
        atomicWrite(output, bytes)
        return PreparedCentralBundle(
            bytes,
            output,
            deploymentName ?: "${manifest.packageId.name}-${manifest.version}",
        )
    }

    private fun requiredMetadata(name: String, option: String?, configured: String?): String =
        option ?: configured ?: throw IllegalArgumentException("--$name or tool option publish.$name is required")

    private fun parseDevelopers(values: List<String>): List<CentralDeveloper> {
        require(values.isNotEmpty()) { "At least one --developer ID=NAME[:EMAIL] is required" }
        return values.map { value ->
            val separator = value.indexOf('=')
            require(separator > 0 && separator < value.lastIndex) { "--developer must use ID=NAME[:EMAIL]" }
            val id = value.substring(0, separator)
            val identity = value.substring(separator + 1)
            val emailSeparator = identity.lastIndexOf(':')
            if (emailSeparator > 0 && '@' in identity.substring(emailSeparator + 1)) {
                CentralDeveloper(id, identity.substring(0, emailSeparator), identity.substring(emailSeparator + 1))
            } else {
                CentralDeveloper(id, identity)
            }
        }
    }

    private fun validatePublicationArtifacts(manifest: PackageManifest, bytes: Map<String, ByteArray>) {
        val expected = manifest.artifacts.map { it.classifier }.toSet()
        val actual = bytes.keys
        val diagnostics = buildList {
            if (expected != actual) {
                add(
                    Diagnostic(
                        DiagnosticCode.PUBLISH_VALIDATION_FAILED,
                        DiagnosticSeverity.ERROR,
                        "Payload classifiers do not match mcfpm.toml",
                        mapOf("expected" to expected.sorted().joinToString(), "actual" to actual.sorted().joinToString()),
                    ),
                )
            }
            manifest.artifacts.forEach { artifact ->
                val content = bytes[artifact.classifier] ?: return@forEach
                if (content.size.toLong() != artifact.size || Hashing.sha256(content) != artifact.sha256) {
                    add(
                        Diagnostic(
                            DiagnosticCode.PUBLISH_VALIDATION_FAILED,
                            DiagnosticSeverity.ERROR,
                            "Payload ${artifact.classifier} does not match its declared checksum and size",
                        ),
                    )
                }
            }
        }
        if (diagnostics.isNotEmpty()) throw CliDiagnosticException(diagnostics)
    }

    private data class PreparedCentralBundle(
        val bytes: ByteArray,
        val path: Path,
        val deploymentName: String,
    )
}

private fun parseVersionCoordinate(value: String): Pair<PackageId, SemVer> {
    val separator = value.lastIndexOf('@')
    require(separator > 0 && separator < value.lastIndex) { "Coordinate must use GROUP:NAME@VERSION syntax" }
    return PackageId.parse(value.substring(0, separator)) to SemVer.parse(value.substring(separator + 1))
}

private fun requiresPackMetadata(type: PayloadType): Boolean =
    type == PayloadType.MINECRAFT_DATAPACK || type == PayloadType.MINECRAFT_RESOURCEPACK

private fun <T> integrityOperation(label: String, operation: () -> T): T = try {
    operation()
} catch (exception: Exception) {
    throw CliDiagnosticException(
        listOf(
            Diagnostic(
                DiagnosticCode.INTEGRITY_FAILURE,
                DiagnosticSeverity.ERROR,
                "$label: ${exception.message}",
            ),
        ),
    )
}
