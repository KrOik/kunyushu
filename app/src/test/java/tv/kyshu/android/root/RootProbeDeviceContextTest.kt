package tv.kyshu.android.root

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.kyshu.BuildConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RootProbeDeviceContextTest {
    @Test
    fun `captures non unique device context permissions and feature flags`() {
        val context = RuntimeEnvironment.getApplication()

        val deviceContext = RootProbeDeviceContext.capture(
            context = context,
            moduleScope = "system-framework",
            targetProcess = "system_server",
        )

        assertEquals(33, deviceContext.sdk)
        assertEquals("system-framework", deviceContext.moduleScope)
        assertEquals("system_server", deviceContext.targetProcess)
        assertTrue(deviceContext.permissions.containsKey(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(deviceContext.permissions.containsKey(Manifest.permission.BLUETOOTH_SCAN))
        assertEquals(BuildConfig.ENABLE_WIFI_MOCK, deviceContext.featureFlags["ENABLE_WIFI_MOCK"])
        assertEquals(BuildConfig.ENABLE_CELL_MOCK, deviceContext.featureFlags["ENABLE_CELL_MOCK"])
        assertFalse(deviceContext.toString().contains("fingerprint", ignoreCase = true))
        assertFalse(deviceContext.toString().contains("serial", ignoreCase = true))
    }
}
