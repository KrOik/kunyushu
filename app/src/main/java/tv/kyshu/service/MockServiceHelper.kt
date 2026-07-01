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

object MockServiceHelper {
    const val PROVIDER_NAME = "portal"
    private lateinit var randomKey: String

    fun tryInitService(locationManager: LocationManager) {
        val rely = Bundle()
        Log.d("MockServiceHelper", "Try to init service")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, "exchange_key", rely)) {
            rely.getString("key")?.let {
                randomKey = it
                Log.d("MockServiceHelper", "Service init success, key: $randomKey")
            }
        } else {
            Log.e("MockServiceHelper", "Failed to init service")
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
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_gnss_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun stopGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_gnss_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
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
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_wifi_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun stopWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_wifi_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun tryOpenMock(
        locationManager: LocationManager,
        speed: Double,
        altitude: Double,
        accuracy: Float,
    ): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start")
        rely.putDouble("speed", speed)
        rely.putDouble("altitude", altitude)
        rely.putFloat("accuracy", accuracy)
        return if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            isMockStart(locationManager)
        } else {
            false
        }
    }

    fun tryCloseMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop")
        if (locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return !isMockStart(locationManager)
        }
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

        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
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
}