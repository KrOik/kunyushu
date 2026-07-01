package tv.kyshu.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import tv.kyshu.android.root.ShellUtils
import tv.kyshu.ext.altitude
import tv.kyshu.ext.debug
import tv.kyshu.ext.disableFusedProvider
import tv.kyshu.ext.disableGetCurrentLocation
import tv.kyshu.ext.disableRegisterLocationListener
import tv.kyshu.ext.enableAGPS
import tv.kyshu.ext.enableGetFromLocation
import tv.kyshu.ext.enableNMEA
import tv.kyshu.ext.enableRequestGeofence
import tv.kyshu.ext.minSatelliteCount
import tv.kyshu.ext.needDowngradeToCdma
import tv.kyshu.ext.speed
import tv.kyshu.ext.hookWifi
import tv.kyshu.ext.mockSSID
import tv.kyshu.ext.mockBSSID
import tv.kyshu.ext.mockMac
import tv.kyshu.ext.mockIp
import tv.kyshu.ext.mockWifiScanCount
import tv.kyshu.ext.enableStepCountMock
import tv.kyshu.ext.stepCount
import tv.kyshu.ext.enableShakeMock
import tv.kyshu.ext.keepWalkingWaveform
import tv.kyshu.ext.dozeSensorIntervalMs
import tv.kyshu.ext.gaitFreq
import tv.kyshu.ext.gaitAmpScale
import tv.kyshu.ext.hideBaseStation
import tv.kyshu.ext.loopBroadcastLocation
import tv.kyshu.ext.hideMock
import tv.kyshu.ext.disableNetworkLocation
import tv.kyshu.ext.speedAmplitude
import tv.kyshu.ext.minSpeed
import tv.kyshu.ext.maxSpeed
import tv.kyshu.xposed.utils.FakeLoc
import java.io.File

/**
 * Lightweight probe logger for app-side operations.
 * Emits tagged events to logcat with a [Probe] prefix for easy filtering,
 * matching the xposed module's MockProbeLog format.
 */
private object MockServiceProbe {
    private const val TAG = "PortalApp"
    private const val PREFIX = "[Probe]"

    fun init(result: String, detail: String = "") {
        Log.d(TAG, "$PREFIX [INIT] $result ${detail.trim()}".trim())
    }

    fun ipcSent(command: String, detail: String = "") {
        Log.d(TAG, "$PREFIX [IPC_SENT] $command ${detail.trim()}".trim())
    }

    fun ipcResult(command: String, success: Boolean, detail: String = "") {
        val status = if (success) "OK" else "FAIL"
        Log.d(TAG, "$PREFIX [IPC_RCVD] $command -> $status ${detail.trim()}".trim())
    }

    fun configSync(detail: String) {
        Log.d(TAG, "$PREFIX [CONFIG_SYNC] $detail")
    }

    fun error(msg: String, e: Throwable? = null) {
        val suffix = if (e != null) ": ${e.message}" else ""
        Log.e(TAG, "$PREFIX [ERROR] $msg$suffix")
    }

    fun warn(msg: String) {
        Log.w(TAG, "$PREFIX [WARN] $msg")
    }
}

object MockServiceHelper {
    @JvmStatic
    val PROVIDER_NAME: String
        get() {
            // Dynamic provider name derived from device fingerprint (like VoxLoction)
            // Falls back to "portal" for backward compatibility and emulator/test environments
            val derived = tv.kyshu.xposed.utils.ChannelIdentity.providerName
            return if (derived.isNotBlank()) derived else "portal"
        }
    private lateinit var randomKey: String
    private var commandSequence: Long = 0

    /**
     * Send an authenticated command with HMAC envelope and encrypted payload.
     * Uses the session key from exchange_key and increments the sequence counter.
     * Falls back to plain send if the security module is unavailable.
     */
    private fun sendAuthenticated(
        locationManager: LocationManager,
        commandId: String,
        bundleBuilder: Bundle.() -> Unit
    ): Boolean {
        if (!::randomKey.isInitialized) {
            MockServiceProbe.warn("sendAuthenticated: randomKey not initialized for $commandId")
            return false
        }
        val bundle = Bundle().apply {
            putString("command_id", commandId)
            bundleBuilder()
        }
        val seq = synchronized(this) { ++commandSequence }
        kotlin.runCatching {
            tv.kyshu.xposed.security.RemoteCommandSecurity.attachEnvelope(
                bundle, randomKey, seq
            )
        }.onFailure { e ->
            MockServiceProbe.warn("sendAuthenticated: auth envelope attach failed for $commandId, falling back")
        }
        MockServiceProbe.ipcSent(commandId)
        val result = locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, bundle)
        MockServiceProbe.ipcResult(commandId, result)
        return result
    }

    fun tryInitService(locationManager: LocationManager) {
        val rely = Bundle()
        MockServiceProbe.init("exchange_key_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, "exchange_key", rely)) {
            rely.getString("key")?.let {
                randomKey = it
                MockServiceProbe.init("exchange_key_ok")
            }
        } else {
            MockServiceProbe.init("exchange_key_failed")
        }
    }

    fun isMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_start")
        }
        return false
    }

    fun isGnssMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_gnss_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_gnss_start")
        }
        return false
    }

    fun startGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) return false
        val rely = Bundle()
        rely.putString("command_id", "start_gnss_mock")
        MockServiceProbe.ipcSent("start_gnss_mock")
        val ok = locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
        MockServiceProbe.ipcResult("start_gnss_mock", ok)
        return ok
    }

    fun stopGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) return false
        val rely = Bundle()
        rely.putString("command_id", "stop_gnss_mock")
        MockServiceProbe.ipcSent("stop_gnss_mock")
        val ok = locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
        MockServiceProbe.ipcResult("stop_gnss_mock", ok)
        return ok
    }

    fun isWifiMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_wifi_mock_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_wifi_mock_start")
        }
        return false
    }

    fun startWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) return false
        val rely = Bundle()
        rely.putString("command_id", "start_wifi_mock")
        MockServiceProbe.ipcSent("start_wifi_mock")
        val ok = locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
        MockServiceProbe.ipcResult("start_wifi_mock", ok)
        return ok
    }

    fun stopWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) return false
        val rely = Bundle()
        rely.putString("command_id", "stop_wifi_mock")
        MockServiceProbe.ipcSent("stop_wifi_mock")
        val ok = locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
        MockServiceProbe.ipcResult("stop_wifi_mock", ok)
        return ok
    }

    fun tryOpenMock(
        locationManager: LocationManager,
        speed: Double,
        altitude: Double,
        accuracy: Float,
    ): Boolean {
        if (!::randomKey.isInitialized) return false
        val rely = Bundle()
        rely.putString("command_id", "start")
        rely.putDouble("speed", speed)
        rely.putDouble("altitude", altitude)
        rely.putFloat("accuracy", accuracy)
        MockServiceProbe.ipcSent("start", "speed=$speed alt=$altitude acc=$accuracy")
        val ok = locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
        if (ok) {
            val confirmed = isMockStart(locationManager)
            MockServiceProbe.ipcResult("start", confirmed)
            return confirmed
        }
        MockServiceProbe.ipcResult("start", false)
        return false
    }

    fun tryCloseMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) return false
        val rely = Bundle()
        rely.putString("command_id", "stop")
        MockServiceProbe.ipcSent("stop")
        if (locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            val stopped = !isMockStart(locationManager)
            MockServiceProbe.ipcResult("stop", stopped)
            return stopped
        }
        MockServiceProbe.ipcResult("stop", false)
        return false
    }

    fun getLocation(locationManager: LocationManager): Pair<Double, Double>? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_location")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return Pair(rely.getDouble("lat"), rely.getDouble("lon"))
        }
        return null
    }

    fun getLocationListenerSize(locationManager: LocationManager): Int? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_listener_size")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getInt("size")
        }
        return null
    }

    fun broadcastLocation(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "broadcast_location")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setBearing(locationManager: LocationManager, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_bearing")
        rely.putDouble("bearing", bearing)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setSpeed(locationManager: LocationManager, speed: Float): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_speed")
        rely.putFloat("speed", speed)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setAltitude(locationManager: LocationManager, altitude: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_altitude")
        rely.putDouble("altitude", altitude)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setSpeedAmplitude(locationManager: LocationManager, speedAmplitude: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_speed_amp")
        rely.putDouble("speed_amplitude", speedAmplitude)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun getSpeed(locationManager: LocationManager): Float? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_speed")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getFloat("speed")
        }
        return null
    }

    fun getBearing(locationManager: LocationManager): Float? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_bearing")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getFloat("bearing")
        }
        return null
    }

    fun getAltitude(locationManager: LocationManager): Double? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_altitude")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getDouble("altitude")
        }
        return null
    }

    fun move(locationManager: LocationManager, distance: Double, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "move")
        rely.putDouble("n", distance)
        rely.putDouble("bearing", bearing)

        if (FakeLoc.enableDebugLog) {
            Log.d("MockServiceHelper", "move: distance=$distance, bearing=$bearing")
        }

        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setLocation(locationManager: LocationManager, lat: Double, lon: Double): Boolean {
        return updateLocation(locationManager, lat, lon, "=")
    }

    fun updateLocation(locationManager: LocationManager, lat: Double, lon: Double, mode: String): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "update_location")
        rely.putDouble("lat", lat)
        rely.putDouble("lon", lon)
        rely.putString("mode", mode)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun loadLibrary(locationManager: LocationManager, path: String): String? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "load_library")
        rely.putString("path", path)
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getString("result")
        }
        return null
    }

    fun putConfig(locationManager: LocationManager, context: Context): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }

        FakeLoc.altitude = context.altitude
        FakeLoc.speed = context.speed
        FakeLoc.enableDebugLog = context.debug
        FakeLoc.disableGetCurrentLocation = context.disableGetCurrentLocation
        FakeLoc.disableRegisterLocationListener = context.disableRegisterLocationListener
        FakeLoc.disableFusedLocation = context.disableFusedProvider
        FakeLoc.needDowngradeToCdma = context.needDowngradeToCdma
        FakeLoc.minSatellites = context.minSatelliteCount
        FakeLoc.enableAGPS = context.enableAGPS
        FakeLoc.enableNMEA = context.enableNMEA
        FakeLoc.disableRequestGeofence = !context.enableRequestGeofence
        FakeLoc.disableGetFromLocation = !context.enableGetFromLocation
        FakeLoc.wifiData.hookWifi = context.hookWifi
        FakeLoc.wifiData.mockSSID = context.mockSSID
        FakeLoc.wifiData.mockBSSID = context.mockBSSID
        FakeLoc.wifiData.mockMac = context.mockMac
        FakeLoc.wifiData.mockIp = context.mockIp
        FakeLoc.wifiData.mockWifiScanCount = context.mockWifiScanCount
        FakeLoc.sensorData.enableStepCountMock = context.enableStepCountMock
        FakeLoc.sensorData.stepCount = context.stepCount
        FakeLoc.sensorData.enableShakeMock = context.enableShakeMock
        FakeLoc.sensorData.keepWalkingWaveform = context.keepWalkingWaveform
        FakeLoc.sensorData.dozeSensorIntervalMs = context.dozeSensorIntervalMs
        FakeLoc.gaitData.gaitFreq = context.gaitFreq
        FakeLoc.gaitData.gaitAmpScale = context.gaitAmpScale
        FakeLoc.hideBaseStation = context.hideBaseStation
        FakeLoc.loopBroadcastLocation = context.loopBroadcastLocation
        FakeLoc.locationData.hideMock = context.hideMock
        FakeLoc.disableNetworkLocation = context.disableNetworkLocation
        FakeLoc.speedAmplitude = context.speedAmplitude
        FakeLoc.minSpeed = context.minSpeed
        FakeLoc.maxSpeed = context.maxSpeed

        val rely = Bundle()
        rely.putString("command_id", "put_config")
        rely.putBoolean("enable", FakeLoc.enable)
        rely.putDouble("altitude", FakeLoc.altitude)
        rely.putDouble("speed", FakeLoc.speed)
        rely.putBoolean("enable_debug_log", FakeLoc.enableDebugLog)
        rely.putBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
        rely.putBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
        rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
        rely.putBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
        rely.putInt("min_satellites", FakeLoc.minSatellites)
        rely.putBoolean("enable_agps", FakeLoc.enableAGPS)
        rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
        rely.putBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
        rely.putBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)
        rely.putBoolean("hook_wifi", FakeLoc.wifiData.hookWifi)
        rely.putString("mock_ssid", FakeLoc.wifiData.mockSSID)
        rely.putString("mock_bssid", FakeLoc.wifiData.mockBSSID)
        rely.putString("mock_mac", FakeLoc.wifiData.mockMac)
        rely.putString("mock_ip", FakeLoc.wifiData.mockIp)
        rely.putInt("mock_wifi_scan_count", FakeLoc.wifiData.mockWifiScanCount)
        rely.putBoolean("enable_step_count_mock", FakeLoc.sensorData.enableStepCountMock)
        rely.putInt("step_count", FakeLoc.sensorData.stepCount)
        rely.putBoolean("enable_shake_mock", FakeLoc.sensorData.enableShakeMock)
        rely.putBoolean("keep_walking_waveform", FakeLoc.sensorData.keepWalkingWaveform)
        rely.putLong("doze_sensor_interval_ms", FakeLoc.sensorData.dozeSensorIntervalMs)
        rely.putFloat("gait_freq", FakeLoc.gaitData.gaitFreq)
        rely.putFloat("gait_amp_scale", FakeLoc.gaitData.gaitAmpScale)
        rely.putBoolean("hide_base_station", FakeLoc.hideBaseStation)
        rely.putBoolean("loop_broadcast_location", FakeLoc.loopBroadcastLocation)
        rely.putBoolean("hide_mock", FakeLoc.locationData.hideMock)
        rely.putBoolean("disable_network_location", FakeLoc.disableNetworkLocation)
        rely.putDouble("speed_amplitude", FakeLoc.speedAmplitude)
        rely.putDouble("min_speed", FakeLoc.minSpeed)
        rely.putDouble("max_speed", FakeLoc.maxSpeed)
        MockServiceProbe.configSync("put_config: enable=${FakeLoc.enable}")
        val ok = locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
        MockServiceProbe.ipcResult("put_config", ok)
        return ok
    }

    fun isServiceInit(): Boolean {
        return ::randomKey.isInitialized
    }

    @SuppressLint("DiscouragedPrivateApi")
    fun loadPortalLibrary(context: Context): Boolean {
        if (!ShellUtils.hasRoot()) return false

        val isX86: Boolean = runCatching {
            if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) {
                return@runCatching true
            }
            val clazz = Class.forName("dalvik.system.VMRuntime")
            val method = clazz.getDeclaredMethod("getRuntime")
            val runtime = method.invoke(null)
            val field = clazz.getDeclaredField("vmInstructionSet")
            field.isAccessible = true
            val instructionSet = field.get(runtime) as String
            if (instructionSet.contains("x86") ) {
                true
            } else false
        }.getOrElse { false }
        // todo: support x86

        val soDir = File("/data/local/portal-lib")
        if (!soDir.exists()) {
            ShellUtils.executeCommand("mkdir -m 755 ${soDir.absolutePath}")
        }
        val soFile = File(soDir, "libportal.so")
        runCatching {
            val tmpSoFile = File(soDir, "libportal.so.tmp").also { file ->
                var nativeDir = context.applicationInfo.nativeLibraryDir
                val apkSoFile = File(nativeDir, "libportal.so")
                if (apkSoFile.exists()) {
                    ShellUtils.executeCommand("cp ${apkSoFile.absolutePath} ${file.absolutePath}")
                } else {
                    Log.e("MockServiceHelper", "Failed to copy portal library: ${apkSoFile.absolutePath}")
                    return@runCatching
                }
            }
            if (soFile.exists()) {
                val newHash = ShellUtils.executeCommand("sha256sum ${tmpSoFile.absolutePath}").substringBefore(' ')
                val oldHash = ShellUtils.executeCommand("sha256sum ${soFile.absolutePath}").substringBefore(' ')
                if (newHash == oldHash || oldHash.isBlank()) {
                    if (newHash.isNotEmpty()) {
                        ShellUtils.executeCommand("rm ${soFile.absolutePath}")
                        ShellUtils.executeCommand("mv ${tmpSoFile.absolutePath} ${soFile.absolutePath}")
                    }
                } else {
                    Log.w("MockServiceHelper", "SO hash mismatch, new: $newHash, old: $oldHash")
                    ShellUtils.executeCommand("rm ${tmpSoFile.absolutePath}")
                }
            } else if (tmpSoFile.exists()) {
                ShellUtils.executeCommand("mv ${tmpSoFile.absolutePath} ${soFile.absolutePath}")
            }
        }.onFailure {
            Log.w("MockServiceHelper", "Failed to copy portal library", it)
        }

        // TOCTOU guard: verify canonical path is within expected directory
        val resolvedPath = soFile.canonicalPath
        val resolvedDir = soDir.canonicalPath
        if (!resolvedPath.startsWith(resolvedDir)) {
            Log.e("MockServiceHelper", "Symlink attack detected: $resolvedPath not under $resolvedDir")
            return false
        }
        ShellUtils.executeCommand("chmod 755 ${soFile.absolutePath}")

        val result = loadLibrary(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager, soFile.absolutePath)

        Log.d("MockServiceHelper", "load portal library result: $result")

        return result == "success"
    }

    /**
     * Always returns false — hooked by the Xposed module to fool
     * any code that checks whether the mock module is active.
     * The real return value is forced to `false` by FakeLocation
     * before any app-side code can call this method.
     */
    @JvmStatic
    fun isModuleLoaded(): Boolean = false

    /**
     * ContentProvider fallback channel — used when sendExtraCommand
     * is unavailable or the location provider is not registered.
     * Writes the command bundle as JSON to the ContentProvider.
     */
    @SuppressLint("Recycle")
    private fun sendViaContentProvider(context: Context, commandId: String, payload: Bundle): Boolean {
        return kotlin.runCatching {
            val uri = android.net.Uri.parse("content://tv.kyshu.xposed.config/cmd")
            val json = org.json.JSONObject()
            json.put("command_id", commandId)
            json.put("timestamp_ms", System.currentTimeMillis())
            // Serialize payload fields
            for (key in payload.keySet()) {
                json.put(key, payload.get(key))
            }
            val contentValues = android.content.ContentValues().apply {
                put("command", json.toString())
            }
            context.contentResolver.insert(uri, contentValues)
            true
        }.getOrDefault(false)
    }

    /**
     * Send a command with automatic fallback to ContentProvider.
     */
    fun sendCommandWithFallback(
        locationManager: LocationManager,
        context: Context,
        commandId: String,
        payload: Bundle
    ): Boolean {
        // Primary: try sendExtraCommand
        if (::randomKey.isInitialized) {
            val ok = locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, payload)
            if (o