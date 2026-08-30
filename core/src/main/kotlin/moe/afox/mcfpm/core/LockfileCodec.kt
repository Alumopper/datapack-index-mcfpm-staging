package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.ArtifactSource
import moe.afox.mcfpm.model.Dependency
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadRef
import moe.afox.mcfpm.model.PayloadRequirement
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.ResolvedEdge
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.model.ResolvedPackage
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.VersionRequirement
import java.nio.charset.StandardCharsets
import java.net.URI
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

public object LockfileCodec {
    public const val LOCK_VERSION: Int = 1

    public fun encode(graph: ResolvedGraph): ByteArray {
        val normalized = graph.normalized()
        require(normalized.diagnostics.none { it.severity.name == "ERROR" }) {
            "A lockfile cannot contain a failed resolution"
        }
        val output = StringBuilder()
        output.appendLine("lock-version = $LOCK_VERSION")
        output.appendLine("resolver-version = ${PackageManifestCodec.quote(normalized.resolverVersion)}")
        output.appendLine("roots = ${PackageManifestCodec.stringArray(normalized.roots.map(PackageId::value))}")
        normalized.minecraftVersion?.let {
            output.appendLine("minecraft-version = ${PackageManifestCodec.quote(it)}")
        }

        normalized.packages.forEach { lockedPackage ->
            output.appendLine()
            output.appendLine("[[packages]]")
            output.appendLine("id = ${PackageManifestCodec.quote(lockedPackage.packageId.value)}")
            output.appendLine("version = ${PackageManifestCodec.quote(lockedPackage.version.toString())}")
            output.appendLine("repository = ${PackageManifestCodec.quote(lockedPackage.repositoryUrl)}")
            output.appendLine("descriptor-sha256 = ${PackageManifestCodec.quote(lockedPackage.descriptorSha256)}")
            output.appendLine("features = ${PackageManifestCodec.stringArray(lockedPackage.selectedFeatures)}")
            lockedPackage.signatureFingerprint?.let {
                output.appendLine("signature-fingerprint = ${PackageManifestCodec.quote(it)}")
            }
            output.appendLine("artifacts = [")
            lockedPackage.artifacts.forEach { artifact ->
                output.append("  ")
                output.append(encodeArtifact(artifact))
                output.appendLine(',')
            }
            output.appendLine("]")
        }

        normalized.edges.forEach { edge ->
            output.appendLine()
            output.appendLine("[[edges]]")
            output.appendLine("from = ${PackageManifestCodec.quote(edge.from.value)}")
            output.appendLine("to = ${PackageManifestCodec.quote(edge.to.value)}")
            output.appendLine("requirement = ${PackageManifestCodec.quote(edge.requirement.expression)}")
        }

        normalized.loadOrder.forEach { payload ->
            output.appendLine()
            output.appendLine("[[load-order]]")
            output.appendLine("package = ${PackageManifestCodec.quote(payload.packageId.value)}")
            output.appendLine("type = ${PackageManifestCodec.quote(payload.type.value)}")
            output.appendLine("classifier = ${PackageManifestCodec.quote(payload.classifier)}")
        }
        normalized.diagnostics.forEach { diagnostic ->
            output.appendLine()
            output.appendLine("[[diagnostics]]")
            output.appendLine("code = ${PackageManifestCodec.quote(diagnostic.code.stableCode)}")
            output.appendLine("severity = ${PackageManifestCodec.quote(diagnostic.severity.name.lowercase())}")
            output.appendLine("message = ${PackageManifestCodec.quote(diagnostic.message)}")
            val context = diagnostic.context.toSortedMap().entries.joinToString(
                prefix = "{ ",
                postfix = " }",
                separator = ", ",
            ) { (key, value) -> "${PackageManifestCodec.quote(key)} = ${PackageManifestCodec.quote(value)}" }
            output.appendLine("context = $context")
        }
        return output.toString().toByteArray(StandardCharsets.UTF_8)
    }

    public fun decode(bytes: ByteArray): ResolvedGraph {
        val document = Toml.parse(bytes.toString(StandardCharsets.UTF_8))
        require(!document.hasErrors()) { document.errors().joinToString("; ") }
        require(document.getLong("lock-version") == LOCK_VERSION.toLong()) {
            "Unsupported lockfile version: ${document.getLong("lock-version")}"
        }
        requireOnlyKeys(
            document,
            setOf("lock-version", "resolver-version", "roots", "minecraft-version", "packages", "edges", "load-order", "diagnostics"),
            "lockfile",
        )
        return ResolvedGraph(
            resolverVersion = document.getString("resolver-version")
                ?: throw IllegalArgumentException("Lockfile resolver-version is required"),
            roots = document.requireArray("roots").strings().map(PackageId::parse),
            packages = document.getArray("packages")?.tables().orEmpty().map(::decodePackage),
            edges = document.getArray("edges")?.tables().orEmpty().map { table ->
                requireOnlyKeys(table, setOf("from", "to", "requirement"), "edge")
                ResolvedEdge(
                    from = PackageId.parse(table.requireString("from")),
                    to = PackageId.parse(table.requireString("to")),
                    requirement = VersionRequirement.parse(table.requireString("requirement")),
                )
            },
            loadOrder = document.getArray("load-order")?.tables().orEmpty().map { table ->
                requireOnlyKeys(table, setOf("package", "type", "classifier"), "load-order")
                PayloadRef(
                    packageId = PackageId.parse(table.requireString("package")),
                    type = PayloadType.parse(table.requireString("type")),
                    classifier = table.requireString("classifier"),
                )
            },
            diagnostics = document.getArray("diagnostics")?.tables().orEmpty().map { table ->
                requireOnlyKeys(table, setOf("code", "severity", "message", "context"), "diagnostic")
                val stableCode = table.requireString("code")
                val contextTable: TomlTable? = table.getTable("context")
                val context: Map<String, String> = if (contextTable == null) {
                    emptyMap()
                } else {
                    contextTable.keySet().sorted().associateWith { key ->
                        contextTable.getString(PackageManifestCodec.quote(key))
                            ?: throw IllegalArgumentException("Diagnostic context $key must be a string")
                    }
                }
                Diagnostic(
                    code = DiagnosticCode.entries.singleOrNull { it.stableCode == stableCode }
                        ?: throw IllegalArgumentException("Unknown diagnostic code in lockfile: $stableCode"),
                    severity = runCatching { DiagnosticSeverity.valueOf(table.requireString("severity").uppercase()) }
                        .getOrElse { throw IllegalArgumentException("Unknown diagnostic severity") },
                    message = table.requireString("message"),
                    context = context,
                )
            },
            minecraftVersion = document.getString("minecraft-version"),
        ).normalized().also(::validateGraph)
    }

    private fun encodeArtifact(artifact: ArtifactDescriptor): String {
        val fields = mutableListOf(
            "type = ${PackageManifestCodec.quote(artifact.type.value)}",
            "classifier = ${PackageManifestCodec.quote(artifact.classifier)}",
            "extension = ${PackageManifestCodec.quote(artifact.extension)}",
            "sha256 = ${PackageManifestCodec.quote(artifact.sha256)}",
            "size = ${artifact.size}",
            "executable = ${artifact.executable}",
        )
        artifact.minecraft?.let { fields += "minecraft = ${PackageManifestCodec.quote(it)}" }
        artifact.compiler?.let { fields += "compiler = ${PackageManifestCodec.quote(it)}" }
        if (artifact.requires.isNotEmpty()) {
            fields += "requires = ${PackageManifestCodec.stringArray(artifact.requires.map { "${it.type.value}:${it.classifier}" })}"
        }
        artifact.source?.let { source ->
            fields += "source-kind = ${PackageManifestCodec.quote(source.kind)}"
            fields += "source-uri = ${PackageManifestCodec.quote(source.uri)}"
            fields += "source-version = ${PackageManifestCodec.quote(source.immutableVersion)}"
            fields += "source-license = ${PackageManifestCodec.quote(source.redistributionLicense)}"
        }
        return fields.joinToString(prefix = "{ ", postfix = " }", separator = ", ")
    }

    private fun decodePackage(table: TomlTable): ResolvedPackage {
        requireOnlyKeys(
            table,
            setOf("id", "version", "repository", "descriptor-sha256", "features", "signature-fingerprint", "artifacts"),
            "package",
        )
        return ResolvedPackage(
            packageId = PackageId.parse(table.requireString("id")),
            version = SemVer.parse(table.requireString("version")),
            repositoryUrl = table.requireString("repository"),
            descriptorSha256 = table.requireString("descriptor-sha256"),
            selectedFeatures = table.requireArray("features").strings(),
            signatureFingerprint = table.getString("signature-fingerprint"),
            artifacts = table.requireArray("artifacts").tables().map(::decodeArtifact),
        )
    }

    private fun decodeArtifact(table: TomlTable): ArtifactDescriptor {
        requireOnlyKeys(
            table,
            setOf(
                "type", "classifier", "extension", "sha256", "size", "executable", "minecraft", "compiler",
                "requires", "source-kind", "source-uri", "source-version", "source-license",
            ),
            "artifact",
        )
        val sourceFields = listOf("source-kind", "source-uri", "source-version", "source-license")
        val sourceFieldCount = sourceFields.count(table::contains)
        require(sourceFieldCount == 0 || sourceFieldCount == sourceFields.size) {
            "Lockfile artifact must declare all source provenance fields together"
        }
        val sourceKind: String? = table.getString("source-kind")
        val source: ArtifactSource? = sourceKind?.let {
            ArtifactSource(
                kind = it,
                uri = table.requireString("source-uri"),
                immutableVersion = table.requireString("source-version"),
                redistributionLicense = table.requireString("source-license"),
            )
        }
        return ArtifactDescriptor(
            type = PayloadType.parse(table.requireString("type")),
            classifier = table.requireString("classifier"),
            extension = table.requireString("extension"),
            sha256 = table.requireString("sha256"),
            size = table.requireLong("size"),
            minecraft = table.getString("minecraft"),
            compiler = table.getString("compiler"),
            executable = table.getBoolean("executable") ?: false,
            requires = table.getArray("requires")?.strings().orEmpty().map { encoded ->
                val separator = encoded.lastIndexOf(':')
                PayloadRequirement(PayloadType.parse(encoded.substring(0, separator)), encoded.substring(separator + 1))
            },
            source = source,
        )
    }

    private fun TomlTable.requireArray(key: String): TomlArray =
        getArray(key) ?: throw IllegalArgumentException("Missing or non-array lockfile key: $key")

    private fun TomlTable.requireString(key: String): String =
        getString(key) ?: throw IllegalArgumentException("Missing or non-string lockfile key: $key")

    private fun TomlTable.requireLong(key: String): Long =
        getLong(key) ?: throw IllegalArgumentException("Missing or non-integer lockfile key: $key")

    private fun TomlArray.strings(): List<String> =
        (0 until size()).map { index ->
            getString(index) ?: throw IllegalArgumentException("Lockfile array item $index must be a string")
        }

    private fun TomlArray.tables(): List<TomlTable> =
        (0 until size()).map { index ->
            getTable(index) ?: throw IllegalArgumentException("Lockfile array item $index must be a table")
        }

    private fun requireOnlyKeys(table: TomlTable, allowed: Set<String>, label: String) {
        val unknown = table.keySet() - allowed
        require(unknown.isEmpty()) { "Unknown $label key(s): ${unknown.sorted().joinToString()}" }
    }

    private fun validateGraph(graph: ResolvedGraph) {
        require(graph.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }) {
            "A lockfile cannot contain error diagnostics"
        }
        val packages = graph.packages.associateBy { it.packageId }
        require(packages.size == graph.packages.size) { "Lockfile package IDs must be unique" }
        require(graph.roots.distinct().size == graph.roots.size && graph.roots.all(packages::containsKey)) {
            "Lockfile roots must be unique and reference locked packages"
        }
        graph.packages.forEach { locked ->
            require(SHA256.matches(locked.descriptorSha256)) { "Invalid descriptor SHA-256 for ${locked.packageId}" }
            val fingerprint = locked.signatureFingerprint
            require(fingerprint == null || SHA256.matches(fingerprint)) {
                "Invalid signature fingerprint for ${locked.packageId}"
            }
            require(URI.create(locked.repositoryUrl).scheme in setOf("file", "http", "https")) {
                "Invalid repository URL for ${locked.packageId}"
            }
        }
        require(graph.edges.all { it.from in packages && it.to in packages }) {
            "Lockfile dependency edges must reference locked packages"
        }
        require(graph.edges.distinct().size == graph.edges.size) { "Lockfile dependency edges must be unique" }
        val artifacts = graph.packages.flatMap { locked ->
            locked.artifacts.map { artifact -> PayloadRef(locked.packageId, artifact.type, artifact.classifier) }
        }.toSet()
        require(graph.loadOrder.distinct().size == graph.loadOrder.size && graph.loadOrder.toSet() == artifacts) {
            "Lockfile load order must contain every selected artifact exactly once"
        }
    }

    private val SHA256: Regex = Regex("[0-9a-f]{64}")
}
