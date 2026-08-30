package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.ArtifactSource
import moe.afox.mcfpm.model.Dependency
import moe.afox.mcfpm.model.FeatureDefinition
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.PayloadRequirement
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.ToolConfiguration
import moe.afox.mcfpm.model.VersionRequirement
import java.nio.charset.StandardCharsets
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable

public object PackageManifestCodec {
    public fun decode(bytes: ByteArray): PackageManifest =
        decode(bytes.toString(StandardCharsets.UTF_8))

    public fun decode(text: String): PackageManifest {
        val document = Toml.parse(text)
        checkParseErrors(document)
        requireOnlyKeys(document, setOf("schema", "package", "dependencies", "features", "artifacts", "tool"), "document")

        val schema = document.requireLong("schema").toInt()
        val packageTable = document.requireTable("package")
        requireOnlyKeys(
            packageTable,
            setOf(
                "id", "version", "license", "minecraft", "compiler", "signature-fingerprint",
                "signature-algorithm", "signing-public-key", "signature",
            ),
            "package",
        )

        val dependencies = document.getTable("dependencies")
            ?.keySet()
            .orEmpty()
            .sorted()
            .map { id ->
                parseDependency(
                    PackageId.parse(id),
                    document.requireTable("dependencies").get(quote(id)),
                )
            }

        val features = document.getTable("features")
            ?.keySet()
            .orEmpty()
            .sorted()
            .map { name ->
                val feature = document.requireTable("features").requireTable(quote(name))
                requireOnlyKeys(feature, setOf("dependencies"), "features.$name")
                FeatureDefinition(
                    name = name,
                    enablesDependencies = feature.getArray("dependencies")
                        ?.strings()
                        .orEmpty()
                        .map(PackageId::parse),
                )
            }

        val artifacts = document.getArray("artifacts")
            ?.tables()
            .orEmpty()
            .mapIndexed(::parseArtifact)

        val tool = document.getTable("tool")?.let(::parseTool) ?: ToolConfiguration()

        return PackageManifest(
            schema = schema,
            packageId = PackageId.parse(packageTable.requireString("id")),
            version = SemVer.parse(packageTable.requireString("version")),
            license = packageTable.requireString("license"),
            minecraft = packageTable.getString("minecraft"),
            compiler = packageTable.getString("compiler"),
            dependencies = dependencies,
            features = features,
            artifacts = artifacts,
            tool = tool,
            signatureFingerprint = packageTable.getString("signature-fingerprint"),
            signatureAlgorithm = packageTable.getString("signature-algorithm"),
            signingPublicKey = packageTable.getString("signing-public-key"),
            signature = packageTable.getString("signature"),
        ).normalized()
    }

    public fun encode(manifest: PackageManifest): ByteArray {
        val normalized = manifest.normalized()
        val output = StringBuilder()
        output.appendLine("schema = ${normalized.schema}")
        output.appendLine()
        output.appendLine("[package]")
        output.appendLine("id = ${quote(normalized.packageId.value)}")
        output.appendLine("version = ${quote(normalized.version.toString())}")
        output.appendLine("license = ${quote(normalized.license)}")
        normalized.minecraft?.let { output.appendLine("minecraft = ${quote(it)}") }
        normalized.compiler?.let { output.appendLine("compiler = ${quote(it)}") }
        normalized.signatureFingerprint?.let { output.appendLine("signature-fingerprint = ${quote(it)}") }
        normalized.signatureAlgorithm?.let { output.appendLine("signature-algorithm = ${quote(it)}") }
        normalized.signingPublicKey?.let { output.appendLine("signing-public-key = ${quote(it)}") }
        normalized.signature?.let { output.appendLine("signature = ${quote(it)}") }

        if (normalized.tool != ToolConfiguration()) {
            output.appendLine()
            output.appendLine("[tool]")
            output.appendLine("consumer-profile = ${quote(consumerProfile(normalized.tool.consumerProfile))}")
            output.appendLine("default-repository = ${quote(normalized.tool.defaultRepository)}")
            appendStringTable(output, "tool.repositories", normalized.tool.repositories)
            appendStringTable(output, "tool.bindings", normalized.tool.bindings)
            appendStringTable(output, "tool.options", normalized.tool.options)
        }

        if (normalized.dependencies.isNotEmpty()) {
            output.appendLine()
            output.appendLine("[dependencies]")
            normalized.dependencies.forEach { dependency ->
                output.append(quote(dependency.packageId.value))
                output.append(" = { version = ")
                output.append(quote(dependency.requirement.expression))
                if (dependency.features.isNotEmpty()) {
                    output.append(", features = ")
                    output.append(stringArray(dependency.features))
                }
                if (dependency.optional) output.append(", optional = true")
                output.appendLine(" }")
            }
        }

        normalized.features.forEach { feature ->
            output.appendLine()
            output.appendLine("[features.${quotedKey(feature.name)}]")
            output.appendLine("dependencies = ${stringArray(feature.enablesDependencies.map(PackageId::value))}")
        }

        normalized.artifacts.forEach { artifact ->
            output.appendLine()
            output.appendLine("[[artifacts]]")
            output.appendLine("type = ${quote(artifact.type.value)}")
            output.appendLine("classifier = ${quote(artifact.classifier)}")
            output.appendLine("extension = ${quote(artifact.extension)}")
            output.appendLine("sha256 = ${quote(artifact.sha256)}")
            output.appendLine("size = ${artifact.size}")
            artifact.minecraft?.let { output.appendLine("minecraft = ${quote(it)}") }
            artifact.compiler?.let { output.appendLine("compiler = ${quote(it)}") }
            if (artifact.executable) output.appendLine("executable = true")
            if (artifact.requires.isNotEmpty()) {
                output.appendLine(
                    "requires = ${stringArray(artifact.requires.map { "${it.type.value}:${it.classifier}" })}",
                )
            }
            artifact.source?.let { source ->
                output.appendLine("source-kind = ${quote(source.kind)}")
                output.appendLine("source-uri = ${quote(source.uri)}")
                output.appendLine("source-version = ${quote(source.immutableVersion)}")
                output.appendLine("source-license = ${quote(source.redistributionLicense)}")
                source.upstreamId?.let { output.appendLine("source-upstream-id = ${quote(it)}") }
                source.revision?.let { output.appendLine("source-revision = ${quote(it)}") }
                source.path?.let { output.appendLine("source-path = ${quote(it)}") }
                source.sha256?.let { output.appendLine("source-sha256 = ${quote(it)}") }
                source.size?.let { output.appendLine("source-size = $it") }
            }
        }

        return output.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun parseTool(table: TomlTable): ToolConfiguration {
        requireOnlyKeys(table, setOf("consumer-profile", "default-repository", "repositories", "bindings", "options"), "tool")
        return ToolConfiguration(
            consumerProfile = parseConsumerProfile(table.getString("consumer-profile") ?: "all"),
            defaultRepository = table.getString("default-repository") ?: "central",
            repositories = table.getTable("repositories")?.stringMap().orEmpty(),
            bindings = table.getTable("bindings")?.stringMap().orEmpty(),
            options = table.getTable("options")?.stringMap().orEmpty(),
        )
    }

    private fun parseConsumerProfile(value: String): moe.afox.mcfpm.model.ConsumerProfile = when (value) {
        "minecraft.datapack" -> moe.afox.mcfpm.model.ConsumerProfile.MINECRAFT_DATAPACK
        "minecraft.resourcepack" -> moe.afox.mcfpm.model.ConsumerProfile.MINECRAFT_RESOURCEPACK
        "compiler.mcfpp" -> moe.afox.mcfpm.model.ConsumerProfile.MCFPP
        "jvm.plugin" -> moe.afox.mcfpm.model.ConsumerProfile.JVM_PLUGIN
        "all" -> moe.afox.mcfpm.model.ConsumerProfile.ALL
        else -> throw IllegalArgumentException("Unknown tool consumer profile: $value")
    }

    private fun consumerProfile(value: moe.afox.mcfpm.model.ConsumerProfile): String = when (value) {
        moe.afox.mcfpm.model.ConsumerProfile.MINECRAFT_DATAPACK -> "minecraft.datapack"
        moe.afox.mcfpm.model.ConsumerProfile.MINECRAFT_RESOURCEPACK -> "minecraft.resourcepack"
        moe.afox.mcfpm.model.ConsumerProfile.MCFPP -> "compiler.mcfpp"
        moe.afox.mcfpm.model.ConsumerProfile.JVM_PLUGIN -> "jvm.plugin"
        moe.afox.mcfpm.model.ConsumerProfile.ALL -> "all"
    }

    private fun appendStringTable(output: StringBuilder, name: String, values: Map<String, String>) {
        if (values.isEmpty()) return
        output.appendLine()
        output.appendLine("[$name]")
        values.toSortedMap().forEach { (key, value) -> output.appendLine("${quote(key)} = ${quote(value)}") }
    }

    private fun TomlTable.stringMap(): Map<String, String> = keySet().sorted().associateWith { key ->
        getString(quote(key)) ?: throw IllegalArgumentException("Tool configuration $key must be a string")
    }

    private fun parseDependency(packageId: PackageId, value: Any?): Dependency = when (value) {
        is String -> Dependency(packageId, VersionRequirement.parse(value))
        is TomlTable -> {
            requireOnlyKeys(value, setOf("version", "features", "optional"), "dependency $packageId")
            Dependency(
                packageId = packageId,
                requirement = VersionRequirement.parse(value.requireString("version")),
                features = value.getArray("features")?.strings().orEmpty(),
                optional = value.getBoolean("optional") ?: false,
            )
        }
        else -> throw IllegalArgumentException("Dependency $packageId must be a version string or inline table")
    }

    private fun parseArtifact(index: Int, table: TomlTable): ArtifactDescriptor {
        val allowed = setOf(
            "type", "classifier", "extension", "sha256", "size", "minecraft", "compiler", "executable",
            "requires", "source-kind", "source-uri", "source-version", "source-license",
            "source-upstream-id", "source-revision", "source-path", "source-sha256", "source-size",
        )
        requireOnlyKeys(table, allowed, "artifacts[$index]")
        val sourceFields = listOf("source-kind", "source-uri", "source-version", "source-license")
        val optionalSourceFields = listOf("source-upstream-id", "source-revision", "source-path", "source-sha256", "source-size")
        val presentSourceFields = sourceFields.count(table::contains)
        require(presentSourceFields == 0 || presentSourceFields == sourceFields.size) {
            "artifacts[$index] must declare all source provenance fields together"
        }
        require(optionalSourceFields.none(table::contains) || presentSourceFields == sourceFields.size) {
            "artifacts[$index] cannot declare extended provenance without the required source fields"
        }
        return ArtifactDescriptor(
            type = PayloadType.parse(table.requireString("type")),
            classifier = table.requireString("classifier"),
            extension = table.getString("extension") ?: "zip",
            sha256 = table.requireString("sha256"),
            size = table.requireLong("size"),
            minecraft = table.getString("minecraft"),
            compiler = table.getString("compiler"),
            executable = table.getBoolean("executable") ?: false,
            requires = table.getArray("requires")
                ?.strings()
                .orEmpty()
                .map { requirement ->
                    val separator = requirement.lastIndexOf(':')
                    require(separator > 0 && separator < requirement.lastIndex) {
                        "Invalid payload requirement: $requirement"
                    }
                    PayloadRequirement(
                        PayloadType.parse(requirement.substring(0, separator)),
                        requirement.substring(separator + 1),
                    )
                },
            source = if (presentSourceFields == 0) {
                null
            } else {
                ArtifactSource(
                    kind = table.requireString("source-kind"),
                    uri = table.requireString("source-uri"),
                    immutableVersion = table.requireString("source-version"),
                    redistributionLicense = table.requireString("source-license"),
                    upstreamId = table.getString("source-upstream-id"),
                    revision = table.getString("source-revision"),
                    path = table.getString("source-path"),
                    sha256 = table.getString("source-sha256"),
                    size = table.getLong("source-size"),
                )
            },
        )
    }

    internal fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    require(character.code >= 0x20 && character != '\u007f') {
                        "Control characters are not valid in canonical TOML strings"
                    }
                    append(character)
                }
            }
        }
        append('"')
    }

    internal fun stringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ", ", transform = ::quote)

    private fun quotedKey(value: String): String = quote(value)

    private fun checkParseErrors(document: TomlParseResult) {
        require(!document.hasErrors()) {
            document.errors().joinToString("; ") { error -> error.toString() }
        }
    }

    private fun requireOnlyKeys(table: TomlTable, allowed: Set<String>, context: String) {
        val unknown = table.keySet() - allowed
        require(unknown.isEmpty()) { "Unknown keys in $context: ${unknown.sorted().joinToString()}" }
    }

    private fun TomlTable.requireString(key: String): String =
        getString(key) ?: throw IllegalArgumentException("Missing or non-string TOML key: $key")

    private fun TomlTable.requireLong(key: String): Long =
        getLong(key) ?: throw IllegalArgumentException("Missing or non-integer TOML key: $key")

    private fun TomlTable.requireTable(key: String): TomlTable =
        getTable(key) ?: throw IllegalArgumentException("Missing or non-table TOML key: $key")

    private fun TomlArray.strings(): List<String> =
        (0 until size()).map { index ->
            getString(index) ?: throw IllegalArgumentException("TOML array item $index must be a string")
        }

    private fun TomlArray.tables(): List<TomlTable> =
        (0 until size()).map { index ->
            getTable(index) ?: throw IllegalArgumentException("TOML array item $index must be a table")
        }
}
