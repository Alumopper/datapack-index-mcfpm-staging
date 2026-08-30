package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.ConsumerProfile
import moe.afox.mcfpm.model.Dependency
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.PayloadRef
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.ResolvedEdge
import moe.afox.mcfpm.model.ResolvedGraph
import moe.afox.mcfpm.model.ResolvedPackage
import moe.afox.mcfpm.model.SemVer
import moe.afox.mcfpm.model.VersionRequirement

public data class RootRequirement(
    public val packageId: PackageId,
    public val requirement: VersionRequirement,
    public val features: List<String> = emptyList(),
)

public data class ResolveRequest(
    public val roots: List<RootRequirement>,
    public val consumerProfile: ConsumerProfile = ConsumerProfile.ALL,
    public val minecraftVersion: String? = null,
)

public class PubGrubResolver(
    private val repositories: RepositoryRegistry,
) {
    public fun resolve(request: ResolveRequest): McfpmResult<ResolvedGraph> {
        if (request.roots.isEmpty()) {
            return McfpmResult.Success(
                ResolvedGraph(
                    resolverVersion = RESOLVER_VERSION,
                    roots = emptyList(),
                    packages = emptyList(),
                    edges = emptyList(),
                    loadOrder = emptyList(),
                    minecraftVersion = request.minecraftVersion,
                ),
            )
        }
        val duplicateRoots = request.roots.groupBy(RootRequirement::packageId).filterValues { it.size > 1 }.keys
        if (duplicateRoots.isNotEmpty()) return failure("Root dependencies must be unique: ${duplicateRoots.sorted().joinToString()}")

        val initial = SolverState(
            constraints = request.roots.associate { root ->
                root.packageId to listOf(Constraint(null, root.requirement, root.features.distinct().sorted()))
            },
        )
        val cache = mutableMapOf<Pair<PackageId, String>, List<RepositoryCandidate>>()
        val outcome = try {
            search(initial, cache)
        } catch (abort: ResolutionAbort) {
            return abort.failure
        } catch (exception: Exception) {
            return failure("Invalid repository package metadata: ${exception.message}")
        }
        if (outcome is SearchOutcome.Conflict) {
            return McfpmResult.Failure(listOf(outcome.diagnostic))
        }
        val solved = (outcome as SearchOutcome.Solved).state
        val cycle = findCycle(solved)
        if (cycle != null) {
            return McfpmResult.Failure(
                listOf(
                    Diagnostic(
                        DiagnosticCode.DEPENDENCY_CYCLE,
                        DiagnosticSeverity.ERROR,
                        "Dependency cycle: ${cycle.joinToString(" -> ")}",
                        mapOf("cycle" to cycle.joinToString(" -> ")),
                    ),
                ),
            )
        }
        return runCatching { toResolvedGraph(request, solved) }.fold(
            onSuccess = { McfpmResult.Success(it) },
            onFailure = { cause -> failure("Invalid resolved package metadata: ${cause.message}") },
        )
    }

    private fun search(
        input: SolverState,
        cache: MutableMap<Pair<PackageId, String>, List<RepositoryCandidate>>,
    ): SearchOutcome {
        val state = propagate(input)
        state.selected.forEach { (packageId, selected) ->
            val requirements = state.constraints.getValue(packageId)
            if (requirements.any { !it.requirement.matches(selected) }) {
                return conflict(packageId, requirements, "Selected version $selected no longer satisfies all constraints")
            }
        }

        val unresolved = state.constraints.keys.filterNot(state.selected::containsKey)
        if (unresolved.isEmpty()) return SearchOutcome.Solved(state)

        val candidatesByPackage = unresolved.associateWith { packageId ->
            val constraints = state.constraints.getValue(packageId)
            val requirements = constraints.map(Constraint::requirement)
            val cacheKey = packageId to requirements.map(VersionRequirement::expression).sorted().joinToString(" ")
            cache.getOrPut(cacheKey) {
                unwrap(repositories.candidates(packageId, requirements))
            }
        }
        val packageId = candidatesByPackage.entries
            .sortedWith(compareBy({ it.value.size }, { it.key }))
            .first()
            .key
        val candidates = candidatesByPackage.getValue(packageId)
        if (candidates.isEmpty()) {
            return conflict(packageId, state.constraints.getValue(packageId), "No available version satisfies all constraints")
        }

        var lastConflict: SearchOutcome.Conflict? = null
        candidates.forEach { candidate ->
            val version = candidate.version
            val repositoryManifest = candidate.manifest
            val manifest = repositoryManifest.manifest
            if (manifest.packageId != packageId || manifest.version != version) {
                lastConflict = conflict(
                    packageId,
                    state.constraints.getValue(packageId),
                    "Repository descriptor coordinate does not match $packageId@$version",
                ) as SearchOutcome.Conflict
                return@forEach
            }
            val canonical = CanonicalJson.encodeManifest(manifest)
            if (!canonical.contentEquals(repositoryManifest.descriptorBytes)) {
                lastConflict = conflict(
                    packageId,
                    state.constraints.getValue(packageId),
                    "Repository descriptor is not canonical JSON",
                ) as SearchOutcome.Conflict
                return@forEach
            }
            if (!ManifestSigner.verify(manifest)) {
                lastConflict = conflict(
                    packageId,
                    state.constraints.getValue(packageId),
                    "Repository descriptor signature is invalid",
                ) as SearchOutcome.Conflict
                return@forEach
            }
            val branch = state.copy(
                selected = state.selected + (packageId to version),
                manifests = state.manifests + (packageId to candidate),
            )
            when (val result = search(branch, cache)) {
                is SearchOutcome.Solved -> return result
                is SearchOutcome.Conflict -> lastConflict = result
            }
        }
        return lastConflict ?: conflict(packageId, state.constraints.getValue(packageId), "Resolution failed")
    }

    private fun propagate(input: SolverState): SolverState {
        val constraints = input.constraints.mapValuesTo(linkedMapOf()) { (_, value) -> value.toMutableList() }
        var changed: Boolean
        do {
            changed = false
            input.manifests.toSortedMap().forEach { (packageId, repositoryManifest) ->
                val requestedFeatures = constraints[packageId]
                    .orEmpty()
                    .flatMap(Constraint::features)
                    .distinct()
                    .sorted()
                val manifest = repositoryManifest.manifest.manifest
                val knownFeatures = manifest.features.map { it.name }.toSet()
                val unknownFeatures = requestedFeatures.filterNot(knownFeatures::contains)
                if (unknownFeatures.isNotEmpty()) {
                    throw ResolutionAbort(
                        failure("Unknown feature(s) for $packageId: ${unknownFeatures.joinToString()}"),
                    )
                }
                val enabledOptionalDependencies = manifest.features
                    .filter { it.name in requestedFeatures }
                    .flatMap { it.enablesDependencies }
                    .toSet()
                manifest.dependencies
                    .filter { dependency -> !dependency.optional || dependency.packageId in enabledOptionalDependencies }
                    .forEach { dependency ->
                        val constraint = Constraint(packageId, dependency.requirement, dependency.features.distinct().sorted())
                        val packageConstraints = constraints.getOrPut(dependency.packageId) { mutableListOf() }
                        if (constraint !in packageConstraints) {
                            packageConstraints += constraint
                            changed = true
                        }
                    }
            }
        } while (changed)
        return input.copy(constraints = constraints.mapValues { it.value.toList() })
    }

    private fun toResolvedGraph(request: ResolveRequest, state: SolverState): ResolvedGraph {
        val edges = state.constraints
            .flatMap { (to, constraints) ->
                constraints.mapNotNull { constraint ->
                    constraint.from?.let { from -> ResolvedEdge(from, to, constraint.requirement) }
                }
            }
            .distinct()
        val packageOrder = dependencyOrder(state.selected.keys, edges)
        val packages = state.selected.map { (packageId, version) ->
            val candidate = state.manifests.getValue(packageId)
            val repositoryManifest = candidate.manifest
            val repository = candidate.repository
            val selectedFeatures = state.constraints.getValue(packageId).flatMap(Constraint::features).distinct().sorted()
            ResolvedPackage(
                packageId = packageId,
                version = version,
                repositoryUrl = repository.baseUri.normalize().toString(),
                descriptorSha256 = Hashing.sha256(repositoryManifest.descriptorBytes),
                selectedFeatures = selectedFeatures,
                signatureFingerprint = repositoryManifest.manifest.signatureFingerprint,
                artifacts = selectArtifacts(repositoryManifest.manifest, request.consumerProfile),
            )
        }
        val packagesById = packages.associateBy(ResolvedPackage::packageId)
        val loadOrder = packageOrder.flatMap { packageId ->
            packagesById.getValue(packageId).artifacts.map { artifact ->
                PayloadRef(packageId, artifact.type, artifact.classifier)
            }
        }
        val diagnostics = request.minecraftVersion?.let { targetVersion ->
            packages.flatMap { resolvedPackage ->
                resolvedPackage.artifacts.mapNotNull { artifact ->
                    val range = artifact.minecraft ?: return@mapNotNull null
                    if (MinecraftTargetRequirement.matches(range, targetVersion)) return@mapNotNull null
                    Diagnostic(
                        DiagnosticCode.MINECRAFT_FORMAT_MISMATCH,
                        DiagnosticSeverity.WARNING,
                        "Payload Minecraft range $range does not include target $targetVersion",
                        mapOf(
                            "package" to resolvedPackage.packageId.value,
                            "classifier" to artifact.classifier,
                            "target" to targetVersion,
                            "range" to range,
                        ),
                    )
                }
            }
        }.orEmpty()
        return ResolvedGraph(
            resolverVersion = RESOLVER_VERSION,
            roots = request.roots.map(RootRequirement::packageId),
            packages = packages,
            edges = edges,
            loadOrder = loadOrder,
            diagnostics = diagnostics,
            minecraftVersion = request.minecraftVersion,
        ).normalized()
    }

    private fun selectArtifacts(manifest: PackageManifest, profile: ConsumerProfile) =
        if (profile == ConsumerProfile.ALL) {
            manifest.artifacts
        } else {
            val primaryTypes = when (profile) {
                ConsumerProfile.MINECRAFT_DATAPACK -> setOf(PayloadType.MINECRAFT_DATAPACK)
                ConsumerProfile.MINECRAFT_RESOURCEPACK -> setOf(PayloadType.MINECRAFT_RESOURCEPACK)
                ConsumerProfile.MCFPP -> setOf(PayloadType.MCFPP_LIBRARY)
                ConsumerProfile.JVM_PLUGIN -> setOf(PayloadType.JVM_PLUGIN)
                ConsumerProfile.ALL -> error("Handled above")
            }
            val selected = manifest.artifacts.filter { it.type in primaryTypes }.toMutableSet()
            var changed: Boolean
            do {
                changed = false
                selected.toList().flatMap { it.requires }.forEach { requirement ->
                    manifest.artifacts
                        .firstOrNull { it.type == requirement.type && it.classifier == requirement.classifier }
                        ?.let { required -> if (selected.add(required)) changed = true }
                }
            } while (changed)
            selected.sortedWith(compareBy({ it.type }, { it.classifier }))
        }

    private fun dependencyOrder(packages: Set<PackageId>, edges: List<ResolvedEdge>): List<PackageId> {
        val dependencies = edges.groupBy(ResolvedEdge::from).mapValues { (_, values) -> values.map(ResolvedEdge::to).distinct().sorted() }
        val visited = mutableSetOf<PackageId>()
        val ordered = mutableListOf<PackageId>()
        fun visit(packageId: PackageId) {
            if (!visited.add(packageId)) return
            dependencies[packageId].orEmpty().forEach(::visit)
            ordered += packageId
        }
        packages.sorted().forEach(::visit)
        return ordered
    }

    private fun findCycle(state: SolverState): List<PackageId>? {
        val dependencies = state.constraints
            .flatMap { (to, constraints) -> constraints.mapNotNull { it.from?.let { from -> from to to } } }
            .groupBy(Pair<PackageId, PackageId>::first)
            .mapValues { (_, pairs) -> pairs.map(Pair<PackageId, PackageId>::second).distinct().sorted() }
        val visiting = linkedSetOf<PackageId>()
        val visited = mutableSetOf<PackageId>()
        fun visit(packageId: PackageId): List<PackageId>? {
            if (packageId in visiting) {
                val path = visiting.toList()
                val start = path.indexOf(packageId)
                return path.drop(start) + packageId
            }
            if (!visited.add(packageId)) return null
            visiting += packageId
            dependencies[packageId].orEmpty().forEach { dependency ->
                visit(dependency)?.let { return it }
            }
            visiting -= packageId
            return null
        }
        state.selected.keys.sorted().forEach { packageId -> visit(packageId)?.let { return it } }
        return null
    }

    private fun conflict(
        packageId: PackageId,
        constraints: List<Constraint>,
        reason: String,
    ): SearchOutcome = SearchOutcome.Conflict(
        Diagnostic(
            DiagnosticCode.RESOLUTION_FAILED,
            DiagnosticSeverity.ERROR,
            "$reason for $packageId",
            mapOf(
                "package" to packageId.value,
                "constraints" to constraints.joinToString("; ") { constraint ->
                    "${constraint.from?.value ?: "root"} requires ${constraint.requirement.expression}"
                },
            ),
        ),
    )

    private fun failure(message: String): McfpmResult.Failure = McfpmResult.Failure(
        listOf(Diagnostic(DiagnosticCode.RESOLUTION_FAILED, DiagnosticSeverity.ERROR, message)),
    )

    private fun <T> unwrap(result: McfpmResult<T>): T = when (result) {
        is McfpmResult.Success -> result.value
        is McfpmResult.Failure -> throw ResolutionAbort(result)
    }

    private data class Constraint(
        val from: PackageId?,
        val requirement: VersionRequirement,
        val features: List<String>,
    )

    private data class SolverState(
        val constraints: Map<PackageId, List<Constraint>>,
        val selected: Map<PackageId, SemVer> = emptyMap(),
        val manifests: Map<PackageId, RepositoryCandidate> = emptyMap(),
    )

    private sealed interface SearchOutcome {
        data class Solved(val state: SolverState) : SearchOutcome
        data class Conflict(val diagnostic: Diagnostic) : SearchOutcome
    }

    private class ResolutionAbort(val failure: McfpmResult.Failure) : RuntimeException()

    public companion object {
        public const val RESOLVER_VERSION: String = "1"
    }
}

private object MinecraftTargetRequirement {
    fun matches(expression: String, target: String): Boolean {
        val version = MinecraftTargetVersion.parse(target)
        val terms = expression.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        require(terms.isNotEmpty()) { "Minecraft range must not be empty" }
        return terms.all { term ->
            val operator = listOf(">=", "<=", ">", "<", "=").firstOrNull(term::startsWith).orEmpty()
            val required = MinecraftTargetVersion.parse(term.removePrefix(operator))
            when (operator) {
                ">=" -> version >= required
                "<=" -> version <= required
                ">" -> version > required
                "<" -> version < required
                "", "=" -> version == required
                else -> false
            }
        }
    }
}

@ConsistentCopyVisibility
private data class MinecraftTargetVersion private constructor(val parts: List<Int>) : Comparable<MinecraftTargetVersion> {
    override fun compareTo(other: MinecraftTargetVersion): Int {
        repeat(maxOf(parts.size, other.parts.size)) { index ->
            val comparison = parts.getOrElse(index) { 0 }.compareTo(other.parts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        fun parse(value: String): MinecraftTargetVersion {
            val parts = value.split('.').map { part ->
                require(part.isNotEmpty() && part.all(Char::isDigit)) { "Invalid Minecraft version: $value" }
                part.toInt()
            }
            return MinecraftTargetVersion(parts)
        }
    }
}
