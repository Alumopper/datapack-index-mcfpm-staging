package moe.afox.mcfpm.cli

import moe.afox.mcfpm.core.PackageManifestCodec
import moe.afox.mcfpm.core.ManifestSigner
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.model.ResolvedPackage
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.ToolConfiguration
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class CliContractTest {
    @Test
    fun `json mode emits one schema v1 document and no stderr logs`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-json")
        val result = run(workspace, "--json", "init", "--id", "example:root")

        assertEquals(0, result.exitCode)
        assertEquals("", result.stderr)
        val json = CanonicalJson.format.parseToJsonElement(result.stdout.trim()).jsonObject
        assertEquals(1, json.getValue("schema").jsonPrimitive.content.toInt())
        assertEquals(true, json.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("init", json.getValue("command").jsonPrimitive.content)
    }

    @Test
    fun `json help remains a single machine readable document`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-help")
        val result = run(workspace, "--json", "trust", "--help")

        assertEquals(0, result.exitCode)
        assertEquals("", result.stderr)
        val json = CanonicalJson.format.parseToJsonElement(result.stdout.trim()).jsonObject
        assertEquals("help", json.getValue("command").jsonPrimitive.content)
        assertEquals(true, json.getValue("ok").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `new consumers default to afox then central while explicit central stays central`() {
        val fresh = Files.createTempDirectory("mcfpm-cli-default-repositories")
        val freshConfig = run(fresh, "--json", "config")
        assertEquals(0, freshConfig.exitCode, freshConfig.stderr)
        val freshData = CanonicalJson.format.parseToJsonElement(freshConfig.stdout.trim())
            .jsonObject.getValue("data").jsonObject
        assertEquals("afox", freshData.getValue("defaultRepository").jsonPrimitive.content)
        assertEquals(
            listOf("afox", "central"),
            freshData.getValue("repositoryPriority").jsonArray.map { it.jsonPrimitive.content },
        )

        val central = Files.createTempDirectory("mcfpm-cli-explicit-central")
        val explicit = run(central, "--default-repository", "central", "--json", "config")
        assertEquals(0, explicit.exitCode, explicit.stderr)
        val explicitData = CanonicalJson.format.parseToJsonElement(explicit.stdout.trim())
            .jsonObject.getValue("data").jsonObject
        assertEquals(listOf("central"), explicitData.getValue("repositoryPriority").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `GitHub import command exposes target repository without colliding with repository URL overrides`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-import-help")
        val result = run(workspace, "--json", "import", "github", "--help")

        assertEquals(0, result.exitCode)
        val json = CanonicalJson.format.parseToJsonElement(result.stdout.trim()).jsonObject
        val usage = json.getValue("data").jsonObject.getValue("usage").jsonPrimitive.content
        assertTrue('\u001B' !in usage, usage)
        val compactUsage = usage.replace(Regex("\\s+"), "")
        assertTrue(compactUsage.contains("--repository=ID"), usage)
        assertTrue(compactUsage.contains("--repository-url"), usage)
    }

    @Test
    fun `install help documents direct dependency coordinates`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-install-help")
        val result = run(workspace, "install", "--help")

        assertEquals(0, result.exitCode)
        val plainUsage = result.stdout.replace(Regex("\u001B\\[[;\\d]*m"), "")
        assertTrue(plainUsage.replace(Regex("\\s+"), "").contains("GROUP:NAME@REQUIREMENT"), result.stdout)
    }

    @Test
    fun `init add and remove update the canonical manifest`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-project")
        assertEquals(0, run(workspace, "init", "--id", "example:root", "--version", "1.2.3").exitCode)
        assertEquals(0, run(workspace, "add", "example:library@^2.0.0", "--feature", "api").exitCode)

        val added = PackageManifestCodec.decode(Files.readAllBytes(workspace.resolve("mcfpm.toml")))
        assertEquals(PackageId.parse("example:library"), added.dependencies.single().packageId)
        assertEquals(listOf("api"), added.dependencies.single().features)

        assertEquals(0, run(workspace, "remove", "example:library").exitCode)
        val removed = PackageManifestCodec.decode(Files.readAllBytes(workspace.resolve("mcfpm.toml")))
        assertTrue(removed.dependencies.isEmpty())
    }

    @Test
    fun `dependency-free project resolves and installs without Minecraft writes`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-empty")
        assertEquals(0, run(workspace, "init", "--id", "example:standalone").exitCode)
        assertEquals(0, run(workspace, "resolve").exitCode)
        assertTrue(Files.isRegularFile(workspace.resolve("mcfpm.lock")))

        val installed = run(workspace, "--json", "install", "--offline")
        assertEquals(0, installed.exitCode)
        assertEquals("", installed.stderr)
        val json = CanonicalJson.format.parseToJsonElement(installed.stdout.trim()).jsonObject
        assertEquals(false, json.getValue("data").jsonObject.getValue("mutated").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `direct install creates a draft consumer project and downloads the dependency`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-direct-install")
        val repository = Files.createTempDirectory("mcfpm-cli-direct-install-repository")
        val dependencyId = PackageId.parse("example.pack:demo")
        val dependencyVersion = SemVer.parse("1.0.0")
        writeRepositoryPackage(repository, dependencyId, dependencyVersion)

        val installed = run(
            workspace,
            "--repository-url",
            "test=${repository.toUri()}",
            "--default-repository",
            "test",
            "install",
            "$dependencyId@$dependencyVersion",
        )

        assertEquals(
            0,
            installed.exitCode,
            "stdout:\n${installed.stdout}\nstderr:\n${installed.stderr}",
        )
        assertTrue(installed.stdout.contains("Created"))
        assertTrue(installed.stdout.contains("downloaded, and verified 1 artifact(s)"))
        val manifest = PackageManifestCodec.decode(Files.readAllBytes(workspace.resolve("mcfpm.toml")))
        assertEquals(DraftProject.packageId, manifest.packageId)
        assertEquals(DraftProject.version, manifest.version)
        assertEquals(DraftProject.license, manifest.license)
        assertEquals(dependencyId, manifest.dependencies.single().packageId)
        assertEquals(repository.toUri().toString(), manifest.tool.repositories.getValue("test"))
        assertTrue(Files.isRegularFile(workspace.resolve("mcfpm.lock")))

        val completed = run(
            workspace,
            "init",
            "--id",
            "example.author:published-pack",
            "--version",
            "1.2.3",
            "--license",
            "MIT",
        )
        assertEquals(0, completed.exitCode, completed.stderr)
        val publishable = PackageManifestCodec.decode(Files.readAllBytes(workspace.resolve("mcfpm.toml")))
        assertEquals(PackageId.parse("example.author:published-pack"), publishable.packageId)
        assertEquals(SemVer.parse("1.2.3"), publishable.version)
        assertEquals("MIT", publishable.license)
        assertEquals(dependencyId, publishable.dependencies.single().packageId)
        assertEquals(repository.toUri().toString(), publishable.tool.repositories.getValue("test"))
    }

    @Test
    fun `repository overrides persist when direct install updates an existing draft`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-persisted-repository")
        Files.write(
            workspace.resolve("mcfpm.toml"),
            PackageManifestCodec.encode(DraftProject.manifest(McfpmCommand(workspace))),
        )
        val repository = Files.createTempDirectory("mcfpm-cli-persisted-repository-source")
        val dependencyId = PackageId.parse("example.private:persistent-demo")
        val dependencyVersion = SemVer.parse("1.0.0")
        writeRepositoryPackage(repository, dependencyId, dependencyVersion)

        val configured = run(
            workspace,
            "--repository-url",
            "private=${repository.toUri()}",
            "--default-repository",
            "private",
            "install",
            "$dependencyId@$dependencyVersion",
        )
        assertEquals(0, configured.exitCode, configured.stderr)

        val manifest = PackageManifestCodec.decode(Files.readAllBytes(workspace.resolve("mcfpm.toml")))
        assertEquals("private", manifest.tool.defaultRepository)
        assertEquals(repository.toUri().toString(), manifest.tool.repositories.getValue("private"))

        val reused = run(workspace, "install", "$dependencyId@$dependencyVersion")
        assertEquals(0, reused.exitCode, reused.stderr)
    }

    @Test
    fun `direct install from datapacks resolves and deploys into its world with progress`() {
        val world = Files.createTempDirectory("mcfpm-cli-direct-install-world")
        Files.write(world.resolve("level.dat"), levelDat())
        Files.write(world.resolve("session.lock"), ByteArray(8))
        val workspace = world.resolve("datapacks").createDirectories()
        val repository = Files.createTempDirectory("mcfpm-cli-direct-install-world-repository")
        val dependencyId = PackageId.parse("example.pack:world-demo")
        val dependencyVersion = SemVer.parse("1.0.0")
        writeRepositoryPackage(repository, dependencyId, dependencyVersion)

        val installed = run(
            workspace,
            "--repository-url",
            "test=${repository.toUri()}",
            "--default-repository",
            "test",
            "install",
            "$dependencyId@$dependencyVersion",
        )

        assertEquals(0, installed.exitCode, installed.stderr)
        assertTrue(installed.stdout.contains("Installed: 1 copy/copies"))
        assertTrue(installed.stderr.contains("[1/4] Resolving dependencies"))
        assertTrue(installed.stderr.contains("[4/4] Installing into world"))
        assertTrue(Files.isRegularFile(workspace.resolve("mcfpm.toml")))
        assertTrue(Files.isRegularFile(workspace.resolve("mcfpm.lock")))
        val installedPacks = Files.list(workspace).use { paths ->
            paths.filter { it.fileName.toString().startsWith("mcfpm-example-pack-world-demo-") }.toList()
        }
        assertEquals(1, installedPacks.size)
        assertTrue(Files.isRegularFile(world.resolve(".mcfpm/latest")))
    }

    @Test
    fun `draft consumer project must declare publication metadata before registered packing`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-draft-pack")
        Files.write(workspace.resolve("mcfpm.toml"), PackageManifestCodec.encode(DraftProject.manifest(McfpmCommand(workspace))))
        val payload = workspace.resolve("payload").createDirectories()
        Files.writeString(payload.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"test\"}}")
        Files.createDirectories(payload.resolve("data/example/function"))
        Files.writeString(payload.resolve("data/example/function/main.mcfunction"), "say hello\n")

        val result = run(
            workspace,
            "pack",
            "--input",
            payload.toString(),
            "--type",
            "minecraft.datapack",
            "--classifier",
            "datapack",
            "--output",
            workspace.resolve("payload.zip").toString(),
            "--register",
        )

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("package.id, package.version, package.license"))
        assertTrue(Files.notExists(workspace.resolve("payload.zip")))
    }

    @Test
    fun `parameter failures use the stable argument exit code in json mode`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-errors")
        val result = run(workspace, "--json", "init")

        assertEquals(2, result.exitCode)
        assertEquals("", result.stderr)
        val json = CanonicalJson.format.parseToJsonElement(result.stdout.trim()).jsonObject
        assertEquals(false, json.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals(2, json.getValue("exitCode").jsonPrimitive.content.toInt())
    }

    @Test
    fun `import package version is not mistaken for the top level version flag`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-import-version")
        val result = run(
            workspace,
            "--json",
            "--offline",
            "import",
            "github",
            "acme/demo",
            "--tag",
            "v1.2.3",
            "--version",
            "1.2.3",
            "--repository",
            "central",
        )

        assertEquals(2, result.exitCode)
        val json = CanonicalJson.format.parseToJsonElement(result.stdout.trim()).jsonObject
        assertEquals(false, json.getValue("ok").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `integrity failures use the stable integrity exit code`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-integrity")
        val artifact = workspace.resolve("payload.bin")
        Files.writeString(artifact, "changed")

        val result = run(
            workspace,
            "--json",
            "verify",
            "--file",
            artifact.toString(),
            "--sha256",
            "0".repeat(64),
        )

        assertEquals(4, result.exitCode)
        val json = CanonicalJson.format.parseToJsonElement(result.stdout.trim()).jsonObject
        assertEquals(4, json.getValue("exitCode").jsonPrimitive.content.toInt())
    }

    @Test
    fun `pack is byte reproducible and registers the classifier`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-pack")
        val payload = workspace.resolve("payload").createDirectories()
        Files.writeString(payload.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"test\"}}")
        Files.createDirectories(payload.resolve("data/example/function"))
        Files.writeString(payload.resolve("data/example/function/main.mcfunction"), "say hello\n")
        assertEquals(0, run(workspace, "init", "--id", "example:root").exitCode)

        val first = workspace.resolve("first.zip")
        val second = workspace.resolve("second.zip")
        val common = arrayOf("--input", payload.toString(), "--type", "minecraft.datapack", "--classifier", "datapack")
        assertEquals(0, run(workspace, "pack", *common, "--output", first.toString(), "--register").exitCode)
        assertEquals(0, run(workspace, "pack", *common, "--output", second.toString()).exitCode)

        assertTrue(Files.readAllBytes(first).contentEquals(Files.readAllBytes(second)))
        val manifest = PackageManifestCodec.decode(Files.readAllBytes(workspace.resolve("mcfpm.toml")))
        assertEquals("datapack", manifest.artifacts.single().classifier)
    }

    @Test
    fun `manifest key generation and signing produce a verifiable descriptor`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-sign")
        val privateKey = workspace.resolve("descriptor.key")
        val publicKey = workspace.resolve("descriptor.pub")
        assertEquals(0, run(workspace, "init", "--id", "example:signed").exitCode)
        assertEquals(
            0,
            run(
                workspace,
                "manifest",
                "keygen",
                "--private-key",
                privateKey.toString(),
                "--public-key",
                publicKey.toString(),
            ).exitCode,
        )
        assertEquals(
            0,
            run(
                workspace,
                "manifest",
                "sign",
                "--private-key",
                privateKey.toString(),
                "--public-key",
                publicKey.toString(),
            ).exitCode,
        )

        val manifest = PackageManifestCodec.decode(Files.readAllBytes(workspace.resolve("mcfpm.toml")))
        assertTrue(ManifestSigner.isSigned(manifest))
        assertTrue(ManifestSigner.verify(manifest))
    }

    @Test
    fun `private repository credentials are loaded from named environment variables for locked sources`() {
        val workspace = Files.createTempDirectory("mcfpm-cli-private-repository")
        val repositoryUrl = "https://nexus.example.invalid/repository/maven-snapshots/"
        val packageId = PackageId.parse("example.private:pack")
        val version = SemVer.parse("1.0.0-SNAPSHOT")
        val manifest = PackageManifest(
            packageId = PackageId.parse("example:root"),
            version = SemVer.parse("1.0.0"),
            license = "MIT",
            tool = ToolConfiguration(
                defaultRepository = "private",
                repositories = mapOf("private" to repositoryUrl),
                bindings = mapOf(packageId.group to "private"),
                options = mapOf(
                    "repository.private.username-env" to "TEST_NEXUS_USERNAME",
                    "repository.private.password-env" to "TEST_NEXUS_PASSWORD",
                ),
            ),
        )
        Files.write(workspace.resolve("mcfpm.toml"), PackageManifestCodec.encode(manifest))
        val environment = mapOf(
            "TEST_NEXUS_USERNAME" to "test-user",
            "TEST_NEXUS_PASSWORD" to "test-password",
        )
        val services = McfpmServices(McfpmCommand(workspace), environment::get)

        val configured = services.configuredRegistry().repositoryFor(packageId)
        val ownedArtifact = URI.create("${repositoryUrl}example/private/pack.zip")
        assertTrue(configured.requestHeaders(ownedArtifact).containsKey("Authorization"))
        assertTrue(configured.requestHeaders(URI.create("https://example.invalid/pack.zip")).isEmpty())

        val graph = ResolvedGraph(
            resolverVersion = "test",
            roots = listOf(packageId),
            packages = listOf(
                ResolvedPackage(
                    packageId = packageId,
                    version = version,
                    repositoryUrl = repositoryUrl,
                    descriptorSha256 = "a".repeat(64),
                ),
            ),
            edges = emptyList(),
            loadOrder = emptyList(),
        )
        val locked = services.lockedRegistry(graph).repositoryFor(packageId)
        assertTrue(locked.requestHeaders(ownedArtifact).containsKey("Authorization"))
    }

    private fun run(workingDirectory: Path, vararg args: String): CommandResult {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val cache = workingDirectory.resolve("cache")
        val trust = workingDirectory.resolve("trust.toml")
        val fullArgs = arrayOf("--cache-dir", cache.toString(), "--trust-store", trust.toString(), *args)
        val exit = runMcfpm(fullArgs, workingDirectory, PrintWriter(stdout, true), PrintWriter(stderr, true))
        return CommandResult(exit, stdout.toString().replace("\r\n", "\n"), stderr.toString().replace("\r\n", "\n"))
    }

    private fun writeRepositoryPackage(repository: Path, packageId: PackageId, version: SemVer) {
        val payload = ReproducibleZip.fromEntries(
            listOf(
                "pack.mcmeta" to """{"pack":{"pack_format":48,"description":"direct install"}}""".encodeToByteArray(),
                "data/example/function/main.mcfunction" to "say installed\n".encodeToByteArray(),
            ),
        )
        val manifest = PackageManifest(
            packageId = packageId,
            version = version,
            license = "MIT",
            artifacts = listOf(
                ArtifactDescriptor(
                    type = PayloadType.MINECRAFT_DATAPACK,
                    classifier = "datapack",
                    sha256 = Hashing.sha256(payload),
                    size = payload.size.toLong(),
                ),
            ),
        )
        val packageDirectory = repository.resolve(packageId.group.replace('.', '/')).resolve(packageId.name)
        val versionDirectory = packageDirectory.resolve(version.toString()).createDirectories()
        Files.write(
            packageDirectory.resolve("maven-metadata.xml"),
            """
            <metadata>
              <groupId>${packageId.group}</groupId>
              <artifactId>${packageId.name}</artifactId>
              <versioning><versions><version>$version</version></versions></versioning>
            </metadata>
            """.trimIndent().encodeToByteArray(),
        )
        Files.write(
            versionDirectory.resolve("${packageId.name}-$version.mcfpkg"),
            CanonicalJson.encodeManifest(manifest),
        )
        Files.write(versionDirectory.resolve("${packageId.name}-$version-datapack.zip"), payload)
    }

    private fun levelDat(): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gzip ->
            DataOutputStream(gzip).use { output ->
                output.writeByte(10)
                output.writeUTF("")
                output.writeByte(10)
                output.writeUTF("Data")
                output.writeByte(3)
                output.writeUTF("DataVersion")
                output.writeInt(3955)
                output.writeByte(10)
                output.writeUTF("DataPacks")
                output.writeByte(9)
                output.writeUTF("Enabled")
                output.writeByte(8)
                output.writeInt(1)
                output.writeUTF("vanilla")
                output.writeByte(9)
                output.writeUTF("Disabled")
                output.writeByte(8)
                output.writeInt(0)
                output.writeByte(0)
                output.writeByte(0)
                output.writeByte(0)
            }
        }
        return bytes.toByteArray()
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)
}
