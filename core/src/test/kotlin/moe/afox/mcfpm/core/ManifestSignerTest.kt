package moe.afox.mcfpm.core

import moe.afox.mcfpm.model.PackageId
import moe.afox.mcfpm.model.PackageManifest
import moe.afox.mcfpm.model.SemVer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManifestSignerTest {
    @Test
    fun `Ed25519 signature covers canonical descriptor content`() {
        val unsigned = PackageManifest(
            packageId = PackageId.parse("test:signed"),
            version = SemVer.parse("1.0.0"),
            license = "Apache-2.0",
        )
        val signed = ManifestSigner.sign(unsigned, ManifestSigner.generateKeyPair())

        assertNotNull(signed.signatureFingerprint)
        assertTrue(ManifestSigner.verify(signed))
        assertFalse(ManifestSigner.verify(signed.copy(license = "MIT")))
    }

    @Test
    fun `unsigned non-executable descriptor remains valid`() {
        val manifest = PackageManifest(
            packageId = PackageId.parse("test:unsigned"),
            version = SemVer.parse("1.0.0"),
            license = "Apache-2.0",
        )

        assertTrue(ManifestSigner.verify(manifest))
        assertFalse(ManifestSigner.isSigned(manifest))
    }
}
