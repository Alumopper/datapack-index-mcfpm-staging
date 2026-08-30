package moe.afox.mcfpm.mcfpp

import moe.afox.mcfpm.model.CanonicalJson
import moe.afox.mcfpm.model.Diagnostic
import moe.afox.mcfpm.model.DiagnosticCode
import moe.afox.mcfpm.model.DiagnosticSeverity
import moe.afox.mcfpm.model.McfpmResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

public data class McfppLegacyConfiguration(
    public val includes: List<String>,
    public val jars: List<String>,
) {
    public val isPresent: Boolean
        get() = includes.isNotEmpty() || jars.isNotEmpty()
}

public object McfppLegacyConfigInspector {
    public fun inspect(mcfppJson: Path): McfppLegacyConfiguration {
        val root = CanonicalJson.format.parseToJsonElement(Files.readString(mcfppJson)).jsonObject
        fun strings(key: String): List<String> = root[key]?.jsonArray?.map { it.toString().trim('"') }.orEmpty()
        return McfppLegacyConfiguration(strings("includes"), strings("jars"))
    }

    public fun migrationDiagnostics(configuration: McfppLegacyConfiguration): List<Diagnostic> =
        if (!configuration.isPresent) {
            emptyList()
        } else {
            listOf(
                Diagnostic(
                    DiagnosticCode.INVALID_MANIFEST,
                    DiagnosticSeverity.WARNING,
                    "MCFPP includes/jars are supported for one migration cycle and must be converted to verified Mcfpm payloads before publishing",
                ),
            )
        }

    public fun validateForPublish(configuration: McfppLegacyConfiguration): McfpmResult<Unit> =
        if (!configuration.isPresent) {
            McfpmResult.Success(Unit)
        } else {
            McfpmResult.Failure(
                listOf(
                    Diagnostic(
                        DiagnosticCode.PUBLISH_VALIDATION_FAILED,
                        DiagnosticSeverity.ERROR,
                        "MCFPP includes/jars must be converted to verified Mcfpm payloads before publishing",
                    ),
                ),
            )
        }
}
