package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PayloadType
import moe.afox.mcfpm.model.ResolvedGraph

public data class KoreDatapackBinding(
    public val packageId: PackageId,
    public val version: String,
    public val classifier: String,
    public val dependencies: List<PackageId>,
)

public object KoreLockGraphAdapter {
    public fun bindings(graph: ResolvedGraph): List<KoreDatapackBinding> {
        val dependencies = graph.edges.groupBy { it.from }.mapValues { (_, edges) ->
            edges.map { it.to }.distinct().sorted()
        }
        val packages = graph.packages.associateBy { it.packageId }
        return graph.loadOrder
            .filter { it.type == PayloadType.MINECRAFT_DATAPACK }
            .map { reference ->
                val resolvedPackage = packages.getValue(reference.packageId)
                KoreDatapackBinding(
                    packageId = reference.packageId,
                    version = resolvedPackage.version.toString(),
                    classifier = reference.classifier,
                    dependencies = dependencies[reference.packageId].orEmpty(),
                )
            }
    }

    public fun generateKotlin(
        graph: ResolvedGraph,
        packageName: String,
        aliases: Map<PackageId, String> = emptyMap(),
    ): String {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid Kotlin package name: $packageName" }
        val bindings = bindings(graph)
        val names = mutableSetOf<String>()
        val properties = bindings.map { binding ->
            val property = aliases[binding.packageId] ?: binding.packageId.name
                .split('-', '_', '.')
                .filter(String::isNotBlank)
                .mapIndexed { index, part ->
                    if (index == 0) part else part.replaceFirstChar(Char::uppercaseChar)
                }
                .joinToString("")
            require(IDENTIFIER.matches(property) && names.add(property)) {
                "Kore binding alias is invalid or duplicated: $property"
            }
            property to binding
        }
        return buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("/** Generated from mcfpm.lock; contains coordinates only, never machine-local paths. */")
            appendLine("public object KoreDatapacks {")
            properties.forEach { (property, binding) ->
                appendLine("    public val $property: DatapackCoordinate = DatapackCoordinate(")
                appendLine("        id = \"${binding.packageId.value}\",")
                appendLine("        version = \"${binding.version}\",")
                appendLine("        classifier = \"${binding.classifier}\",")
                appendLine("    )")
            }
            appendLine("}")
            appendLine()
            appendLine("public data class DatapackCoordinate(")
            appendLine("    public val id: String,")
            appendLine("    public val version: String,")
            appendLine("    public val classifier: String,")
            appendLine(")")
        }
    }

    private val PACKAGE_NAME: Regex = Regex("[a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*")
    private val IDENTIFIER: Regex = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
}
