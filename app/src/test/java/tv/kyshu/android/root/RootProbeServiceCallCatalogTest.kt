package tv.kyshu.android.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RootProbeServiceCallCatalogTest {
    @Test
    fun `default service call catalog has no enabled transactions`() {
        assertTrue(RootProbeServiceCallCatalog.registeredTransactions().isEmpty())
        assertFalse(
            RootProbeServiceCallCatalog.isAllowlisted(
                listOf("service", "call", "wifi", "1"),
            ),
        )
    }

    @Test
    fun `transaction metadata rejects sensitive services and unsafe fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            RootProbeServiceCallCatalog.Transaction(
                service = "iphonesubinfo",
                code = 1,
                purpose = "Read subscriber information",
                expectedFields = listOf("subscriberId"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootProbeServiceCallCatalog.Transaction(
                service = "wifi",
                code = 0,
                purpose = "Read WiFi field shape",
                expectedFields = listOf("WifiInfo"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootProbeServiceCallCatalog.Transaction(
                service = "wifi",
                code = 1,
                purpose = "Read WiFi field shape",
                expectedFields = listOf("bad field; reboot"),
            )
        }
    }

    @Test
    fun `transaction metadata accepts non-sensitive read-only shape descriptors`() {
        RootProbeServiceCallCatalog.Transaction(
            service = "wifi",
            code = 1,
            purpose = "Read WiFi field shape",
            expectedFields = listOf("WifiInfo.mWifiStandard"),
        )
    }
}
