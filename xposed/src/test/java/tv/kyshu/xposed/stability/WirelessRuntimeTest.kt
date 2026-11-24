package tv.kyshu.xposed.stability

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.kyshu.xposed.config.BleDeviceProfile
import tv.kyshu.xposed.config.CellTowerProfile
import tv.kyshu.xposed.config.DomainSwitch
import tv.kyshu.xposed.config.MockDomain
import tv.kyshu.xposed.config.MockDomainConfig
import tv.kyshu.xposed.config.MockMode
import tv.kyshu.xposed.config.WifiScanEntry
import tv.kyshu.xposed.config.WifiScanProfile

@RunWith(RobolectricTestRunner::class)
class WirelessRuntimeTest {
    @Before fun setUp() = WirelessRuntime.resetForTest()
    @After fun tearDown() {
        WirelessRuntime.resetForTest()
        DomainGuard.resetAll()
    }

    @Test fun `malformed scan preserves prior known-good domain`() {
        val good = config(MockDomain.WLAN_SCAN).copy(wifiScan = WifiScanProfile(
            listOf(WifiScanEntry("test-network", "02:00:00:00:00:01", -60, 2412)), 8))
        val first = WirelessRuntime.load(good).snapshot

        val bad = good.copy(wifiScan = WifiScanProfile(
            listOf(WifiScanEntry("bad-network", "not-a-mac", -60, 2412)), 8))
        val result = WirelessRuntime.load(bad)

        assertTrue(MockDomain.WLAN_SCAN in result.rejectedDomains)
        assertEquals(first.wifiScan, result.snapshot.wifiScan)
        assertTrue(result.snapshot.generation > first.generation)
    }

    @Test fun `explicit off commits disabled empty state`() {
        WirelessRuntime.load(config(MockDomain.CELL).copy(cellTowers = listOf(
            CellTowerProfile(mcc = "460", mnc = "01", cellId = 1))))

        val off = WirelessRuntime.load(MockDomainConfig()).snapshot

        assertFalse(off.cellEnabled)
        assertTrue(off.cellTowers.isEmpty())
    }

    @Test fun `invalid domain without known-good remains passthrough`() {
        val bad = config(MockDomain.BLE).copy(bleDevices = listOf(BleDeviceProfile(address = "invalid")))
        val result = WirelessRuntime.load(bad)
        assertFalse(result.snapshot.bleEnabled)
        assertTrue(result.snapshot.bleDevices.isEmpty())
    }

    @Test fun `safe replacement preserves exact original and trips on first failure`() {
        val original = Any()
        val value = replaceOrPassthrough(DomainGuard.Domain.WLAN, "fault-test", original) {
            throw IllegalStateException("injected")
        }
        assertSame(original, value)
        assertTrue(DomainGuard.isDomainDisabled(DomainGuard.Domain.WLAN))
    }

    private fun config(vararg enabled: MockDomain): MockDomainConfig {
        val active = enabled.toSet()
        return MockDomainConfig(switches = MockDomain.entries.associateWith { domain ->
            DomainSwitch(domain, runtimeEnabled = domain in active,
                mode = if (domain in active) MockMode.REPLACE_WITH_PROFILE else MockMode.OFF)
        })
    }
}
