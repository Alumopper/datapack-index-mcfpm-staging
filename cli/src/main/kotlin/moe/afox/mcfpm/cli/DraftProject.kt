package moe.afox.mcfpm.cli

import moe.afox.mcfpm.core.PackageManifestCodec
import moe.afox.mcfpm.model.Dependency
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.ToolConfiguration
import moe.afox.mcfpm.model.VersionRequirement
import java.nio.file.Files
import java.nio.file.Path

internal data class InstallProjectEdit(
    val projectRoot: Path,
    val manifestCreated: Boolean,
    val dependencies: List<PackageId>,
)

internal object DraftProject {
    val packageId: PackageId = PackageId.parse("local:unpublished")
    val version: SemVer = SemVer.parse("0.0.0-unpublished")
    const val license: String = "UNLICENSED"

    fun manifest(root: McfpmCommand): PackageManifest = PackageManifest(
        packageId = packageId,
        version = version,
        license = license,
        tool = ToolConfiguration(
            defaultRepository = root.defaultRepository ?: "afox",
            repositories = assignments(root.repositories, "repository"),
            bindings = assignments(root.bindings, "binding"),
        ),
    )

    fun applyToolOverrides(root: McfpmCommand, manifest: PackageManifest): PackageManifest = manifest.copy(
        tool = manifest.tool.copy(
            defaultRepository = root.defaultRepository ?: manifest.tool.defaultRepository,
            repositories = manifest.tool.repositories + assignments(root.repositories, "repository"),
            bindings = manifest.tool.bindings + assignments(root.bindings, "binding"),
        ),
    )

    fun isDraft(manifest: PackageManifest): Boolean =
        manifest.packageId == packageId || manifest.version == version || manifest.license == license

    fun requirePublicationMetadata(manifest: PackageManifest, operation: String) {
        val missing = buildList {
            if (manifest.packageId == packageId) add("package.id")
            if (manifest.version == version) add("package.version")
            if (manifest.license == license) add("package.license")
        }
        require(missing.isEmpty()) {
            "$operation requires publication metadata; run mcfpm init --id GROUP:NAME or replace: ${missing.joinToString()}"
        }
    }

    private fun assignments(declarations: List<String>, label: String): Map<String, String> =
        buildMap {
            declarations.forEach { declaration ->
                val separator = declaration.indexOf('=')
                require(separator > 0 && separator < declaration.lastIndex) {
                    "$label must use NAME=VALUE syntax"
                }
                put(declaration.substring(0, separator), declaration.substring(separator + 1))
            }
        }
}

internal fun prepareInstallProject(
    root: McfpmCommand,
    coordinates: List<String>,
    explicitProject: Path? = null,
): InstallProjectEdit? {
    if (coordinates.isEmpty()) return null
    require(!root.dryRun) {
        "Direct dependency installation cannot update mcfpm.toml with --dry-run; run without --dry-run or use mcfpm add"
    }
    val requested = coordinates.map(::parseDependencyCoordinate)
    require(requested.map(Dependency::packageId).distinct().size == requested.size) {
        "Each direct dependency may be specified only once"
    }
    val requestedProject = explicitProject?.let { path ->
        (if (path.isAbsolute) path else root.workingDirectory.resolve(path)).toAbsolutePath().normalize()
    }
    if (requestedProject != null) require(Files.isDirectory(requestedProject)) { "Project directory does not exist: $requestedProject" }
    val existingRoot = requestedProject?.takeIf { Files.isRegularFile(it.resolve("mcfpm.toml")) }
        ?: if (requestedProject == null) McfpmServices.findAncestor(root.workingDirectory, "mcfpm.toml") else null
    val projectRoot = (existingRoot ?: requestedProject ?: root.workingDirectory).toAbsolutePath().normalize()
    val manifestPath = projectRoot.resolve("mcfpm.toml")
    val manifestCreated = existingRoot == null
    val manifest = if (manifestCreated) {
        DraftProject.manifest(root)
    } else {
        DraftProject.applyToolOverrides(root, PackageManifestCodec.decode(Files.readAllBytes(manifestPath)))
    }
    val requestedIds = requested.map(Dependency::packageId).toSet()
    val dependencies = manifest.dependencies.filterNot { it.packageId in requestedIds } + requested
    atomicWrite(
        manifestPath,
        PackageManifestCodec.encode(manifest.copy(dependencies = dependencies).invalidateSignature().normalized()),
    )
    return InstallProjectEdit(projectRoot, manifestCreated, requested.map(Dependency::packageId).sorted())
}

internal fun parseDependencyCoordinate(coordinate: String): Dependency {
    val separator = coordinate.lastIndexOf('@')
    require(separator > 0 && separator < coordinate.lastIndex) {
        "Dependency must use GROUP:NAME@REQUIREMENT syntax"
    }
    return Dependency(
        PackageId.parse(coordinate.substring(0, separator)),
        VersionRequirement.parse(coordinate.substring(separator + 1)),
    )
}
