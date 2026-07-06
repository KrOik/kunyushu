package tv.kyshu.xposed

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import tv.kyshu.xposed.security.RemoteCommandSecurity
import tv.kyshu.xposed.hooks.sensor.SystemSensorManagerHook
import tv.kyshu.xposed.utils.BinderUtils
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.ChannelIdentity
import tv.kyshu.xposed.utils.Logger
import java.util.concurrent.ConcurrentHashMap

abstract class BaseDivineService {
    private var commandSequence: Long = 0L

    companion object {
        private const val DEFERRED_INIT_ATTEMPTS = 60
        private const val DEFERRED_INIT_INTERVAL_MS = 1_000L
        private const val LOCATION_MANAGER_ATTEMPTS = 3
        private const val PROVIDER_READY_ATTEMPTS = 10
        private const val SHORT_RETRY_INTERVAL_MS = 100L

        private val initializedServices = ConcurrentHashMap.newKeySet<String>()
        private val pendingServices = ConcurrentHashMap.newKeySet<String>()
    }

    protected fun ensureDivineService(from: String): Boolean {
        if (FakeLoc.isSystemServerProcess) {
            return true
        }
        if (initializedServices.contains(from)) {
            return true
        }
        if (initDivineService(from, logFailures = false)) {
            initializedServices.add(from)
            return true
        }
        scheduleDeferredInit(from)
        return false
    }

    private fun scheduleDeferredInit(from: String) {
        if (!pendingServices.add(from)) {
            return
        }
        Thread({
            try {
                repeat(DEFERRED_INIT_ATTEMPTS) { attempt ->
                    try {
                        Thread.sleep(DEFERRED_INIT_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                    if (initDivineService(from, logFailures = false)) {
                        initializedServices.add(from)
                        Logger.info("[BaseDivineService] Deferred init succeeded in $from after ${attempt + 1}s")
                        return@Thread
                    }
                }
                Logger.warn("[BaseDivineService] Deferred init unavailable in $from after ${DEFERRED_INIT_ATTEMPTS}s")
            } finally {
                pendingServices.remove(from)
            }
        }, "Svc-Divine-$from").apply { isDaemon = true }.start()
    }

    /**
     * if the hook is TelephonyService? or other service?
     * this service may not be in the same space as the `system_server`,
     * so a binder is used to talk.
     */
    protected fun initDivineService(from: String, retryCount: Int = 0, logFailures: Boolean = true): Boolean {
        if (FakeLoc.isSystemServerProcess) {
            initializedServices.add(from)
            return true
        }
        val sensorBootstrap = SensorAppBootstrap.from(from)

        fun tryFetchLocationManager(): LocationManager? {
            repeat(LOCATION_MANAGER_ATTEMPTS) { attempt ->
                val locationManager = BinderUtils.getSystemContext()
                    ?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (locationManager != null) {
                    return locationManager
                }
                if (attempt < LOCATION_MANAGER_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(SHORT_RETRY_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
            return null
        }

        val locationManager = tryFetchLocationManager()
        if (locationManager == null) {
            if (logFailures) {
                Logger.warn("LocationManager not found in $from")
            }
            return false
        }

        if (sensorBootstrap == null) {
            var providerReady = false
            var attempts = retryCount
            while (attempts <= PROVIDER_READY_ATTEMPTS) {
                providerReady = runCatching {
                    locationManager.isProviderEnabled(ChannelIdentity.providerName)
                }.getOrDefault(false)
                if (providerReady) {
                    break
                }
                if (attempts == PROVIDER_READY_ATTEMPTS) {
                    break
                }
                attempts += 1
                try {
                    Thread.sleep(SHORT_RETRY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (logFailures) {
                        Logger.warn("Interrupted while waiting for provider ${ChannelIdentity.providerName} in $from")
                    }
                    return false
                }
            }
            if (!providerReady) {
                if (logFailures) {
                    Logger.warn("Location provider ${ChannelIdentity.providerName} not ready in $from")
                }
                return false
            }
        }

        val randomKey = exchangeSessionKey(locationManager, sensorBootstrap)
        if (randomKey.isNullOrEmpty()){
            if (logFailures) {
                Logger.warn("Failed to init service in $from")
            }
            return false
        }

        if (!syncConfig(locationManager, randomKey, from)) {
            return false
        }

        val proxyBinder = object: Binder() {
            override fun getInterfaceDescriptor(): String {
                return "tv.kyshu.service.${from}Helper"
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) {
                    val sessionKey = data.readString() ?: randomKey
                    val bundle = data.readBundle(javaClass.classLoader) ?: run {
                        Logger.warn("ProxyBinder($from): missing command bundle")
                        return true
                    }
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("ProxyBinder($from): $bundle")
                    }
                    if(!RemoteCommandHandler.handleSessionInstruction(sessionKey, bundle)) {
                        Logger.error("Failed to handle instruction in $from")
                    }
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
        val (proxyOk, _) = sendAuthenticated(locationManager, randomKey, "set_proxy") {
            putBinder("proxy", proxyBinder)
        }
        if (!proxyOk) {
            if (logFailures) {
                Logger.warn("Failed to init service proxy in $from")
            }
            return false
        }
        initializedServices.add(from)
        return true
    }

    private fun exchangeSessionKey(
        locationManager: LocationManager,
        sensorBootstrap: SensorAppBootstrap?,
    ): String? {
        val rely = Bundle().apply {
            if (sensorBootstrap != null) {
                putString("requested_scope", RemoteCommandHandler.SCOPE_SENSOR_APP)
                putString("from_package", sensorBootstrap.packageName)
                putString("from_process", sensorBootstrap.processName)
            }
        }
        if (!locationManager.sendExtraCommand(ChannelIdentity.providerName, "exchange_key", rely)) {
            return null
        }
        val sessionKey = RemoteCommandSecurity.decryptSessionKey(rely) ?: return null
        RemoteCommandHandler.registerSessionKey(sessionKey)
        return sessionKey
    }

    @Synchronized
    private fun nextCommandSequence(): Long {
        commandSequence += 1
        return commandSequence
    }

    private fun sendAuthenticated(
        locationManager: LocationManager,
        randomKey: String,
        commandId: String,
        bundleBuilder: Bundle.() -> Unit = {},
    ): Pair<Boolean, Bundle> {
        val rely = Bundle().apply {
            putString("command_id", commandId)
            bundleBuilder()
        }
        if (!RemoteCommandSecurity.attachEnvelope(rely, randomKey, nextCommandSequence())) {
            Logger.error("Failed to attach auth envelope for DivineService command=$commandId")
            return false to rely
        }
        if (!locationManager.sendExtraCommand(ChannelIdentity.providerName, randomKey, rely)) {
            return false to rely
        }
        if (!RemoteCommandSecurity.decryptSensitivePayload(rely, randomKey)) {
            Logger.error("Failed to decrypt DivineService response for command=$commandId")
            return false to rely
        }
        return true to rely
    }

    /**
     * Synchronize configurations in different processes
     */
    private fun syncConfig(locationManager: LocationManager, randomKey: String, from: String): Boolean {
        val (ok, rely) = sendAuthenticated(locationManager, randomKey, "sync_config")
        if(ok) {
            val parsed = runCatching {
                SyncCriticalConfig(
                    latitude = MockCommandContract.requireLatitude(
                        MockCommandContract.Extract.requireDouble(rely, "latitude"),
                        "latitude"
                    ),
                    longitude = MockCommandContract.requireLongitude(
                        MockCommandContract.Extract.requireDouble(rely, "longitude"),
                        "longitude"
                    ),
                    altitude = MockCommandContract.requireAltitude(
                        MockCommandContract.Extract.requireDouble(rely, "altitude"),
                        "altitude"
                    ),
                    speed = MockCommandContract.requireSpeed(
                        MockCommandContract.Extract.requireDouble(rely, "speed"),
                        "speed"
                    ),
                    speedAmplitude = MockCommandContract.requireSpeed(
                        MockCommandContract.Extract.requireDouble(rely, "speed_amplitude"),
                        "speed_amplitude"
                    ),
                    bearing = MockCommandContract.requireBearing(
                        MockCommandContract.Extract.requireDouble(rely, "bearing"),
                        "bearing"
                    ),
                )
            }.onFailure {
                Logger.error("Invalid sync_config critical payload; keeping previous config", it)
            }.getOrNull() ?: return false
            FakeLoc.enable = rely.getBoolean("enable", FakeLoc.enable)
            FakeLoc.latitude = parsed.latitude
            FakeLoc.longitude = parsed.longitude
            FakeLoc.altitude = parsed.altitude
            FakeLoc.speed = parsed.speed
            FakeLoc.speedAmplitude = parsed.speedAmplitude
            FakeLoc.hasBearings = rely.getBoolean("has_bearings", FakeLoc.hasBearings)
            FakeLoc.bearing = parsed.bearing
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                FakeLoc.lastLocation = rely.getParcelable("last_location", Location::class.java)
            } else {
                FakeLoc.lastLocation = rely.getParcelable("last_location")
            }
            FakeLoc.enableLog = rely.getBoolean("enable_log", FakeLoc.enableLog)
            FakeLoc.enableDebugLog = rely.getBoolean("enable_debug_log", FakeLoc.enableDebugLog)
            FakeLoc.disableGetCurrentLocation = rely.getBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
            FakeLoc.disableRegisterLocationListener = rely.getBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
            FakeLoc.disableFusedLocation = rely.getBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
            FakeLoc.enableMockGnss = rely.getBoolean("enable_mock_gnss", FakeLoc.enableMockGnss)
            FakeLoc.enableAGPS = rely.getBoolean("enable_agps", FakeLoc.enableAGPS)
            FakeLoc.enableNMEA = rely.getBoolean("enable_nmea", FakeLoc.enableNMEA)
            FakeLoc.minSatellites = rely.getInt("min_satellites", FakeLoc.minSatellites).coerceIn(1, 35)
            FakeLoc.hideMock = rely.getBoolean("hide_mock", FakeLoc.hideMock)
            FakeLoc.hookWifi = rely.getBoolean("hook_wifi", FakeLoc.hookWifi)
            FakeLoc.enableMockWifi = rely.getBoolean("enable_mock_wifi", FakeLoc.enableMockWifi)
            FakeLoc.disableWifiScan = rely.getBoolean("disable_wifi_scan", FakeLoc.disableWifiScan)
            FakeLoc.mockSSID = rely.getString("mock_ssid") ?: FakeLoc.mockSSID
            FakeLoc.mockBSSID = rely.getString("mock_bssid") ?: FakeLoc.mockBSSID
            FakeLoc.mockMac = rely.getString("mock_mac") ?: FakeLoc.mockMac
            FakeLoc.mockIp = rely.getString("mock_ip") ?: FakeLoc.mockIp
            FakeLoc.mockWifiScanCount = rely.getInt("mock_wifi_scan_count", FakeLoc.mockWifiScanCount)
            FakeLoc.hookSensor = rely.getBoolean("hook_sensor", FakeLoc.hookSensor)
            FakeLoc.enableStepCountMock = rely.getBoolean("enable_step_count_mock", FakeLoc.enableStepCountMock)
            FakeLoc.stepCount = rely.getInt("step_count", FakeLoc.stepCount)
            FakeLoc.enableShakeMock = rely.getBoolean("enable_shake_mock", FakeLoc.enableShakeMock)
            FakeLoc.sensorData.enableMotionMock = rely.getBoolean("enable_motion_mock", FakeLoc.sensorData.enableMotionMock)
            FakeLoc.keepWalkingWaveform = rely.getBoolean("keep_walking_waveform", FakeLoc.keepWalkingWaveform)
            FakeLoc.needDowngradeToCdma = rely.getBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
            rely.getString("rinex_path")?.takeIf { it.isNotBlank() }?.let { FakeLoc.rinexPath = it }
            SystemSensorManagerHook.updateStepGoal()
            Logger.info(
                "[BaseDivineService] Synced config for $from: enable=${FakeLoc.enable}, " +
                    "gnss=${FakeLoc.enableMockGnss}, cdma=${FakeLoc.needDowngradeToCdma}, " +
                    "sensor=${FakeLoc.hookSensor}, motion=${FakeLoc.sensorData.enableMotionMock}, " +
                    "step=${FakeLoc.enableStepCountMock}, stepCount=${FakeLoc.stepCount}"
            )
            return true
        } else {
            Logger.error("Failed to sync config for DivineService")
        }
        return false
    }

    private data class SyncCriticalConfig(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val speed: Double,
        val speedAmplitude: Double,
        val bearing: Double,
    )

    private data class SensorAppBootstrap(
        val packageName: String,
        val processName: String,
    ) {
        companion object {
            fun from(from: String): SensorAppBootstrap? {
                if (!from.startsWith("SensorApp:")) return null
                val parts = from.split(":", limit = 3)
                val packageName = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
                val processName = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: packageName
                return SensorAppBootstrap(packageName, processName)
            }
        }
    }
}
