package moe.afox.mcfpm.publish.central

import moe.afox.mcfpm.core.ReproducibleZip
import moe.afox.mcfpm.model.McfpmResult
import kotlin.test.Test
import kotlin.test.assertIs

class CentralBundleInspectorTest {
    @Test
    fun `rejects a zip that omits signatures and checksum sidecars`() {
        val bytes = ReproducibleZip.fromEntries(
            listOf(
                "example/package/1.0.0/package-1.0.0.pom" to "pom".encodeToByteArray(),
                "example/package/1.0.0/package-1.0.0.mcfpkg" to "{}".encodeToByteArray(),
                "example/package/1.0.0/package-1.0.0-datapack.zip" to byteArrayOf(1),
            ),
        )

        assertIs<McfpmResult.Failure>(CentralBundleInspector.validate(bytes))
    }
}
