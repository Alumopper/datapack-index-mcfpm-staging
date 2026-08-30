package moe.afox.mcfpm.publish.central

import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.LockfileCodec
import moe.afox.mcfpm.core.ManifestSigner
import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageManifest
import java.security.MessageDigest

public data class CentralDeveloper(
    public val id: String,
    public val name: String,
    public val email: String? = null,
)

public data class CentralPomMetadata(
    public val displayName: String,
    public val description: String,
    public val projectUrl: String,
    public val scmUrl: String,
    public val licenseName: String,
    public val licenseUrl: String,
    public val developers: List<CentralDeveloper>,
)

public fun interface DetachedSignatureVerifier {
    public fun verify(content: ByteArray, armoredSignature: ByteArray): Boolean
}

public data class CentralBundleRequest(
    public val manifest: PackageManifest,
    public val lockfile: ByteArray,
    public val artifactsByClassifier: Map<String, ByteArray>,
    public val signaturesByFileName: Map<String, ByteArray>,
    public val pom: CentralPomMetadata,
)

public class CentralBundleBuilder(
    private val signatureVerifier: DetachedSignatureVerifier,
) {
    public fun build(request: CentralBundleRequest): McfpmResult<ByteArray> {
        val errors = validate(request)
        if (errors.isNotEmpty()) return McfpmResult.Failure(errors)
        val first = buildUnchecked(request)
        val second = buildUnchecked(request)
        return if (first.contentEquals(second)) {
            McfpmResult.Success(first)
        } else {
            McfpmResult.Failure(listOf(error("Central bundle is not reproducible")))
        }
    }

    private fun validate(request: CentralBundleRequest): List<Diagnostic> {
        val errors = mutableListOf<Diagnostic>()
        runCatching { LockfileCodec.decode(request.lockfile) }
            .onSuccess { graph ->
                val declared = request.manifest.dependencies.filterNot { it.optional }.map { it.packageId }.sorted()
                if (graph.roots.sorted() != declared) errors += error("Lockfile roots do not match the package dependencies")
            }
            .onFailure { errors += error("Lockfile validation failed: ${it.message}") }
        if (request.manifest.license.isBlank()) errors += error("Package license is required")
        if (!ManifestSigner.isSigned(request.manifest) || !ManifestSigner.verify(request.manifest)) {
            errors += error("Descriptor requires a valid Ed25519 signature")
        }
        if (request.manifest.artifacts.any { it.executable } && !ManifestSigner.isSigned(request.manifest)) {
            errors += error("Executable payloads require a signed package descriptor")
        }
        if (request.pom.developers.isEmpty()) errors += error("At least one developer is required by Maven Central")
        if (request.pom.displayName.isBlank() || request.pom.description.isBlank()) {
            errors += error("POM display name and description are required")
        }
        if (request.pom.projectUrl.isBlank() || request.pom.scmUrl.isBlank()) {
            errors += error("POM project and SCM URLs are required")
        }
        if (request.pom.developers.any { it.id.isBlank() || it.name.isBlank() }) {
            errors += error("Every POM developer requires a non-blank ID and name")
        }
        if (request.pom.licenseName.isBlank() || request.pom.licenseUrl.isBlank()) {
            errors += error("POM license name and URL are required")
        }
        val duplicateClassifiers = request.manifest.artifacts.groupBy { it.classifier }.filterValues { it.size > 1 }.keys
        if (duplicateClassifiers.isNotEmpty()) errors += error("Maven artifact classifiers must be unique")

        request.manifest.artifacts.forEach { artifact ->
            val bytes = request.artifactsByClassifier[artifact.classifier]
            when {
                bytes == null -> errors += error("Missing artifact bytes for classifier ${artifact.classifier}")
                bytes.size.toLong() != artifact.size || Hashing.sha256(bytes) != artifact.sha256 ->
                    errors += error("Artifact ${artifact.classifier} does not match its descriptor")
            }
        }
        val expectedClassifiers = request.manifest.artifacts.map { it.classifier }.toSet()
        val extraClassifiers = request.artifactsByClassifier.keys - expectedClassifiers
        if (extraClassifiers.isNotEmpty()) errors += error("Undeclared artifact classifiers: ${extraClassifiers.sorted().joinToString()}")

        val expectedSignatureFiles = runCatching {
            CentralPublicationLayout.files(request.manifest, request.artifactsByClassifier, request.pom).keys
        }.getOrDefault(emptySet())
        val extraSignatures = request.signaturesByFileName.keys - expectedSignatureFiles
        if (extraSignatures.isNotEmpty()) errors += error("Signatures for undeclared files: ${extraSignatures.sorted().joinToString()}")

        if (errors.isEmpty()) {
            CentralPublicationLayout.files(request.manifest, request.artifactsByClassifier, request.pom).forEach { (fileName, bytes) ->
                val signature = request.signaturesByFileName[fileName]
                if (signature == null || !signatureVerifier.verify(bytes, signature)) {
                    errors += error("Missing or invalid detached OpenPGP signature for $fileName")
                }
            }
        }
        return errors
    }

    private fun buildUnchecked(request: CentralBundleRequest): ByteArray {
        val manifest = request.manifest
        val basePath = "${manifest.packageId.group.replace('.', '/')}/${manifest.packageId.name}/${manifest.version}/"
        val entries = mutableListOf<Pair<String, ByteArray>>()
        CentralPublicationLayout.files(request.manifest, request.artifactsByClassifier, request.pom).forEach { (fileName, content) ->
            entries += "$basePath$fileName" to content
            val signature = request.signaturesByFileName.getValue(fileName)
            entries += "$basePath$fileName.asc" to signature
            checksumFiles(content).forEach { (suffix, checksum) ->
                entries += "$basePath$fileName.$suffix" to checksum
            }
            checksumFiles(signature).forEach { (suffix, checksum) ->
                entries += "$basePath$fileName.asc.$suffix" to checksum
            }
        }
        return ReproducibleZip.fromEntries(entries)
    }

    private fun checksumFiles(bytes: ByteArray): Map<String, ByteArray> = linkedMapOf(
        "md5" to digest("MD5", bytes).encodeToByteArray(),
        "sha1" to digest("SHA-1", bytes).encodeToByteArray(),
        "sha256" to digest("SHA-256", bytes).encodeToByteArray(),
        "sha512" to digest("SHA-512", bytes).encodeToByteArray(),
    )

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    private fun error(message: String): Diagnostic =
        Diagnostic(DiagnosticCode.PUBLISH_VALIDATION_FAILED, DiagnosticSeverity.ERROR, message)
}

public object CentralPublicationLayout {
    public fun files(
        manifest: PackageManifest,
        artifactsByClassifier: Map<String, ByteArray>,
        pom: CentralPomMetadata,
    ): Map<String, ByteArray> {
        val prefix = "${manifest.packageId.name}-${manifest.version}"
        val files = linkedMapOf(
            "$prefix.pom" to pomXml(manifest, pom).encodeToByteArray(),
            "$prefix.mcfpkg" to CanonicalJson.encodeManifest(manifest),
        )
        manifest.artifacts.sortedBy { it.classifier }.forEach { artifact ->
            files["$prefix-${artifact.classifier}.${artifact.extension}"] =
                artifactsByClassifier.getValue(artifact.classifier)
        }
        return files
    }

    public fun pomXml(manifest: PackageManifest, metadata: CentralPomMetadata): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">")
        appendLine("  <modelVersion>4.0.0</modelVersion>")
        appendLine("  <groupId>${xml(manifest.packageId.group)}</groupId>")
        appendLine("  <artifactId>${xml(manifest.packageId.name)}</artifactId>")
        appendLine("  <version>${xml(manifest.version.toString())}</version>")
        appendLine("  <packaging>mcfpkg</packaging>")
        appendLine("  <name>${xml(metadata.displayName)}</name>")
        appendLine("  <description>${xml(metadata.description)}</description>")
        appendLine("  <url>${xml(metadata.projectUrl)}</url>")
        appendLine("  <licenses><license><name>${xml(metadata.licenseName)}</name><url>${xml(metadata.licenseUrl)}</url><distribution>repo</distribution></license></licenses>")
        appendLine("  <developers>")
        metadata.developers.sortedBy(CentralDeveloper::id).forEach { developer ->
            append("    <developer><id>${xml(developer.id)}</id><name>${xml(developer.name)}</name>")
            developer.email?.let { append("<email>${xml(it)}</email>") }
            appendLine("</developer>")
        }
        appendLine("  </developers>")
        appendLine("  <scm><url>${xml(metadata.scmUrl)}</url><connection>scm:git:${xml(metadata.scmUrl)}</connection><developerConnection>scm:git:${xml(metadata.scmUrl)}</developerConnection></scm>")
        appendLine("</project>")
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

}
