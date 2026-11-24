package tv.kyshu.xposed.stability

import tv.kyshu.xposed.BuildConfig
import tv.kyshu.xposed.config.BleDeviceProfile
import tv.kyshu.xposed.config.CellTowerProfile
import tv.kyshu.xposed.config.MockDomain
import tv.kyshu.xposed.config.MockDomainConfig
import tv.kyshu.xposed.config.MockMode
import tv.kyshu.xposed.config.MockWifiData
import tv.kyshu.xposed.config.WifiConnectedProfile
import tv.kyshu.xposed.utils.Log
import tv.kyshu.xposed.utils.FakeLoc
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Immutable, callback-safe view of all wireless replay configuration. */
data class WirelessRuntimeSnapshot(
    val generation: Long = 0,
    val wifiConnectedEnabled: Boolean = false,
    val wifiScanEnabled: Boolean = false,
    val wifiConnected: WifiConnectedProfile? = null,
    val wifiScan: List<MockWifiData> = emptyList(),
    val wifiScanLimit: Int = 0,
    val cellEnabled: Boolean = false,
    val cellTowers: List<CellTowerProfile> = emptyList(),
    val bleEnabled: Boolean = false,
    val bleDevices: List<BleDeviceProfile> = emptyList(),
)

data class WirelessLoadResult(
    val snapshot: WirelessRuntimeSnapshot,
    val rejectedDomains: Set<MockDomain>,
)

object WirelessRuntime {
    private val generation = AtomicLong(0)
    private val current = AtomicReference(WirelessRuntimeSnapshot())

    fun snapshot(): WirelessRuntimeSnapshot = current.get()

    /** Immutable compatibility snapshot until the first schema-v3 domain config arrives. */
    fun snapshotOrLegacy(): WirelessRuntimeSnapshot {
        val snapshot = current.get()
        if (snapshot.generation != 0L) return snapshot
        return WirelessRuntimeSnapshot(
            wifiConnectedEnabled = BuildConfig.ENABLE_WIFI_MOCK && FakeLoc.enableMockWifi,
            wifiScanEnabled = BuildConfig.ENABLE_WIFI_MOCK && FakeLoc.enableMockWifi && !FakeLoc.disableWifiScan,
            wifiConnected = WifiConnectedProfile(FakeLoc.mockSSID, FakeLoc.mockBSSID, FakeLoc.mockMac, FakeLoc.mockIp),
            wifiScan = FakeLoc.mockWifiList.toList(),
            wifiScanLimit = FakeLoc.mockWifiScanCount.coerceIn(0, 32),
            cellEnabled = BuildConfig.ENABLE_CELL_MOCK && FakeLoc.cellTowers.isNotEmpty(),
            cellTowers = FakeLoc.cellTowers.toList(),
            bleEnabled = BuildConfig.ENABLE_BLE_MOCK && FakeLoc.enableBleMock,
            bleDevices = FakeLoc.bleDevices.toList(),
        )
    }

    /** Validate each domain independently and atomically publish one complete snapshot. */
    @Synchronized
    fun load(config: MockDomainConfig): WirelessLoadResult {
        val previous = current.get()
        val issues = config.validate()
        val rejected = issues.map { it.domain }.filterTo(mutableSetOf()) {
            it == MockDomain.WLAN_CONNECTED || it == MockDomain.WLAN_SCAN ||
                it == MockDomain.CELL || it == MockDomain.BLE
        }

        fun active(domain: MockDomain): Boolean {
            val switch = config.switches[domain] ?: return false
            return switch.runtimeEnabled && switch.mode != MockMode.OFF &&
                !switch.killSwitch && !switch.domainGuardDisabled
        }

        val connectedActive = BuildConfig.ENABLE_WIFI_MOCK && active(MockDomain.WLAN_CONNECTED)
        val scanActive = BuildConfig.ENABLE_WIFI_MOCK && active(MockDomain.WLAN_SCAN)
        val cellActive = BuildConfig.ENABLE_CELL_MOCK &&
            config.switches[MockDomain.CELL]?.mode == MockMode.REPLACE_WITH_PROFILE && active(MockDomain.CELL)
        val bleActive = BuildConfig.ENABLE_BLE_MOCK && active(MockDomain.BLE)

        val nextGeneration = generation.incrementAndGet()
        val next = WirelessRuntimeSnapshot(
            generation = nextGeneration,
            wifiConnectedEnabled = when {
                !connectedActive -> false
                MockDomain.WLAN_CONNECTED in rejected -> previous.wifiConnectedEnabled
                else -> true
            },
            wifiConnected = when {
                !connectedActive -> null
                MockDomain.WLAN_CONNECTED in rejected -> previous.wifiConnected
                else -> config.wifiConnected.copy()
            },
            wifiScanEnabled = when {
                !scanActive -> false
                MockDomain.WLAN_SCAN in rejected -> previous.wifiScanEnabled
                else -> true
            },
            wifiScan = when {
                !scanActive -> emptyList()
                MockDomain.WLAN_SCAN in rejected -> previous.wifiScan
                else -> config.wifiScan.entries.take(32).map { entry ->
                    MockWifiData(entry.ssid, entry.bssid, entry.rssiDbm, entry.frequencyMhz,
                        entry.channelWidth, entry.capabilities, entry.standard.toIntOrNull() ?: 0)
                }
            }.toList(),
            wifiScanLimit = when {
                !scanActive -> 0
                MockDomain.WLAN_SCAN in rejected -> previous.wifiScanLimit
                else -> config.wifiScan.maxEntries.coerceIn(0, 32)
            },
            cellEnabled = when {
                !cellActive -> false
                MockDomain.CELL in rejected -> previous.cellEnabled
                else -> config.cellTowers.isNotEmpty()
            },
            cellTowers = when {
                !cellActive -> emptyList()
                MockDomain.CELL in rejected -> previous.cellTowers
                else -> config.cellTowers.take(32)
            }.toList(),
            bleEnabled = when {
                !bleActive -> false
                MockDomain.BLE in rejected -> previous.bleEnabled
                else -> config.bleDevices.isNotEmpty()
            },
            bleDevices = when {
                !bleActive -> emptyList()
                MockDomain.BLE in rejected -> previous.bleDevices
                else -> config.bleDevices.take(32)
            }.toList(),
        )
        current.set(next)
        Log.telemetry("WIRELESS_CONFIG", if (rejected.isEmpty()) "CONFIG_COMMITTED" else "CONFIG_REJECTED",
            "wireless snapshot published", mapOf("generation" to nextGeneration, "rejected_count" to rejected.size,
                "wifi_count" to next.wifiScan.size, "cell_count" to next.cellTowers.size, "ble_count" to next.bleDevices.size))
        return WirelessLoadResult(next, rejected)
    }

    internal fun resetForTest() {
        generation.set(0)
        current.set(WirelessRuntimeSnapshot())
    }
}
