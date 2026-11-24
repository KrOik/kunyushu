package tv.kyshu.service

import android.content.Context
import android.os.Bundle
import androidx.core.content.edit
import tv.kyshu.BuildConfig
import tv.kyshu.ext.sharedPrefs
import tv.kyshu.ui.mock.HistoricalLocation
import tv.kyshu.xposed.MockCommandContract
import tv.kyshu.xposed.config.DomainSwitch
import tv.kyshu.xposed.config.MockDomain
import tv.kyshu.xposed.config.MockDomainConfig
import tv.kyshu.xposed.config.MockDomainConfigJson
import tv.kyshu.xposed.config.MockMode
import tv.kyshu.xposed.config.WifiConnectedProfile
import tv.kyshu.xposed.config.WifiScanProfile
import tv.kyshu.xposed.utils.FakeLoc

object MockDomainConfigPrefs {
    const val PREF_KEY = "mockDomainConfigJson"

    fun exportJson(context: Context, persist: Boolean = true): String {
        val scalarConfig = MockDomainConfigJson
            .importJson(MockCommandContract.exportDomainConfigJson(toCommandBundle(context)), legalize = true)
        val merged = mergeUnsupportedProfiles(
            scalarConfig = scalarConfig,
            storedConfig = storedConfig(context),
        ).legalize()
        val json = MockDomainConfigJson.exportJson(merged)
        if (persist) {
            context.sharedPrefs.edit { putString(PREF_KEY, json) }
        }
        return json
    }

    fun importJson(context: Context, json: String): String {
        val config = MockDomainConfigJson.importValidatedJson(json, legalize = true)
        val legalizedJson = MockDomainConfigJson.exportJson(config)
        applyToPrefs(context, config)
        context.sharedPrefs.edit { putString(PREF_KEY, legalizedJson) }
        return legalizedJson
    }

    fun toCommandBundle(context: Context): Bundle {
        val prefs = context.sharedPrefs
        val selectedLocation = selectedLocationOrNull(context)
        return Bundle().apply {
            putBoolean(MockCommandContract.Keys.ENABLE, prefs.getBoolean("enable", false))
            selectedLocation?.let { location ->
                putDouble(MockCommandContract.Keys.LATITUDE, location.lat)
                putDouble(MockCommandContract.Keys.LONGITUDE, location.lon)
            }
            putDouble(MockCommandContract.Keys.ALTITUDE, prefs.getFloat("altitude", FakeLoc.altitude.toFloat()).toDouble())
            putDouble(MockCommandContract.Keys.SPEED, prefs.getFloat("speed", FakeLoc.speed.toFloat()).toDouble())
            putFloat(MockCommandContract.Keys.ACCURACY, prefs.getFloat("accuracy", FakeLoc.accuracy))
            putDouble(MockCommandContract.Keys.BEARING, FakeLoc.bearing)
            putBoolean(MockCommandContract.Keys.ENABLE_MOCK_GNSS, prefs.getBoolean("enableMockGnss", FakeLoc.enableMockGnss))
            putBoolean(MockCommandContract.Keys.ENABLE_NMEA, prefs.getBoolean("enableNMEA", FakeLoc.enableNMEA))
            putBoolean(MockCommandContract.Keys.ENABLE_AGPS, prefs.getBoolean("enableAGPS", FakeLoc.enableAGPS))
            putInt(MockCommandContract.Keys.MIN_SATELLITES, prefs.getInt("minSatelliteCount", FakeLoc.minSatellites))
            if (BuildConfig.ENABLE_WIFI_MOCK) {
                putBoolean(MockCommandContract.Keys.ENABLE_MOCK_WIFI, prefs.getBoolean("enableMockWifi", FakeLoc.enableMockWifi))
                putBoolean(MockCommandContract.Keys.HOOK_WIFI, prefs.getBoolean("hookWifi", FakeLoc.hookWifi))
                putBoolean(MockCommandContract.Keys.DISABLE_WIFI_SCAN, prefs.getBoolean("disableWifiScan", FakeLoc.disableWifiScan))
                putString(MockCommandContract.Keys.MOCK_SSID, prefs.getString("mockSSID", FakeLoc.mockSSID).orEmpty())
                putString(MockCommandContract.Keys.MOCK_BSSID, prefs.getString("mockBSSID", FakeLoc.mockBSSID).orEmpty())
                putString(MockCommandContract.Keys.MOCK_MAC, prefs.getString("mockMac", FakeLoc.mockMac).orEmpty())
                putString(MockCommandContract.Keys.MOCK_IP, prefs.getString("mockIp", FakeLoc.mockIp).orEmpty())
                putInt(MockCommandContract.Keys.MOCK_WIFI_SCAN_COUNT, prefs.getInt("mockWifiScanCount", FakeLoc.mockWifiScanCount))
            } else {
                putBoolean(MockCommandContract.Keys.ENABLE_MOCK_WIFI, false)
                putBoolean(MockCommandContract.Keys.HOOK_WIFI, false)
                putBoolean(MockCommandContract.Keys.DISABLE_WIFI_SCAN, false)
            }
            val hookSensor = prefs.getBoolean("hookSensor", false)
            val stepMock = prefs.getBoolean("enableStepCountMock", FakeLoc.enableStepCountMock)
            val shakeMock = prefs.getBoolean("enableShakeMock", FakeLoc.enableShakeMock)
            putBoolean(MockCommandContract.Keys.HOOK_SENSOR, hookSensor || stepMock || shakeMock)
            putBoolean(MockCommandContract.Keys.ENABLE_STEP_COUNT_MOCK, stepMock)
            putBoolean(MockCommandContract.Keys.ENABLE_SHAKE_MOCK, shakeMock)
            putBoolean(
                MockCommandContract.Keys.ENABLE_MOTION_MOCK,
                prefs.getBoolean("enableMotionMock", prefs.getBoolean("hookSensor", FakeLoc.sensorData.enableMotionMock)),
            )
            putBoolean(MockCommandContract.Keys.HIDE_BASE_STATION, prefs.getBoolean("hideBaseStation", FakeLoc.hideBaseStation))
            putBoolean(MockCommandContract.Keys.NEED_DOWNGRADE_TO_2G, prefs.getBoolean("needDowngradeToCdma", FakeLoc.needDowngradeToCdma))
            putBoolean(MockCommandContract.Keys.HIDE_MOCK, prefs.getBoolean("hideMock", FakeLoc.locationData.hideMock))
        }
    }

    fun selectedLocationOrNull(context: Context): HistoricalLocation? {
        val raw = context.sharedPrefs.getString("selectedLocation", null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { HistoricalLocation.fromString(raw) }.getOrNull()
    }

    private fun storedConfig(context: Context): MockDomainConfig? {
        val json = context.sharedPrefs.getString(PREF_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { MockDomainConfigJson.importJson(json, legalize = true) }.getOrNull()
    }

    private fun mergeUnsupportedProfiles(
        scalarConfig: MockDomainConfig,
        storedConfig: MockDomainConfig?,
    ): MockDomainConfig {
        if (storedConfig == null) return scalarConfig
        return scalarConfig.copy(
            wifiConnected = mergeWifiConnected(scalarConfig.wifiConnected, storedConfig.wifiConnected),
            wifiScan = mergeWifiScan(scalarConfig.wifiScan, storedConfig.wifiScan),
            cellTowers = storedConfig.cellTowers,
            bleDevices = storedConfig.bleDevices,
            nfcReplays = storedConfig.nfcReplays,
            coordinateOverrides = storedConfig.coordinateOverrides,
            motionNoise = storedConfig.motionNoise,
            evidenceRefs = storedConfig.evidenceRefs,
        )
    }

    private fun mergeWifiConnected(
        scalar: WifiConnectedProfile,
        stored: WifiConnectedProfile,
    ): WifiConnectedProfile = stored.copy(
        ssid = scalar.ssid,
        bssid = scalar.bssid,
        macAddress = scalar.macAddress,
        ipv4Address = scalar.ipv4Address,
    )

    private fun mergeWifiScan(
        scalar: WifiScanProfile,
        stored: WifiScanProfile,
    ): WifiScanProfile = stored.copy(maxEntries = scalar.maxEntries)

    private fun applyToPrefs(context: Context, config: MockDomainConfig) {
        val locationEnabled = config.isRuntimeEnabled(MockDomain.LOCATION)
        val gnssEnabled = config.isRuntimeEnabled(MockDomain.GNSS)
        val nmeaEnabled = config.isRuntimeEnabled(MockDomain.NMEA) || config.nmea.enabled
        val sensorEnabled = config.isRuntimeEnabled(MockDomain.SENSOR)
        val wifiConnectedEnabled = config.isRuntimeEnabled(MockDomain.WLAN_CONNECTED)
        val wifiScanEnabled = config.isRuntimeEnabled(MockDomain.WLAN_SCAN)
        val cellSwitch = config.switches[MockDomain.CELL]

        context.sharedPrefs.edit {
            putBoolean("enable", locationEnabled)
            putString(
                "selectedLocation",
                HistoricalLocation(
                    name = "Domain Config",
                    address = "Imported",
                    lat = config.location.latitude,
                    lon = config.location.longitude,
                ).toString(),
            )
            putFloat("altitude", config.location.altitudeMeters.toFloat())
            putFloat("speed", config.location.speedMetersPerSecond)
            putFloat("accuracy", config.location.horizontalAccuracyMeters)
            putBoolean("hideMock", !config.location.preserveMockFlag)
            putBoolean("enableMockGnss", gnssEnabled)
            putBoolean("enableNMEA", nmeaEnabled)
            putInt("minSatelliteCount", config.gnss.minSatelliteCount)
            putBoolean("hookSensor", sensorEnabled)
            if (BuildConfig.ENABLE_WIFI_MOCK) {
                putBoolean("enableMockWifi", wifiConnectedEnabled || wifiScanEnabled)
                putBoolean("hookWifi", wifiConnectedEnabled)
                putBoolean("disableWifiScan", !wifiScanEnabled)
                putString("mockSSID", config.wifiConnected.ssid)
                putString("mockBSSID", config.wifiConnected.bssid)
                putString("mockMac", config.wifiConnected.macAddress)
                putString("mockIp", config.wifiConnected.ipv4Address)
                putInt("mockWifiScanCount", config.wifiScan.maxEntries)
            } else {
                putBoolean("enableMockWifi", false)
                putBoolean("hookWifi", false)
                putBoolean("disableWifiScan", false)
            }
            putBoolean("hideBaseStation", cellSwitch.isRuntimeEnabled(MockMode.MASK_REAL))
            putBoolean("needDowngradeToCdma", cellSwitch.isRuntimeEnabled(MockMode.REPLACE_WITH_PROFILE))
        }
    }

    private fun MockDomainConfig.isRuntimeEnabled(domain: MockDomain): Boolean =
        switches[domain].isRuntimeEnabled()

    private fun DomainSwitch?.isRuntimeEnabled(requiredMode: MockMode? = null): Boolean =
        this != null &&
            runtimeEnabled &&
            !killSwitch &&
            !domainGuardDisabled &&
            !faulted &&
            mode != MockMode.OFF &&
            (requiredMode == null || mode == requiredMode)
}
