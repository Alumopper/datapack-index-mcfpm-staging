package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.McfpmResult
import java.nio.file.Path

public enum class InstallContextKind {
    PROJECT,
    WORLD,
    INSTANCE,
}

public data class InstallContext(
    public val kind: InstallContextKind,
    public val root: Path,
    public val evidence: List<String>,
    public val inferredInstance: Path? = null,
)

public interface InstallContextDetector {
    public fun detect(
        workingDirectory: Path,
        forcedKind: InstallContextKind? = null,
    ): McfpmResult<InstallContext>

    public fun explicit(kind: InstallContextKind, root: Path): McfpmResult<InstallContext>
}
