package moe.afox.mcfpm.minecraft

import moe.afox.mcfpm.core.FetchedGraph
import moe.afox.mcfpm.core.Hashing
import moe.afox.mcfpm.core.InstallContext
import moe.afox.mcfpm.core.InstallContextKind
import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadRef
import moe.afox.mcfpm.model.PayloadType
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

public fun interface DataVersionRegistry {
    public fun minecraftVersion(dataVersion: Int): String?
}

public object MojangDataVersionRegistry : DataVersionRegistry {
    override fun minecraftVersion(dataVersion: Int): String? = VERSIONS[dataVersion]

    private val VERSIONS: Map<Int, String> = mapOf(
        3463 to "1.20",
        3465 to "1.20.1",
        3578 to "1.20.2",
        3698 to "1.20.3",
        3700 to "1.20.4",
        3837 to "1.20.5",
        3839 to "1.20.6",
        3953 to "1.21",
        3955 to "1.21.1",
        4080 to "1.21.2",
        4082 to "1.21.3",
        4189 to "1.21.4",
        4325 to "1.21.5",
        4435 to "1.21.6",
        4438 to "1.21.7",
        4440 to "1.21.8",
        4554 to "1.21.9",
        4556 to "1.21.10",
        4671 to "1.21.11",
        4786 to "26.1",
        4788 to "26.1.1",
        4790 to "26.1.2",
        4903 to "26.2",
    )
}

public data class MinecraftInstallRequest(
    public val fetched: FetchedGraph,
    public val context: InstallContext,
    public val explicitWorld: Path? = null,
    public val explicitInstance: Path? = null,
    public val confirmedGlobalResourcePackImpact: Boolean = false,
    public val dryRun: Boolean = false,
    public val force: Boolean = false,
)

public data class PlannedCopy(
    public val payload: PayloadRef,
    public val source: Path,
    public val target: Path,
)

public data class PlannedOrderChange(
    public val file: Path,
    public val before: List<String>,
    public val after: List<String>,
)

public data class MinecraftInstallPlan(
    public val context: InstallContext,
    public val world: Path?,
    public val instance: Path?,
    public val copies: List<PlannedCopy>,
    public val removals: List<Path>,
    public val orderChanges: List<PlannedOrderChange>,
    public val requiresGlobalResourcePackConfirmation: Boolean,
    public val evidence: List<String>,
)

public data class MinecraftInstallResult(
    public val plan: MinecraftInstallPlan,
    public val transactionId: String?,
    public val dryRun: Boolean,
)

public class MinecraftInstallEngine(
    private val detector: MinecraftInstallContextDetector = MinecraftInstallContextDetector(),
    private val dataVersions: DataVersionRegistry = MojangDataVersionRegistry,
    private val mutationHook: MutationHook = MutationHook { _, _ -> },
) {
    public fun plan(request: MinecraftInstallRequest): McfpmResult<MinecraftInstallPlan> = safely {
        when (val prepared = prepare(request)) {
            is McfpmResult.Success -> McfpmResult.Success(prepared.value.plan)
            is McfpmResult.Failure -> prepared
        }
    }

    public fun install(request: MinecraftInstallRequest): McfpmResult<MinecraftInstallResult> = safely {
        val prepared = when (val result = prepare(request)) {
            is McfpmResult.Success -> result.value
            is McfpmResult.Failure -> return@safely result
        }
        if (prepared.plan.requiresGlobalResourcePackConfirmation &&
            !request.confirmedGlobalResourcePackImpact &&
            !request.dryRun
        ) {
            return@safely failure(
                DiagnosticCode.INSTALL_CONFIRMATION_REQUIRED,
                "Installing world resource packs into ${prepared.plan.instance} affects every world in that instance; confirmation is required",
            )
        }
        if (request.dryRun || prepared.mutations.isEmpty()) {
            return@safely McfpmResult.Success(MinecraftInstallResult(prepared.plan, null, request.dryRun))
        }
        return@safely withTargetLocks(prepared.plan.world, prepared.plan.instance) {
            execute(prepared, request.force)
        }
    }

    public fun rollback(context: InstallContext, force: Boolean = false): McfpmResult<String> = safely {
        val primaryRoot = context.root.toAbsolutePath().normalize()
        val state = readLatestState(primaryRoot)
            ?: return@safely failure(DiagnosticCode.DEPLOYMENT_FAILED, "No Mcfpm installation transaction is available to roll back")
        val world = state.worldRoot?.let(Path::of)
        val instance = state.instanceRoot?.let(Path::of)
        return@safely withTargetLocks(world, instance) {
            val allowedRoots = state.allowedRoots.map(Path::of)
            val drift = state.files.filter { record ->
                val target = Path.of(record.target)
                !isAllowed(target, allowedRoots) || !matchesInstalledState(target, record)
            }
            if (drift.isNotEmpty() && !force) {
                return@withTargetLocks failure(
                    DiagnosticCode.USER_DRIFT,
                    "Managed files changed after installation; use --force to roll back",
                    mapOf("files" to drift.joinToString { it.target }),
                )
            }
            return@withTargetLocks runCatching {
                state.files.asReversed().forEach { record -> restoreRecord(state, record) }
                val latest = stateRoot(primaryRoot).resolve("latest")
                if (state.previousTransactionId == null) {
                    Files.deleteIfExists(latest)
                } else {
                    atomicWrite(latest, state.previousTransactionId.encodeToByteArray())
                }
                state.id
            }.fold(
                onSuccess = { McfpmResult.Success(it) },
                onFailure = { failure(DiagnosticCode.DEPLOYMENT_FAILED, "Rollback failed: ${it.message}") },
            )
        }
    }

    private fun prepare(request: MinecraftInstallRequest): McfpmResult<PreparedInstall> {
        if (request.context.kind == InstallContextKind.PROJECT) {
            val plan = MinecraftInstallPlan(
                context = request.context,
                world = null,
                instance = null,
                copies = emptyList(),
                removals = emptyList(),
                orderChanges = emptyList(),
                requiresGlobalResourcePackConfirmation = false,
                evidence = request.context.evidence + "Project context only resolves and populates the content cache",
            )
            return McfpmResult.Success(PreparedInstall(plan, emptyList(), request.context.root, emptyList()))
        }

        val explicitWorld = when (val result = explicitContext(InstallContextKind.WORLD, request.explicitWorld)) {
            is McfpmResult.Success -> result.value
            is McfpmResult.Failure -> return result
        }
        val explicitInstance = when (val result = explicitContext(InstallContextKind.INSTANCE, request.explicitInstance)) {
            is McfpmResult.Success -> result.value
            is McfpmResult.Failure -> return result
        }
        val world = explicitWorld
            ?: request.context.root.takeIf { request.context.kind == InstallContextKind.WORLD }
        val instance = explicitInstance
            ?: request.context.root.takeIf { request.context.kind == InstallContextKind.INSTANCE }
            ?: request.context.inferredInstance
        val graph = request.fetched.graph
        val packages = graph.packages.associateBy { it.packageId }
        val dataPacks = graph.loadOrder.filter { it.type == PayloadType.MINECRAFT_DATAPACK }
        val resourcePacks = graph.loadOrder.filter { it.type == PayloadType.MINECRAFT_RESOURCEPACK }
        if (dataPacks.isNotEmpty() && world == null) {
            return failure(
                DiagnosticCode.DEPLOYMENT_FAILED,
                "Installing data packs from an instance context requires an explicit --world",
            )
        }
        if (resourcePacks.isNotEmpty() && world == null && instance == null) {
            return failure(DiagnosticCode.DEPLOYMENT_FAILED, "Resource packs require a world or instance target")
        }
        if (resourcePacks.size > 1 && world != null && instance == null) {
            return failure(
                DiagnosticCode.DEPLOYMENT_FAILED,
                "An independent world cannot install multiple resource packs; specify --instance",
            )
        }

        val allowedRoots = listOfNotNull(world, instance).map { it.toRealPath() }.distinct()
        val copies = mutableListOf<PlannedCopy>()
        val mutations = mutableListOf<Mutation>()
        fun payloadBytes(reference: PayloadRef): Pair<Path, ByteArray> {
            val source = request.fetched.artifacts[reference]
                ?: throw IllegalArgumentException("Fetched graph is missing $reference")
            return source to Files.readAllBytes(source)
        }

        val dataPackNames = mutableListOf<String>()
        dataPacks.forEach { reference ->
            val resolved = packages.getValue(reference.packageId)
            val artifact = resolved.artifacts.single { it.type == reference.type && it.classifier == reference.classifier }
            val fileName = payloadFileName(reference.packageId, resolved.version.toString(), reference.classifier, artifact.extension)
            val target = safeResolve(requireNotNull(world), Path.of("datapacks", fileName))
            val (source, bytes) = payloadBytes(reference)
            copies += PlannedCopy(reference, source, target)
            mutations += mutation(target, bytes, FileRole.PAYLOAD)
            dataPackNames += fileName
        }

        val resourcePackNames = mutableListOf<String>()
        if (resourcePacks.isNotEmpty() && instance != null) {
            resourcePacks.forEach { reference ->
                val resolved = packages.getValue(reference.packageId)
                val artifact = resolved.artifacts.single { it.type == reference.type && it.classifier == reference.classifier }
                val fileName = payloadFileName(reference.packageId, resolved.version.toString(), reference.classifier, artifact.extension)
                val target = safeResolve(instance, Path.of("resourcepacks", fileName))
                val (source, bytes) = payloadBytes(reference)
                copies += PlannedCopy(reference, source, target)
                mutations += mutation(target, bytes, FileRole.PAYLOAD)
                resourcePackNames += fileName
            }
        } else if (resourcePacks.size == 1 && world != null) {
            val reference = resourcePacks.single()
            val targetVersion = resolveTargetMinecraftVersion(graph.minecraftVersion, world)
                ?: return failure(
                    DiagnosticCode.DEPLOYMENT_FAILED,
                    "Minecraft target version is missing and the world's DataVersion is unsupported",
                )
            val relative = if (MinecraftVersion.parse(targetVersion) >= MinecraftVersion.parse("26.1")) {
                Path.of("resourcepacks", "resources.zip")
            } else {
                Path.of("resources.zip")
            }
            val target = safeResolve(world, relative)
            val (source, bytes) = payloadBytes(reference)
            copies += PlannedCopy(reference, source, target)
            mutations += mutation(target, bytes, FileRole.PAYLOAD)
        }

        val orderChanges = mutableListOf<PlannedOrderChange>()
        if (dataPackNames.isNotEmpty()) {
            val levelDat = requireNotNull(world).resolve("level.dat")
            val beforeBytes = Files.readAllBytes(levelDat)
            val before = runCatching { MinecraftLevelDat.enabledDataPacks(beforeBytes) }
                .getOrElse { return failure(DiagnosticCode.DEPLOYMENT_FAILED, "Invalid level.dat: ${it.message}") }
            val afterBytes = runCatching { MinecraftLevelDat.updateEnabledDataPacks(beforeBytes, dataPackNames) }
                .getOrElse { return failure(DiagnosticCode.DEPLOYMENT_FAILED, "Unable to update level.dat: ${it.message}") }
            val after = MinecraftLevelDat.enabledDataPacks(afterBytes)
            mutations += mutation(levelDat, afterBytes, FileRole.METADATA)
            orderChanges += PlannedOrderChange(levelDat, before, after)
        }
        if (resourcePackNames.isNotEmpty()) {
            val options = requireNotNull(instance).resolve("options.txt")
            val beforeBytes = Files.readAllBytes(options)
            val update = runCatching { updateResourcePackOptions(beforeBytes, resourcePackNames) }
                .getOrElse { return failure(DiagnosticCode.DEPLOYMENT_FAILED, "Invalid options.txt: ${it.message}") }
            mutations += mutation(options, update.bytes, FileRole.METADATA)
            orderChanges += PlannedOrderChange(options, update.before, update.after)
        }

        val primaryRoot = request.context.root.toAbsolutePath().normalize()
        val previous = readLatestState(primaryRoot)
        val desiredTargets = mutations.map { it.target }.toSet()
        val removals = previous?.files.orEmpty()
            .filter { it.role == FileRole.PAYLOAD.name && it.installedSha256 != null }
            .map { Path.of(it.target) }
            .filter { it !in desiredTargets }
            .filter { isAllowed(it, allowedRoots) }
            .distinct()
        removals.forEach { target -> mutations += mutation(target, null, FileRole.PAYLOAD) }

        val requiresConfirmation = request.context.kind == InstallContextKind.WORLD &&
            resourcePacks.isNotEmpty() && instance != null
        val plan = MinecraftInstallPlan(
            context = request.context,
            world = world,
            instance = instance,
            copies = copies,
            removals = removals,
            orderChanges = orderChanges,
            requiresGlobalResourcePackConfirmation = requiresConfirmation,
            evidence = request.context.evidence + buildList {
                if (instance != null && request.context.kind == InstallContextKind.WORLD) {
                    add("World belongs to instance $instance through the strict <instance>/saves/<world> layout")
                }
            },
        )
        return McfpmResult.Success(PreparedInstall(plan, mutations.distinctBy { it.target }, primaryRoot, allowedRoots))
    }

    private fun execute(prepared: PreparedInstall, force: Boolean): McfpmResult<MinecraftInstallResult> {
        val previous = readLatestState(prepared.primaryRoot)
        val previousByTarget = previous?.files.orEmpty().associateBy { Path.of(it.target) }
        val driftedPrevious = prepared.mutations.mapNotNull { mutation ->
            previousByTarget[mutation.target]?.takeIf { !matchesInstalledState(mutation.target, it) }
        }
        if (driftedPrevious.isNotEmpty() && !force) {
            return failure(
                DiagnosticCode.USER_DRIFT,
                "Managed files changed after the previous install; use --force to continue",
                mapOf("files" to driftedPrevious.joinToString { it.target }),
            )
        }
        prepared.mutations.forEach { mutation ->
            if (!matchesExpectedState(mutation)) {
                return failure(DiagnosticCode.USER_DRIFT, "Install target changed while planning: ${mutation.target}")
            }
        }

        val transactionId = UUID.randomUUID().toString()
        val transactionDirectory = stateRoot(prepared.primaryRoot).resolve("backups").resolve(transactionId)
        Files.createDirectories(transactionDirectory)
        val snapshots = prepared.mutations.mapIndexed { index, mutation ->
            val existed = Files.isRegularFile(mutation.target)
            val bytes = if (existed) Files.readAllBytes(mutation.target) else null
            if (bytes != null) Files.write(transactionDirectory.resolve("before-$index.bin"), bytes)
            Snapshot(mutation, existed, bytes, index)
        }

        return try {
            snapshots.forEachIndexed { index, snapshot ->
                mutationHook.beforeMutation(index, snapshot.mutation.target)
                val content = snapshot.mutation.content
                if (content == null) {
                    Files.deleteIfExists(snapshot.mutation.target)
                } else {
                    Files.createDirectories(snapshot.mutation.target.parent)
                    atomicWrite(snapshot.mutation.target, content)
                }
            }
            val state = TransactionState(
                id = transactionId,
                previousTransactionId = previous?.id,
                primaryRoot = prepared.primaryRoot.toString(),
                allowedRoots = prepared.allowedRoots.map(Path::toString),
                worldRoot = prepared.plan.world?.toString(),
                instanceRoot = prepared.plan.instance?.toString(),
                files = snapshots.map { snapshot ->
                    val installed = snapshot.mutation.content
                    TransactionFile(
                        target = snapshot.mutation.target.toString(),
                        role = snapshot.mutation.role.name,
                        beforeExisted = snapshot.existed,
                        beforeSha256 = snapshot.before?.let(Hashing::sha256),
                        backupFile = snapshot.before?.let { "before-${snapshot.index}.bin" },
                        installedSha256 = installed?.let(Hashing::sha256),
                    )
                },
            )
            atomicWrite(
                transactionDirectory.resolve("state.json"),
                CanonicalJson.encode(TransactionState.serializer(), state),
            )
            atomicWrite(stateRoot(prepared.primaryRoot).resolve("latest"), transactionId.encodeToByteArray())
            McfpmResult.Success(MinecraftInstallResult(prepared.plan, transactionId, dryRun = false))
        } catch (exception: Exception) {
            val restorationFailures = snapshots.asReversed().mapNotNull { snapshot ->
                runCatching { restoreSnapshot(snapshot) }.exceptionOrNull()
            }
            val suffix = if (restorationFailures.isEmpty()) "" else "; rollback also failed"
            failure(DiagnosticCode.DEPLOYMENT_FAILED, "Install transaction failed and was rolled back: ${exception.message}$suffix")
        }
    }

    private fun resolveTargetMinecraftVersion(lockedVersion: String?, world: Path): String? {
        if (lockedVersion != null) return lockedVersion
        val bytes = runCatching { Files.readAllBytes(world.resolve("level.dat")) }.getOrNull() ?: return null
        val dataVersion = runCatching { MinecraftLevelDat.dataVersion(bytes) }.getOrNull() ?: return null
        return dataVersions.minecraftVersion(dataVersion)
    }

    private fun explicitContext(kind: InstallContextKind, path: Path?): McfpmResult<Path?> {
        if (path == null) return McfpmResult.Success(null)
        return when (val result = detector.explicit(kind, path)) {
            is McfpmResult.Success -> McfpmResult.Success(result.value.root)
            is McfpmResult.Failure -> result
        }
    }

    private fun payloadFileName(id: PackageId, version: String, classifier: String, extension: String): String {
        val group = id.group.replace('.', '-').replace('_', '-')
        return "mcfpm-$group-${id.name}-$version-$classifier.$extension"
    }

    private fun mutation(target: Path, content: ByteArray?, role: FileRole): Mutation {
        val existing = target.takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
        return Mutation(
            target = target.toAbsolutePath().normalize(),
            content = content,
            role = role,
            expectedExisted = existing != null,
            expectedSha256 = existing?.let(Hashing::sha256),
        )
    }

    private fun matchesExpectedState(mutation: Mutation): Boolean {
        val exists = Files.isRegularFile(mutation.target)
        if (exists != mutation.expectedExisted) return false
        return !exists || Hashing.sha256(mutation.target) == mutation.expectedSha256
    }

    private fun matchesInstalledState(target: Path, record: TransactionFile): Boolean {
        val exists = Files.isRegularFile(target)
        return if (record.installedSha256 == null) {
            !exists
        } else {
            exists && Hashing.sha256(target) == record.installedSha256
        }
    }

    private fun restoreSnapshot(snapshot: Snapshot) {
        if (snapshot.before == null) {
            Files.deleteIfExists(snapshot.mutation.target)
        } else {
            atomicWrite(snapshot.mutation.target, snapshot.before)
        }
    }

    private fun restoreRecord(state: TransactionState, record: TransactionFile) {
        val target = Path.of(record.target)
        if (!record.beforeExisted) {
            Files.deleteIfExists(target)
        } else {
            val backupName = requireNotNull(record.backupFile)
            val backup = stateRoot(Path.of(state.primaryRoot)).resolve("backups").resolve(state.id).resolve(backupName)
            val bytes = Files.readAllBytes(backup)
            require(Hashing.sha256(bytes) == record.beforeSha256) { "Backup checksum mismatch for ${record.target}" }
            Files.createDirectories(target.parent)
            atomicWrite(target, bytes)
        }
    }

    private fun updateResourcePackOptions(bytes: ByteArray, managedFileNames: List<String>): OptionsUpdate {
        val text = bytes.decodeToString()
        val lineEnding = if ("\r\n" in text) "\r\n" else "\n"
        val lines = text.split(Regex("\\r?\\n")).toMutableList()
        val index = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        require(index >= 0) { "options.txt is missing resourcePacks" }
        val encodedBefore = lines[index].substringAfter(':')
        val before = CanonicalJson.format.parseToJsonElement(encodedBefore).jsonArray.map { it.jsonPrimitive.content }
        val unmanaged = before.filterNot { it.startsWith("file/mcfpm-") }
        val after = (unmanaged + managedFileNames.map { "file/$it" }).distinct()
        lines[index] = "resourcePacks:" + JsonArray(after.map(::JsonPrimitive)).toString()
        val trailingLine = text.endsWith("\n")
        var updated = lines.joinToString(lineEnding)
        if (trailingLine && !updated.endsWith(lineEnding)) updated += lineEnding
        return OptionsUpdate(before, after, updated.encodeToByteArray())
    }

    private fun safeResolve(root: Path, relative: Path): Path {
        require(!relative.isAbsolute) { "Install target must be relative" }
        val canonicalRoot = root.toRealPath()
        var current = canonicalRoot
        relative.forEach { segment ->
            val candidate = current.resolve(segment.toString())
            current = if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                candidate.toRealPath()
            } else {
                candidate.normalize()
            }
            require(current.startsWith(canonicalRoot)) { "Install target escapes its context root" }
        }
        return current
    }

    private fun isAllowed(target: Path, allowedRoots: List<Path>): Boolean {
        val normalized = target.toAbsolutePath().normalize()
        return allowedRoots.any { normalized.startsWith(it.toAbsolutePath().normalize()) }
    }

    private fun readLatestState(primaryRoot: Path): TransactionState? {
        val stateRoot = stateRoot(primaryRoot)
        val latest = stateRoot.resolve("latest")
        if (!Files.isRegularFile(latest)) return null
        val id = Files.readString(latest).trim()
        if (id.isBlank() || '/' in id || '\\' in id) return null
        val stateFile = stateRoot.resolve("backups").resolve(id).resolve("state.json")
        if (!Files.isRegularFile(stateFile)) return null
        return CanonicalJson.decode(TransactionState.serializer(), Files.readAllBytes(stateFile))
            .also { require(it.id == id && Path.of(it.primaryRoot).toAbsolutePath().normalize() == primaryRoot.toAbsolutePath().normalize()) }
    }

    private fun stateRoot(primaryRoot: Path): Path = primaryRoot.resolve(".mcfpm")

    private fun <T> withTargetLocks(
        world: Path?,
        instance: Path?,
        block: () -> McfpmResult<T>,
    ): McfpmResult<T> {
        val channels = mutableListOf<FileChannel>()
        val locks = mutableListOf<FileLock>()
        return try {
            if (world != null) {
                val sessionLock = world.resolve("session.lock")
                if (!Files.isRegularFile(sessionLock)) {
                    return failure(DiagnosticCode.INSTALL_TARGET_BUSY, "World is missing session.lock: $world")
                }
                val channel = FileChannel.open(sessionLock, StandardOpenOption.READ, StandardOpenOption.WRITE)
                channels += channel
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: return failure(DiagnosticCode.INSTALL_TARGET_BUSY, "World is currently in use: $world")
                locks += lock
            }
            if (instance != null) {
                val instanceLock = instance.resolve(".mcfpm.lock")
                val channel = FileChannel.open(
                    instanceLock,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
                channels += channel
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: return failure(DiagnosticCode.INSTALL_TARGET_BUSY, "Instance is currently in use: $instance")
                locks += lock
            }
            block()
        } catch (exception: Exception) {
            failure(DiagnosticCode.DEPLOYMENT_FAILED, "Unable to lock install target: ${exception.message}")
        } finally {
            locks.asReversed().forEach { runCatching { it.release() } }
            channels.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun atomicWrite(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.part")
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun <T> failure(
        code: DiagnosticCode,
        message: String,
        context: Map<String, String> = emptyMap(),
    ): McfpmResult<T> = McfpmResult.Failure(
        listOf(Diagnostic(code, DiagnosticSeverity.ERROR, message, context)),
    )

    private fun <T> safely(operation: () -> McfpmResult<T>): McfpmResult<T> = try {
        operation()
    } catch (exception: Exception) {
        failure(DiagnosticCode.DEPLOYMENT_FAILED, "Minecraft operation failed safely: ${exception.message}")
    }

    public fun interface MutationHook {
        public fun beforeMutation(index: Int, target: Path)
    }

    private data class PreparedInstall(
        val plan: MinecraftInstallPlan,
        val mutations: List<Mutation>,
        val primaryRoot: Path,
        val allowedRoots: List<Path>,
    )

    private data class Mutation(
        val target: Path,
        val content: ByteArray?,
        val role: FileRole,
        val expectedExisted: Boolean,
        val expectedSha256: String?,
    )

    private data class Snapshot(
        val mutation: Mutation,
        val existed: Boolean,
        val before: ByteArray?,
        val index: Int,
    )

    private data class OptionsUpdate(
        val before: List<String>,
        val after: List<String>,
        val bytes: ByteArray,
    )

    private enum class FileRole { PAYLOAD, METADATA }

    @Serializable
    private data class TransactionState(
        val schema: Int = 1,
        val id: String,
        val previousTransactionId: String?,
        val primaryRoot: String,
        val allowedRoots: List<String>,
        val worldRoot: String?,
        val instanceRoot: String?,
        val files: List<TransactionFile>,
    )

    @Serializable
    private data class TransactionFile(
        val target: String,
        val role: String,
        val beforeExisted: Boolean,
        val beforeSha256: String?,
        val backupFile: String?,
        val installedSha256: String?,
    )
}

@ConsistentCopyVisibility
private data class MinecraftVersion private constructor(val parts: List<Int>) : Comparable<MinecraftVersion> {
    override fun compareTo(other: MinecraftVersion): Int {
        val length = maxOf(parts.size, other.parts.size)
        repeat(length) { index ->
            val comparison = parts.getOrElse(index) { 0 }.compareTo(other.parts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        fun parse(value: String): MinecraftVersion {
            val parts = value.split('.').map { component ->
                require(component.isNotEmpty() && component.all(Char::isDigit)) { "Invalid Minecraft version: $value" }
                component.toInt()
            }
            return MinecraftVersion(parts)
        }
    }
}
