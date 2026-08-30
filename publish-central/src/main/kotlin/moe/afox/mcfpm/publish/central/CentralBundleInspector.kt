package moe.afox.mcfpm.publish.central

import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult

public object CentralBundleInspector {
    public fun validate(bytes: ByteArray): McfpmResult<Unit> = try {
        val verified = ReproducibleZip.verify(bytes)
        require(ReproducibleZip.normalize(bytes).contentEquals(bytes)) { "Central bundle ZIP is not canonical" }
        val files = verified.entries.toSet()
        val baseFiles = files.filterNot(::isSidecar)
        require(baseFiles.count { it.endsWith(".pom") } == 1) { "Central bundle requires exactly one POM" }
        require(baseFiles.count { it.endsWith(".mcfpkg") } == 1) { "Central bundle requires exactly one .mcfpkg descriptor" }
        require(baseFiles.size >= 3) { "Central bundle requires at least one payload artifact" }
        baseFiles.forEach { file ->
            REQUIRED_SUFFIXES.forEach { suffix -> require("$file.$suffix" in files) { "Missing $suffix sidecar for $file" } }
            val signature = "$file.asc"
            require(signature in files) { "Missing detached OpenPGP signature for $file" }
            REQUIRED_SUFFIXES.forEach { suffix ->
                require("$signature.$suffix" in files) { "Missing $suffix sidecar for $signature" }
            }
        }
        McfpmResult.Success(Unit)
    } catch (exception: Exception) {
        McfpmResult.Failure(
            listOf(
                Diagnostic(
                    DiagnosticCode.PUBLISH_VALIDATION_FAILED,
                    DiagnosticSeverity.ERROR,
                    "Invalid Central bundle: ${exception.message}",
                ),
            ),
        )
    }

    private fun isSidecar(name: String): Boolean =
        name.endsWith(".asc") || REQUIRED_SUFFIXES.any { suffix -> name.endsWith(".$suffix") }

    private val REQUIRED_SUFFIXES: List<String> = listOf("md5", "sha1", "sha256", "sha512")
}
