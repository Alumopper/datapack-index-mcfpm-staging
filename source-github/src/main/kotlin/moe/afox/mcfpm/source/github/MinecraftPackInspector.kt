package moe.afox.mcfpm.source.github

import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.core.ZipSafetyLimits
import moe.afox.mcfpm.model.PayloadType

public class MinecraftPackInspector(
    private val limits: ZipSafetyLimits = ZipSafetyLimits(),
) {
    public fun inspect(
        archive: ByteArray,
        subdirectory: String? = null,
        nestedZip: String? = null,
        stripSingleRootDirectory: Boolean = false,
    ): PackInspectionCandidate {
        val outerEntries = ReproducibleZip.readEntries(archive, limits).entries
        val entries = if (stripSingleRootDirectory) stripWrapper(outerEntries) else outerEntries
        val candidates = mutableListOf<PackInspectionCandidate>()
        candidates += discover(entries, null)
        entries.asSequence()
            .filter { (name, _) -> name.lowercase().endsWith(".zip") && isBuildOutput(name) }
            .sortedBy(Pair<String, ByteArray>::first)
            .forEach { (name, bytes) ->
                val nestedEntries = ReproducibleZip.readEntries(bytes, limits).entries
                candidates += discover(nestedEntries, name)
            }

        val normalizedSubdirectory = subdirectory?.normalizeSelector()
        val normalizedNestedZip = nestedZip?.normalizeSelector()
        val selected = candidates.filter { candidate ->
            (normalizedSubdirectory == null || candidate.rootPath == normalizedSubdirectory) &&
                (normalizedNestedZip == null || candidate.nestedZip == normalizedNestedZip)
        }
        require(selected.size == 1) {
            val available = candidates.map(PackInspectionCandidate::displayPath).sorted()
            when {
                candidates.isEmpty() -> "No valid Minecraft datapack or resource pack was found"
                selected.isEmpty() -> "Pack selection did not match any candidate; available: ${available.joinToString()}"
                else -> "Pack selection is ambiguous; specify --subdir/--nested-zip exactly: ${available.joinToString()}"
            }
        }
        return selected.single()
    }

    private fun discover(
        entries: List<Pair<String, ByteArray>>,
        nestedZip: String?,
    ): List<PackInspectionCandidate> = entries.asSequence()
        .map(Pair<String, ByteArray>::first)
        .filter { it == "pack.mcmeta" || it.endsWith("/pack.mcmeta") }
        .map { metadata -> metadata.removeSuffix("pack.mcmeta").removeSuffix("/") }
        .distinct()
        .sorted()
        .map { root ->
            val prefix = root.takeIf(String::isNotEmpty)?.let { "$it/" }.orEmpty()
            val packEntries = entries.asSequence()
                .filter { (name, _) -> name.startsWith(prefix) }
                .map { (name, bytes) -> name.removePrefix(prefix) to bytes }
                .filter { it.first.isNotEmpty() }
                .toList()
            val hasData = packEntries.any { it.first.startsWith("data/") }
            val hasAssets = packEntries.any { it.first.startsWith("assets/") }
            require(hasData.xor(hasAssets)) {
                val label = listOfNotNull(nestedZip, root.ifEmpty { "/" }).joinToString("!/")
                if (hasData && hasAssets) {
                    "Minecraft pack candidate $label contains both data/ and assets/; select a single pack"
                } else {
                    "Minecraft pack candidate $label contains neither data/ nor assets/"
                }
            }
            val type = if (hasData) PayloadType.MINECRAFT_DATAPACK else PayloadType.MINECRAFT_RESOURCEPACK
            val payload = ReproducibleZip.fromEntries(packEntries)
            ReproducibleZip.verify(payload, requirePackMetadata = true, limits = limits)
            PackInspectionCandidate(
                rootPath = root,
                nestedZip = nestedZip,
                type = type,
                classifier = if (hasData) "datapack" else "resourcepack",
                payload = payload,
            )
        }
        .toList()

    private fun stripWrapper(entries: List<Pair<String, ByteArray>>): List<Pair<String, ByteArray>> {
        val roots = entries.map { it.first.substringBefore('/') }.distinct()
        require(roots.size == 1 && entries.all { '/' in it.first }) {
            "GitHub source archive does not have one repository wrapper directory"
        }
        val prefix = "${roots.single()}/"
        return entries.map { (name, bytes) -> name.removePrefix(prefix) to bytes }
    }

    private fun isBuildOutput(path: String): Boolean {
        val segments = path.lowercase().split('/')
        return segments.dropLast(1).any { it in BUILD_OUTPUT_DIRECTORIES }
    }

    private fun String.normalizeSelector(): String = trim().replace('\\', '/').trim('/').let { value ->
        require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe pack selector: $this" }
        value
    }

    private companion object {
        val BUILD_OUTPUT_DIRECTORIES: Set<String> = setOf("build", "dist", "out", "release", "releases")
    }
}
