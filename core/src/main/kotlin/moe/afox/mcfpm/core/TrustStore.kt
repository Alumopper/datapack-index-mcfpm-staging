package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.PackageId
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.tomlj.Toml

public data class TrustGrant(
    public val packageId: PackageId,
    public val fingerprint: String,
) {
    init {
        require(FINGERPRINT_PATTERN.matches(fingerprint)) { "Fingerprint must be a lowercase SHA-256 value" }
    }

    private companion object {
        val FINGERPRINT_PATTERN: Regex = Regex("[0-9a-f]{64}")
    }
}

public interface TrustStore {
    public fun isTrusted(packageId: PackageId, fingerprint: String): Boolean
    public fun grants(): Set<TrustGrant>
}

public class InMemoryTrustStore(
    grants: Collection<TrustGrant> = emptyList(),
) : TrustStore {
    private val grants: MutableSet<TrustGrant> = grants.toMutableSet()

    public fun add(grant: TrustGrant) {
        grants += grant
    }

    public fun remove(grant: TrustGrant) {
        grants -= grant
    }

    override fun isTrusted(packageId: PackageId, fingerprint: String): Boolean =
        TrustGrant(packageId, fingerprint) in grants

    override fun grants(): Set<TrustGrant> = grants.toSet()
}

public class FileTrustStore(
    private val path: Path,
) : TrustStore {
    private val delegate: InMemoryTrustStore = InMemoryTrustStore(read(path))

    public fun add(grant: TrustGrant) {
        delegate.add(grant)
        save()
    }

    public fun remove(grant: TrustGrant) {
        delegate.remove(grant)
        save()
    }

    override fun isTrusted(packageId: PackageId, fingerprint: String): Boolean =
        delegate.isTrusted(packageId, fingerprint)

    override fun grants(): Set<TrustGrant> = delegate.grants()

    private fun save() {
        val output = buildString {
            appendLine("version = 1")
            delegate.grants().sortedWith(compareBy({ it.packageId }, { it.fingerprint })).forEach { grant ->
                appendLine()
                appendLine("[[trust]]")
                appendLine("package = ${PackageManifestCodec.quote(grant.packageId.value)}")
                appendLine("fingerprint = ${PackageManifestCodec.quote(grant.fingerprint)}")
            }
        }.toByteArray(StandardCharsets.UTF_8)
        val parent = path.toAbsolutePath().normalize().parent
        Files.createDirectories(parent)
        val temporary = parent.resolve("${path.fileName}.${UUID.randomUUID()}.part")
        try {
            Files.write(temporary, output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        fun read(path: Path): List<TrustGrant> {
            if (!Files.isRegularFile(path)) return emptyList()
            val document = Toml.parse(path)
            require(!document.hasErrors()) { document.errors().joinToString("; ") }
            require(document.getLong("version") == 1L) { "Unsupported trust store version" }
            val entries = document.getArray("trust") ?: return emptyList()
            return (0 until entries.size()).map { index ->
                val table = entries.getTable(index)
                    ?: throw IllegalArgumentException("Trust entry $index must be a table")
                TrustGrant(
                    PackageId.parse(table.getString("package") ?: error("Trust package is required")),
                    table.getString("fingerprint") ?: error("Trust fingerprint is required"),
                )
            }
        }
    }
}
