package tv.kyshu.xposed.hooks.bluetooth

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.kyshu.xposed.BuildConfig
import tv.kyshu.xposed.config.BleDeviceProfile
import tv.kyshu.xposed.utils.FakeLoc

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BluetoothHookRuntimeTest {
    @Before
    fun setUp() {
        BluetoothHook.resetForTest()
        FakeLoc.enable = true
        FakeLoc.enableBleMock = true
        FakeLoc.bleDevices = emptyList()
    }

    @Test
    fun `dispatches configured BLE profiles through batch and single scan callbacks`() {
        FakeLoc.bleDevices = listOf(
            BleDeviceProfile(
                address = "C0:AA:BB:CC:DD:EF",
                name = "PortalBand",
                rssiDbm = -61,
                serviceUuids = listOf("0000180D-0000-1000-8000-00805F9B34FB"),
                manufacturerDataHex = "4C000215",
                serviceDataHex = "0102",
                connectable = true,
            ),
        )
        val callback = CapturingScanCallback()

        val delivered = BluetoothHook.dispatchProfileScanResultsForTest(callback, timestampNanos = 123L)

        if (!BuildConfig.ENABLE_BLE_MOCK) {
            assertEquals(0, delivered)
            assertTrue(callback.batchResults.isEmpty())
            assertTrue(callback.singleResults.isEmpty())
            return
        }

        assertEquals(1, delivered)
        assertEquals(1, callback.batchResults.single().size)
        assertEquals("C0:AA:BB:CC:DD:EF", callback.batchResults.single().single().device.address)
        assertEquals(-61, callback.singleResults.single().rssi)
        assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, callback.singleCallbackTypes.single())
    }

    @Test
    fun `builds bonded and connected device lists from explicit BLE profile flags`() {
        FakeLoc.bleDevices = listOf(
            BleDeviceProfile(
                address = "C0:AA:BB:CC:DD:F0",
                bonded = true,
                connected = false,
            ),
            BleDeviceProfile(
                address = "C0:AA:BB:CC:DD:F1",
                bonded = false,
                connected = true,
            ),
            BleDeviceProfile(
                address = "C0:AA:BB:CC:DD:F2",
                bonded = true,
                connected = true,
            ),
        )

        val bonded = BluetoothHook.profileBondedDevices().map { it.address }.toSet()
        val connected = BluetoothHook.profileConnectedDevices().map { it.address }

        if (!BuildConfig.ENABLE_BLE_MOCK) {
            assertTrue(bonded.isEmpty())
            assertTrue(connected.isEmpty())
            return
        }

        assertEquals(setOf("C0:AA:BB:CC:DD:F0", "C0:AA:BB:CC:DD:F2"), bonded)
        assertEquals(listOf("C0:AA:BB:CC:DD:F1", "C0:AA:BB:CC:DD:F2"), connected)
    }

    private class CapturingScanCallback : ScanCallback() {
        val batchResults = mutableListOf<List<ScanResult>>()
        val singleResults = mutableListOf<ScanResult>()
        val singleCallbackTypes = mutableListOf<Int>()

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            batchResults += results.toList()
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            singleCallbackTypes += callbackType
            singleResults += result
        }
    }
}
