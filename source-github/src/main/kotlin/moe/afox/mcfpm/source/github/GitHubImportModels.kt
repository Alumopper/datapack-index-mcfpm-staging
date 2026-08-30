package moe.afox.mcfpm.source.github

import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.VersionRequirement
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public data class GitHubRepository(
    public val owner: String,
    public val name: String,
) {
    init {
        require(PART.matches(owner) && PART.matches(name)) { "Invalid GitHub repository: $owner/$name" }
        require(owner !in DOT_SEGMENTS && name !in DOT_SEGMENTS) { "Invalid GitHub repository path: $owner/$name" }
    }

    public val slug: String = "$owner/$name"
    public val projectUri: URI = URI.create("https://github.com/$slug")

    public fun defaultPackageId(): PackageId = PackageId.parse(
        "io.github.${owner.lowercase()}:${name.lowercase()}",
    )

    public companion object {
        private val PART: Regex = Regex("[A-Za-z0-9_.-]+")
        private val DOT_SEGMENTS: Set<String> = setOf(".", "..")

        public fun parse(value: String): GitHubRepository {
            val withoutUrl = value.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removeSuffix("/")
                .removeSuffix(".git")
            val parts = withoutUrl.split('/')
            require(parts.size == 2) { "GitHub repository must use OWNER/REPOSITORY or a github.com URL" }
            return GitHubRepository(parts[0], parts[1])
        }
    }
}

@Serializable
public enum class GitHubSourceMode {
    @SerialName("release-asset")
    RELEASE_ASSET,

    @SerialName("archive")
    ARCHIVE,
}

public data class GitHubImportRequest(
    public val repository: GitHubRepository,
    public val reference: String,
    public val mode: GitHubSourceMode = GitHubSourceMode.RELEASE_ASSET,
    public val asset: String? = null,
    public val subdirectory: String? = null,
    public val nestedZip: String? = null,
    public val explicitLicense: String? = null,
    public val expectedSha256: String? = null,
    public val token: String? = null,
) {
    init {
        require(reference.isNotBlank()) { "A GitHub tag or commit reference is required" }
        require(mode == GitHubSourceMode.RELEASE_ASSET || asset == null) {
            "--asset is valid only for release assets"
        }
        require(expectedSha256 == null || Regex("[0-9a-fA-F]{64}").matches(expectedSha256)) {
            "Expected GitHub SHA-256 must be 64 hexadecimal characters"
        }
    }
}

public data class GitHubImportCandidate(
    public val repository: GitHubRepository,
    public val mode: GitHubSourceMode,
    public val reference: String,
    public val releaseId: Long?,
    public val assetId: Long?,
    public val assetName: String?,
    public val commit: String,
    public val sourceUri: URI,
    public val requestUri: URI,
    public val finalUri: URI,
    public val rawSha256: String,
    public val rawSize: Long,
    public val license: String,
    public val selectedPath: String,
    public val selectedRoot: String,
    public val selectedNestedZip: String?,
    public val payloadType: PayloadType,
    public val classifier: String,
    public val normalizedSha256: String,
    public val normalizedSize: Long,
    public val payload: ByteArray,
)

public data class PackInspectionCandidate(
    public val rootPath: String,
    public val nestedZip: String?,
    public val type: PayloadType,
    public val classifier: String,
    public val payload: ByteArray,
) {
    public val displayPath: String = if (nestedZip == null) {
        rootPath.ifEmpty { "/" }
    } else {
        "$nestedZip!/${rootPath}"
    }
}

public data class GitHubImportRecipe(
    public val schema: Int = 1,
    public val source: RecipeSource,
    public val packageConfiguration: RecipePackage = RecipePackage(),
    public val repository: RecipeRepository,
    public val dependencies: Map<PackageId, VersionRequirement> = emptyMap(),
) {
    init {
        require(schema == 1) { "Unsupported GitHub import recipe schema: $schema" }
    }
}

public data class RecipeSource(
    public val repository: GitHubRepository,
    public val mode: GitHubSourceMode = GitHubSourceMode.RELEASE_ASSET,
    public val asset: String? = null,
    public val subdirectory: String? = null,
    public val nestedZip: String? = null,
    public val githubTokenEnvironment: String = "GITHUB_TOKEN",
) {
    init {
        require(mode == GitHubSourceMode.RELEASE_ASSET || asset == null) {
            "Archive recipes cannot declare a release asset"
        }
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(githubTokenEnvironment)) {
            "Invalid GitHub token environment variable: $githubTokenEnvironment"
        }
    }
}

public data class RecipePackage(
    public val packageId: PackageId? = null,
    public val license: String? = null,
    public val type: PayloadType? = null,
    public val classifier: String? = null,
    public val minecraft: String? = null,
)

public data class RecipeRepository(
    public val id: String,
) {
    init {
        require(Regex("[a-z][a-z0-9._-]*").matches(id)) { "Invalid recipe repository ID: $id" }
    }
}

@Serializable
public data class GitHubImportLock(
    public val schema: Int = 1,
    public val source: LockedGitHubSource,
    public val packageCoordinate: LockedPackageCoordinate,
) {
    init {
        require(schema == 1) { "Unsupported GitHub import lock schema: $schema" }
    }
}

@Serializable
public data class LockedGitHubSource(
    public val repository: String,
    public val mode: GitHubSourceMode,
    public val reference: String,
    public val releaseId: Long? = null,
    public val assetId: Long? = null,
    public val assetName: String? = null,
    public val commit: String,
    public val sourceUri: String,
    public val rawSha256: String,
    public val rawSize: Long,
    public val selectedPath: String,
) {
    init {
        require(Regex("[0-9a-f]{40}").matches(commit)) { "Locked GitHub commit must be a full lowercase SHA" }
        require(Regex("[0-9a-f]{64}").matches(rawSha256)) { "Locked source SHA-256 is invalid" }
        require(rawSize >= 0) { "Locked source size must not be negative" }
        require(selectedPath.isNotBlank()) { "Locked source path is required" }
    }
}

@Serializable
public data class LockedPackageCoordinate(
    public val packageId: PackageId,
    public val version: SemVer,
    public val repositoryId: String,
    public val type: PayloadType,
    public val classifier: String,
    public val sha256: String,
    public val size: Long,
) {
    init {
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "Locked payload SHA-256 is invalid" }
        require(size >= 0) { "Locked payload size must not be negative" }
    }
}
