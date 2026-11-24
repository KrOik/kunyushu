package tv.kyshu.xposed.factory

import android.net.wifi.ScanResult
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.config.MockWifiData
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import java.util.concurrent.ConcurrentHashMap

object WifiScanResultFactory {
    fun createFromSnapshot(wifiList: List<MockWifiData>, maxEntries: Int): List<ScanResult> {
        if (wifiList.isNotEmpty()) {
            return wifiList.take(maxEntries).map { wifi ->
                fromMockWifiOrNull(wifi) ?: throw IllegalStateException("ScanResult construction failed")
            }
        }
        return createFromCurrentConfig(maxEntries)
    }

    private val diagnosticKeys = ConcurrentHashMap.newKeySet<String>()

    fun createFromCurrentConfig(maxEntries: Int): List<ScanResult> {
        val wifiData = FakeLoc.wifiData
        val wifiList = wifiData.mockWifiList
        if (wifiList.isNotEmpty()) {
            return wifiList.take(maxEntries).mapNotNull(::fromMockWifiOrNull)
        }

        val count = wifiData.mockWifiScanCount.coerceIn(0, maxEntries)
        val generatedCount = if (count > 0) count else DEFAULT_FALLBACK_COUNT
        val locationData = FakeLoc.locationData
        return RadioFingerprintGenerator.generate(
            latitude = locationData.latitude,
            longitude = locationData.longitude,
            count = generatedCount,
        ).mapNotNull(::fromGeneratedWifiOrNull)
    }

    fun fromMockWifiOrNull(wifi: MockWifiData): ScanResult? =
        newScanResultOrNull()?.also { result ->
            result.SSID = wifi.ssid
            result.BSSID = wifi.bssid
            result.level = wifi.rssi
            result.frequency = wifi.frequency
        }

    private fun fromGeneratedWifiOrNull(wifi: GeneratedWifiProfile): ScanResult? =
        newScanResultOrNull()?.also { result ->
            result.SSID = wifi.ssid
            result.BSSID = wifi.bssid
            result.level = (wifi.rssiDbm + FakeLoc.wifiData.currentRssiOffset).coerceIn(-95, -25)
            result.frequency = wifi.frequencyMhz
            result.capabilities = wifi.capabilities
        }

    private fun newScanResultOrNull(): ScanResult? =
        runCatching {
            ScanResult()
        }.recoverCatching {
            XposedHelpers.newInstance(ScanResult::class.java) as ScanResult
        }.onFailure {
            logOnce("wifi.scanResult.construct.${it.javaClass.simpleName}", "[WifiScanResultFactory] ScanResult construction failed: ${it.javaClass.simpleName}")
        }.getOrNull()

    private fun logOnce(key: String, message: String) {
        if (diagnosticKeys.add(key)) {
            Logger.info(message)
        }
    }

    private const val DEFAULT_FALLBACK_COUNT = 4
}
