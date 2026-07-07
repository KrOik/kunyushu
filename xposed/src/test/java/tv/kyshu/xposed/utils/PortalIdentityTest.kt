package tv.kyshu.xposed.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class PortalIdentityTest {
    @Test
    fun `runtime version summary includes module identity`() {
        val summary = PortalIdentity.runtimeVersionSummary()

        assertTrue(summary.contains("applicationId=tv.kyshu"))
        assertTrue(summary.contains("identity=1.0.8(10008)"))
        assertTrue(summary.contains("installed=unavailable"))
    }
}
