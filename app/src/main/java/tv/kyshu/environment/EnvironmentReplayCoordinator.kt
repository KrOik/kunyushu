package tv.kyshu.environment

import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.kyshu.BuildConfig
import tv.kyshu.service.MockDomainConfigPrefs
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.xposed.config.DomainSwitch
import tv.kyshu.xposed.config.MockDomain
import tv.kyshu.xposed.config.MockDomainConfigJson
import tv.kyshu.xposed.config.MockMode

class EnvironmentReplayCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val settings = EnvironmentSettings(appContext)
    private val repository = EnvironmentRepository(EnvironmentDatabase.get(appContext))
    private val mutex = Mutex()
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var lastProfileHash = 0
    private var lastSettingsHash = 0

    suspend fun resolveAndApply(
        locationManager: LocationManager,
        latitude: Double,
        longitude: Double,
        force: Boolean = false,
    ): Boolean = mutex.withLock {
        val settingsHash = listOf(
            settings.wifiReplayEnabled,
            settings.cellReplayEnabled,
            settings.bleReplayEnabled,
            settings.naturalDriftEnabled,
            settings.replayRadiusMeters,
        ).hashCode()
        val previousLat = lastLatitude
        val previousLon = lastLongitude
        if (!force && settingsHash == lastSettingsHash && previousLat != null && previousLon != null &&
            EnvironmentRepository.distanceMeters(previousLat, previousLon, latitude, longitude) < 20.0
        ) return@withLock false

        val resolved = repository.resolve(latitude, longitude, settings.replayRadiusMeters.toDouble())
        val hash = 31 * resolved.hashCode() + settingsHash
        if (!force && hash == lastProfileHash) {
            lastLatitude = latitude; lastLongitude = longitude
            return@withLock false
        }

        val current = MockDomainConfigJson.importJson(MockDomainConfigPrefs.exportJson(appContext), legalize = true)
        val switches = current.switches.toMutableMap()
        fun configure(domain: MockDomain, enabled: Boolean, buildEnabled: Boolean) {
            switches[domain] = DomainSwitch(
                domain = domain,
                buildTimeEnabled = buildEnabled,
                runtimeEnabled = enabled,
                mode = if (enabled) MockMode.REPLACE_WITH_PROFILE else MockMode.OFF,
            )
        }
        val hasConnectedWifi = settings.wifiReplayEnabled && resolved.wifiConnected != null
        val hasWifiScan = settings.wifiReplayEnabled && !resolved.wifiScan?.entries.isNullOrEmpty()
        configure(MockDomain.WLAN_CONNECTED, hasConnectedWifi, BuildConfig.ENABLE_WIFI_MOCK)
        configure(MockDomain.WLAN_SCAN, hasWifiScan, BuildConfig.ENABLE_WIFI_MOCK)
        configure(MockDomain.CELL, settings.cellReplayEnabled && resolved.cellTowers.isNotEmpty(), BuildConfig.ENABLE_CELL_MOCK)
        configure(MockDomain.BLE, settings.bleReplayEnabled && resolved.bleDevices.isNotEmpty(), BuildConfig.ENABLE_BLE_MOCK)

        val updated = current.copy(
            switches = switches,
            location = current.location.copy(latitude = latitude, longitude = longitude),
            wifiConnected = resolved.wifiConnected ?: current.wifiConnected.copy(
                ssid = "", bssid = "", macAddress = "", ipv4Address = "",
            ),
            wifiScan = resolved.wifiScan ?: current.wifiScan.copy(entries = emptyList(), maxEntries = 0),
            cellTowers = if (settings.cellReplayEnabled) resolved.cellTowers else emptyList(),
            bleDevices = if (settings.bleReplayEnabled) resolved.bleDevices else emptyList(),
            motionNoise = current.motionNoise.copy(enabled = settings.naturalDriftEnabled),
        ).legalize()
        MockDomainConfigPrefs.importJson(appContext, MockDomainConfigJson.exportJson(updated))
        val applied = MockServiceHelper.putConfig(locationManager, appContext)
        if (applied) {
            lastLatitude = latitude; lastLongitude = longitude; lastProfileHash = hash; lastSettingsHash = settingsHash
        }
        applied
    }
}
