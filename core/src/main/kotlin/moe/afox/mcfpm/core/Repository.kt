package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.ArtifactDescriptor
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.VersionRequirement
import java.net.URI

public data class RepositoryManifest(
    public val manifest: PackageManifest,
    public val descriptorBytes: ByteArray,
)

/** A version candidate together with the repository that actually supplied it. */
public data class RepositoryCandidate(
    public val repository: PackageRepository,
    public val version: SemVer,
    public val manifest: RepositoryManifest,
)

public interface PackageRepository {
    public val id: String
    public val baseUri: URI

    public fun versions(packageId: PackageId): McfpmResult<List<SemVer>>

    public fun manifest(packageId: PackageId, version: SemVer): McfpmResult<RepositoryManifest>

    public fun artifactUri(
        packageId: PackageId,
        version: SemVer,
        artifact: ArtifactDescriptor,
    ): URI

    /** Headers used when downloading repository-owned artifacts. Values must never be persisted. */
    public fun requestHeaders(uri: URI): Map<String, String> = emptyMap()
}

public data class RepositoryBinding(
    public val group: String,
    public val repositoryId: String,
) {
    init {
        require(groupPattern.matches(group)) { "Invalid package group binding: $group" }
        require(repositoryId.isNotBlank()) { "Repository binding requires a repository ID" }
    }

    private companion object {
        val groupPattern: Regex = Regex("[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?")
    }
}

public class RepositoryRegistry(
    repositories: Collection<PackageRepository>,
    bindings: Collection<RepositoryBinding> = emptyList(),
    defaultRepositoryId: String,
    defaultRepositoryIds: List<String> = listOf(defaultRepositoryId),
    lockedPackageRepositories: Map<PackageId, String> = emptyMap(),
) {
    private val repositoryList: List<PackageRepository> = repositories.toList()
    private val repositoriesById: Map<String, PackageRepository> = repositoryList.associateBy(PackageRepository::id)
    private val bindingsByGroup: Map<String, String> = bindings.associate { it.group to it.repositoryId }
    private val lockedPackageRepositoriesById: Map<PackageId, PackageRepository>
    private val defaultRepositories: List<PackageRepository>

    public val effectiveDefaultRepositoryIds: List<String> = defaultRepositoryIds.toList()

    init {
        require(repositoriesById.size == repositoryList.size) { "Repository IDs must be unique" }
        require(bindingsByGroup.size == bindings.size) { "Each package group may be bound only once" }
        require(bindingsByGroup.values.all(repositoriesById::containsKey)) { "Repository binding references an unknown repository" }
        require(defaultRepositoryIds.isNotEmpty()) { "At least one default repository is required" }
        require(defaultRepositoryIds.distinct().size == defaultRepositoryIds.size) {
            "Default repository priority must not contain duplicates"
        }
        require(defaultRepositoryIds.all(repositoriesById::containsKey)) {
            "Default repository priority references an unknown repository"
        }
        require(defaultRepositoryId == defaultRepositoryIds.first()) {
            "defaultRepositoryId must be the first repository in the effective priority"
        }
        require(lockedPackageRepositories.values.all(repositoriesById::containsKey)) {
            "Locked package source references an unknown repository"
        }
        lockedPackageRepositoriesById = lockedPackageRepositories.mapValues { (_, repositoryId) ->
            repositoriesById.getValue(repositoryId)
        }
        defaultRepositories = defaultRepositoryIds.map(repositoriesById::getValue)
    }

    public fun repositoryFor(packageId: PackageId): PackageRepository {
        return repositoriesFor(packageId).first()
    }

    public fun repository(id: String): PackageRepository? = repositoriesById[id]

    /** Returns the effective repository chain. A group binding always has exactly one entry. */
    public fun repositoriesFor(packageId: PackageId): List<PackageRepository> =
        lockedPackageRepositoriesById[packageId]?.let { listOf(it) }
            ?: bindingsByGroup[packageId.group]?.let { listOf(repositoriesById.getValue(it)) }
            ?: defaultRepositories

    /**
     * Returns the repository named by a lockfile source after checking that the package is allowed
     * to use it. Fetching must use this exact repository rather than the current default.
     */
    public fun repositoryForSource(packageId: PackageId, source: String): McfpmResult<PackageRepository> {
        val validation = validateLockedSource(packageId, source)
        if (validation is McfpmResult.Failure) return validation
        val normalized = URI.create(source).normalize().toString()
        return repositoriesFor(packageId).firstOrNull { it.baseUri.normalize().toString() == normalized }
            ?.let { McfpmResult.Success(it) }
            ?: McfpmResult.Failure(
                listOf(
                    Diagnostic(
                        DiagnosticCode.REPOSITORY_VIOLATION,
                        DiagnosticSeverity.ERROR,
                        "Locked source is not available for ${packageId.value}",
                    ),
                ),
            )
    }

    /**
     * Enumerates candidates in repository priority order and version-descending order inside
     * each repository. A lower-priority repository is consulted only when the higher-priority
     * repository has no matching, readable descriptor. Non-absence failures stop the search.
     */
    public fun candidates(
        packageId: PackageId,
        requirements: Collection<VersionRequirement> = emptyList(),
    ): McfpmResult<List<RepositoryCandidate>> {
        val chain = repositoriesFor(packageId)
        chain.forEach { repository ->
            val versions = when (val result = repository.versions(packageId)) {
                is McfpmResult.Success -> result.value
                is McfpmResult.Failure -> {
                    if (isTrustedAbsence(result)) return@forEach
                    return result
                }
            }
            val matchingVersions = versions
                .distinct()
                .filter { version -> requirements.all { it.matches(version) } }
                .sortedDescending()
            if (matchingVersions.isEmpty()) return@forEach

            val candidates = mutableListOf<RepositoryCandidate>()
            matchingVersions.forEach { version ->
                when (val result = repository.manifest(packageId, version)) {
                    is McfpmResult.Success -> candidates += RepositoryCandidate(repository, version, result.value)
                    is McfpmResult.Failure -> {
                        if (!isTrustedAbsence(result)) return result
                    }
                }
            }
            if (candidates.isNotEmpty()) return McfpmResult.Success(candidates)
        }
        return McfpmResult.Success(emptyList())
    }

    /** Alias with a descriptive name for callers that need to make source selection explicit. */
    public fun candidatesWithSources(
        packageId: PackageId,
        requirements: Collection<VersionRequirement> = emptyList(),
    ): McfpmResult<List<RepositoryCandidate>> = candidates(packageId, requirements)

    public fun candidate(packageId: PackageId, version: SemVer): McfpmResult<RepositoryCandidate> =
        when (val result = candidates(packageId, listOf(VersionRequirement.exact(version)))) {
            is McfpmResult.Failure -> result
            is McfpmResult.Success -> result.value.firstOrNull()?.let { McfpmResult.Success(it) }
                ?: McfpmResult.Failure(
                    listOf(
                        Diagnostic(
                            DiagnosticCode.RESOLUTION_FAILED,
                            DiagnosticSeverity.ERROR,
                            "No repository contains $packageId@$version",
                        ),
                    ),
                )
        }

    public fun validateLockedSource(packageId: PackageId, lockedRepository: String): McfpmResult<Unit> {
        val actual = runCatching { URI.create(lockedRepository).normalize().toString() }.getOrElse {
            return McfpmResult.Failure(
                listOf(
                    Diagnostic(
                        DiagnosticCode.REPOSITORY_VIOLATION,
                        DiagnosticSeverity.ERROR,
                        "Locked source is not a valid repository URI for ${packageId.value}",
                    ),
                ),
            )
        }
        val expected = repositoriesFor(packageId).map { it.baseUri.normalize().toString() }
        return if (actual in expected) {
            McfpmResult.Success(Unit)
        } else {
            McfpmResult.Failure(
                listOf(
                    Diagnostic(
                        DiagnosticCode.REPOSITORY_VIOLATION,
                        DiagnosticSeverity.ERROR,
                        "Locked source does not match the repository bound to ${packageId.value}",
                        mapOf("expected" to expected.joinToString(","), "actual" to actual),
                    ),
                ),
            )
        }
    }

    private fun isTrustedAbsence(result: McfpmResult.Failure): Boolean =
        result.diagnostics.isNotEmpty() && result.diagnostics.all { diagnostic ->
            diagnostic.context[REPOSITORY_FAILURE_KIND] == REPOSITORY_NOT_FOUND
        }

    private companion object {
        const val REPOSITORY_FAILURE_KIND: String = "repository-failure-kind"
        const val REPOSITORY_NOT_FOUND: String = "not-found"
    }
}
