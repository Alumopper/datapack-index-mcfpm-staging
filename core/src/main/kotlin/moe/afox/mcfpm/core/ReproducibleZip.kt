package moe.afox.mcfpm.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode

public data class ZipSafetyLimits(
    public val maxEntries: Int = 100_000,
    public val maxExpandedBytes: Long = 512L * 1024L * 1024L,
    public val maxEntryBytes: Long = 256L * 1024L * 1024L,
)

public data class VerifiedZip(
    public val entries: List<String>,
    public val expandedBytes: Long,
    public val packMetadata: JsonObject?,
)

public data class ZipContents(
    public val entries: List<Pair<String, ByteArray>>,
    public val expandedBytes: Long,
)

public object ReproducibleZip {
    private val fixedTimestamp: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0)

    public fun fromDirectory(root: Path): ByteArray {
        val realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) { "ZIP source is not a directory: $root" }
        val files = Files.walk(realRoot).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                .map { path ->
                    require(!Files.isSymbolicLink(path)) { "Symbolic links are not permitted in package ZIPs: $path" }
                    val name = realRoot.relativize(path).joinToString("/") { it.toString() }
                    validateEntryName(name)
                    name to Files.readAllBytes(path)
                }
                .sorted(compareBy(Pair<String, ByteArray>::first))
                .toList()
        }
        return fromEntries(files)
    }

    public fun fromEntries(entries: List<Pair<String, ByteArray>>): ByteArray {
        val normalized = entries
            .map { (name, bytes) -> validateEntryName(name) to bytes }
            .sortedBy(Pair<String, ByteArray>::first)
        require(normalized.map(Pair<String, ByteArray>::first).distinct().size == normalized.size) {
            "Duplicate ZIP entry"
        }

        val bytes = ByteArrayOutputStream()
        ZipArchiveOutputStream(bytes).use { output ->
            output.setEncoding("UTF-8")
            output.setUseLanguageEncodingFlag(true)
            output.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER)
            output.setUseZip64(Zip64Mode.Never)
            output.setMethod(ZipEntry.DEFLATED)
            output.setLevel(9)
            normalized.forEach { (name, content) ->
                val entry = ZipArchiveEntry(name)
                entry.setTimeLocal(fixedTimestamp)
                entry.setUnixMode(33_188)
                entry.size = content.size.toLong()
                entry.method = ZipEntry.DEFLATED
                output.putArchiveEntry(entry)
                output.write(content)
                output.closeArchiveEntry()
            }
            output.finish()
        }
        return bytes.toByteArray()
    }

    public fun verify(
        bytes: ByteArray,
        requirePackMetadata: Boolean = false,
        limits: ZipSafetyLimits = ZipSafetyLimits(),
    ): VerifiedZip {
        val contents = readEntries(bytes, limits)
        var packMetadata: JsonObject? = null
        contents.entries.firstOrNull { it.first == "pack.mcmeta" }?.let { packMetadata = parsePackMetadata(it.second) }
        if (requirePackMetadata) require(packMetadata != null) { "ZIP is missing pack.mcmeta" }
        return VerifiedZip(contents.entries.map(Pair<String, ByteArray>::first), contents.expandedBytes, packMetadata)
    }

    public fun readEntries(
        bytes: ByteArray,
        limits: ZipSafetyLimits = ZipSafetyLimits(),
    ): ZipContents {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        val names = linkedSetOf<String>()
        var expandedBytes = 0L
        ZipArchiveInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = validateEntryName(entry.name)
                require(names.add(name)) { "Duplicate ZIP entry: $name" }
                require(names.size <= limits.maxEntries) { "ZIP contains too many entries" }
                require(!entry.isUnixSymlink) { "ZIP symbolic links are not permitted: $name" }
                if (entry.isDirectory) continue
                val content = readLimited(input, limits.maxEntryBytes)
                expandedBytes += content.size
                require(expandedBytes <= limits.maxExpandedBytes) { "ZIP expands beyond the configured safety limit" }
                entries += name to content
            }
        }
        return ZipContents(entries, expandedBytes)
    }

    public fun normalize(
        bytes: ByteArray,
        requirePackMetadata: Boolean = false,
        limits: ZipSafetyLimits = ZipSafetyLimits(),
    ): ByteArray {
        val contents = readEntries(bytes, limits)
        val metadata = contents.entries.firstOrNull { it.first == "pack.mcmeta" }
        metadata?.let { parsePackMetadata(it.second) }
        if (requirePackMetadata) require(metadata != null) { "ZIP is missing pack.mcmeta" }
        return fromEntries(contents.entries)
    }

    public fun validateEntryName(name: String): String {
        require(name.isNotBlank()) { "ZIP entry name must not be blank" }
        require('\\' !in name) { "ZIP entry names must use forward slashes: $name" }
        require(!name.startsWith('/')) { "Absolute ZIP entry path is not permitted: $name" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(name)) { "Drive-qualified ZIP entry path is not permitted: $name" }
        val segments = name.removeSuffix("/").split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "Unsafe ZIP entry path: $name"
        }
        return name
    }

    private fun readLimited(input: ZipArchiveInputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes) { "ZIP entry expands beyond the configured safety limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun parsePackMetadata(bytes: ByteArray): JsonObject {
        val root = runCatching {
            moe.afox.mcfpm.model.CanonicalJson.format.parseToJsonElement(bytes.decodeToString()).jsonObject
        }.getOrElse { cause -> throw IllegalArgumentException("pack.mcmeta is not valid JSON", cause) }
        val pack = root["pack"]?.jsonObject
            ?: throw IllegalArgumentException("pack.mcmeta is missing the pack object")
        val modernMinimum = "min_format" in pack
        val modernMaximum = "max_format" in pack
        require(modernMinimum == modernMaximum) {
            "pack.mcmeta must declare min_format and max_format together"
        }
        require("pack_format" in pack || "supported_formats" in pack || modernMinimum) {
            "pack.mcmeta must declare a legacy pack format or the min_format/max_format pair"
        }
        return root
    }
}
