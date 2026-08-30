package moe.afox.mcfpm.source.github

import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.VersionRequirement
import java.nio.charset.StandardCharsets
import org.tomlj.Toml
import org.tomlj.TomlTable

public object GitHubImportRecipeCodec {
    public fun decode(bytes: ByteArray): GitHubImportRecipe = decode(bytes.toString(StandardCharsets.UTF_8))

    public fun decode(text: String): GitHubImportRecipe {
        val document = Toml.parse(text)
        require(!document.hasErrors()) { document.errors().joinToString("; ") }
        requireOnlyKeys(document, setOf("schema", "source", "package", "repository", "dependencies"), "document")
        val source = document.requireTable("source")
        val packageTable = document.requireTable("package")
        val repository = document.requireTable("repository")
        requireOnlyKeys(
            source,
            setOf("repository", "mode", "asset", "subdir", "nested-zip", "github-token-env"),
            "source",
        )
        requireOnlyKeys(packageTable, setOf("id", "license", "type", "classifier", "minecraft"), "package")
        requireOnlyKeys(repository, setOf("id"), "repository")
        val tokenEnvironment = source.getString("github-token-env") ?: "GITHUB_TOKEN"
        require(ENVIRONMENT.matches(tokenEnvironment)) { "Invalid GitHub token environment variable: $tokenEnvironment" }

        val dependencies = document.getTable("dependencies")
            ?.keySet()
            .orEmpty()
            .sorted()
            .associate { id ->
                val value = document.requireTable("dependencies").get(quote(id))
                val requirement = when (value) {
                    is String -> value
                    is TomlTable -> {
                        requireOnlyKeys(value, setOf("version"), "dependencies.$id")
                        value.requireString("version")
                    }
                    else -> throw IllegalArgumentException("Dependency $id must be a version string or inline table")
                }
                PackageId.parse(id) to VersionRequirement.parse(requirement)
            }

        return GitHubImportRecipe(
            schema = document.requireLong("schema").toInt(),
            source = RecipeSource(
                repository = GitHubRepository.parse(source.requireString("repository")),
                mode = parseMode(source.getString("mode") ?: "release-asset"),
                asset = source.getString("asset"),
                subdirectory = source.getString("subdir"),
                nestedZip = source.getString("nested-zip"),
                githubTokenEnvironment = tokenEnvironment,
            ),
            packageConfiguration = RecipePackage(
                packageId = packageTable.getString("id")?.let(PackageId::parse),
                license = packageTable.getString("license"),
                type = packageTable.getString("type")?.let(PayloadType::parse),
                classifier = packageTable.getString("classifier"),
                minecraft = packageTable.getString("minecraft"),
            ),
            repository = RecipeRepository(repository.requireString("id")),
            dependencies = dependencies,
        )
    }

    public fun encode(recipe: GitHubImportRecipe): ByteArray = buildString {
        appendLine("schema = ${recipe.schema}")
        appendLine()
        appendLine("[source]")
        appendLine("repository = ${quote(recipe.source.repository.slug)}")
        appendLine("mode = ${quote(wireMode(recipe.source.mode))}")
        recipe.source.asset?.let { appendLine("asset = ${quote(it)}") }
        recipe.source.subdirectory?.let { appendLine("subdir = ${quote(it)}") }
        recipe.source.nestedZip?.let { appendLine("nested-zip = ${quote(it)}") }
        appendLine("github-token-env = ${quote(recipe.source.githubTokenEnvironment)}")
        appendLine()
        appendLine("[package]")
        recipe.packageConfiguration.packageId?.let { appendLine("id = ${quote(it.value)}") }
        recipe.packageConfiguration.license?.let { appendLine("license = ${quote(it)}") }
        recipe.packageConfiguration.type?.let { appendLine("type = ${quote(it.value)}") }
        recipe.packageConfiguration.classifier?.let { appendLine("classifier = ${quote(it)}") }
        recipe.packageConfiguration.minecraft?.let { appendLine("minecraft = ${quote(it)}") }
        appendLine()
        appendLine("[repository]")
        appendLine("id = ${quote(recipe.repository.id)}")
        if (recipe.dependencies.isNotEmpty()) {
            appendLine()
            appendLine("[dependencies]")
            recipe.dependencies.toSortedMap().forEach { (id, requirement) ->
                appendLine("${quote(id.value)} = { version = ${quote(requirement.expression)} }")
            }
        }
    }.toByteArray(StandardCharsets.UTF_8)

    private fun parseMode(value: String): GitHubSourceMode = when (value) {
        "release-asset" -> GitHubSourceMode.RELEASE_ASSET
        "archive" -> GitHubSourceMode.ARCHIVE
        else -> throw IllegalArgumentException("Unknown GitHub source mode: $value")
    }

    private fun wireMode(mode: GitHubSourceMode): String = when (mode) {
        GitHubSourceMode.RELEASE_ASSET -> "release-asset"
        GitHubSourceMode.ARCHIVE -> "archive"
    }

    private fun requireOnlyKeys(table: TomlTable, allowed: Set<String>, context: String) {
        val unknown = table.keySet() - allowed
        require(unknown.isEmpty()) { "Unknown keys in $context: ${unknown.sorted().joinToString()}" }
    }

    private fun TomlTable.requireString(key: String): String =
        getString(key) ?: throw IllegalArgumentException("Missing or non-string TOML key: $key")

    private fun TomlTable.requireLong(key: String): Long =
        getLong(key) ?: throw IllegalArgumentException("Missing or non-integer TOML key: $key")

    private fun TomlTable.requireTable(key: String): TomlTable =
        getTable(key) ?: throw IllegalArgumentException("Missing or non-table TOML key: $key")

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    require(character.code >= 0x20 && character != '\u007f') { "Control character in TOML string" }
                    append(character)
                }
            }
        }
        append('"')
    }

    private val ENVIRONMENT: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")
}

public object GitHubImportLockCodec {
    public fun encode(lock: GitHubImportLock): ByteArray = CanonicalJson.encode(GitHubImportLock.serializer(), lock)

    public fun decode(bytes: ByteArray): GitHubImportLock = CanonicalJson.decode(GitHubImportLock.serializer(), bytes)
}
