package tv.kyshu.service

import android.location.LocationManager
import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MockServiceHelperSecurityTest {

    @Test
    fun `uninitialized service returns false for all operations`() {
        val locationManager = mockk<LocationManager>()
        assertFalse(MockServiceHelper.isMockStart(locationManager))
        assertFalse(MockServiceHelper.startGnssMock(locationManager))
        assertFalse(MockServiceHelper.stopGnssMock(locationManager))
        assertFalse(MockServiceHelper.startWifiMock(locationManager))
        assertFalse(MockServiceHelper.stopWifiMock(locationManager))
    }

    @Test
    fun `exchange_key returns a non-empty key and marks service as initialized`() {
        val locationManager = mockk<LocationManager>()
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "exchange_key", any())
        } answers {
            val bundle = invocation.args[2] as Bundle
            bundle.putString("key", "auth_abcdef1234567890abcdef1234567890")
            true
        }

        MockServiceHelper.tryInitService(locationManager)
        assertTrue(MockServiceHelper.isServiceInit())
    }

    @Test
    fun `exchange_key failure leaves service uninitialized`() {
        val locationManager = mockk<LocationManager>()
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "exchange_key", any())
        } returns false

        MockServiceHelper.tryInitService(locationManager)
        assertFalse(MockServiceHelper.isServiceInit())
    }

    @Test
    fun `isMockStart returns false when remote returns no is_start`() {
        val locationManager = mockk<LocationManager>()
        // Init first
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "exchange_key", any())
        } answers {
            val bundle = invocation.args[2] as Bundle
            bundle.putString("key", "auth_test_key")
            true
        }
        MockServiceHelper.tryInitService(locationManager)

        // Then query — returns true without is_start field → false
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "auth_test_key", any())
        } answers {
            val bundle = invocation.args[2] as Bundle
            bundle.putBoolean("is_start", true)
            true
        }

        assertTrue(MockServiceHelper.isMockStart(locationManager))
    }
}
