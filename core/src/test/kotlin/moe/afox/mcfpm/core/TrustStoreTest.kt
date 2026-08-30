package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.PackageId
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustStoreTest {
    @Test
    fun `trust is exact and persists without becoming transitive`() {
        val path = createTempDirectory("mcfpm-trust-test").resolve("trust.toml")
        val packageId = PackageId.parse("test:plugin")
        val dependencyId = PackageId.parse("test:dependency")
        val fingerprint = "a".repeat(64)
        val store = FileTrustStore(path)
        store.add(TrustGrant(packageId, fingerprint))

        val reloaded = FileTrustStore(path)
        assertTrue(reloaded.isTrusted(packageId, fingerprint))
        assertFalse(reloaded.isTrusted(dependencyId, fingerprint))
        assertFalse(reloaded.isTrusted(packageId, "b".repeat(64)))
    }
}
