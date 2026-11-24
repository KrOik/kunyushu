package tv.kyshu.xposed.hooks.telephony

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.kyshu.xposed.utils.FakeLoc

class TelephonyHookRuntimeGateTest {
    @After
    fun tearDown() {
        FakeLoc.enable = false
        FakeLoc.needDowngradeToCdma = true
    }

    @Test
    fun `CDMA downgrade requires global mock and downgrade switches`() {
        FakeLoc.enable = false
        FakeLoc.needDowngradeToCdma = true
        assertFalse(TelephonyDowngradeGate.shouldDowngradeToCdma())

        FakeLoc.enable = true
        FakeLoc.needDowngradeToCdma = false
        assertFalse(TelephonyDowngradeGate.shouldDowngradeToCdma())

        FakeLoc.enable = true
        FakeLoc.needDowngradeToCdma = true
        assertTrue(TelephonyDowngradeGate.shouldDowngradeToCdma())
    }
}
