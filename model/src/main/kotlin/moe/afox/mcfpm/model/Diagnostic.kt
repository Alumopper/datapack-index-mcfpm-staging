package moe.afox.mcfpm.model

import kotlinx.serialization.Serializable

@Serializable
public enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

@Serializable
public enum class DiagnosticCode(public val stableCode: String) {
    INVALID_ARGUMENT("MCFPM-ARG-001"),
    INVALID_MANIFEST("MCFPM-MANIFEST-001"),
    RESOLUTION_FAILED("MCFPM-RESOLVE-001"),
    DEPENDENCY_CYCLE("MCFPM-RESOLVE-002"),
    REPOSITORY_VIOLATION("MCFPM-REPOSITORY-001"),
    NETWORK_FAILURE("MCFPM-NETWORK-001"),
    OFFLINE_MISS("MCFPM-CACHE-001"),
    INTEGRITY_FAILURE("MCFPM-INTEGRITY-001"),
    UNTRUSTED_EXECUTABLE("MCFPM-TRUST-001"),
    UNKNOWN_INSTALL_CONTEXT("MCFPM-INSTALL-001"),
    AMBIGUOUS_INSTALL_CONTEXT("MCFPM-INSTALL-002"),
    INSTALL_TARGET_BUSY("MCFPM-INSTALL-003"),
    INSTALL_CONFIRMATION_REQUIRED("MCFPM-INSTALL-004"),
    USER_DRIFT("MCFPM-INSTALL-005"),
    PUBLISH_VALIDATION_FAILED("MCFPM-PUBLISH-001"),
    DEPLOYMENT_FAILED("MCFPM-DEPLOY-001"),
    MINECRAFT_FORMAT_MISMATCH("MCFPM-MINECRAFT-001"),
    MCFPP_INTEGRATION_FAILED("MCFPM-MCFPP-001"),
    INTERNAL_ERROR("MCFPM-INTERNAL-001"),
}

@Serializable
public data class Diagnostic(
    public val code: DiagnosticCode,
    public val severity: DiagnosticSeverity,
    public val message: String,
    public val context: Map<String, String> = emptyMap(),
)

public sealed interface McfpmResult<out T> {
    public data class Success<T>(public val value: T) : McfpmResult<T>

    public data class Failure(public val diagnostics: List<Diagnostic>) : McfpmResult<Nothing> {
        init {
            require(diagnostics.isNotEmpty()) { "A failed result requires at least one diagnostic" }
        }
    }
}
