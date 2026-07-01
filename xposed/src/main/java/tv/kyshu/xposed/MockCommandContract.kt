package tv.kyshu.xposed

import android.os.Bundle
import tv.kyshu.xposed.utils.FakeLoc

object MockCommandContract {

    object Commands {
        const val EXCHANGE_KEY = "exchange_key"
        const val PUT_CONFIG = "put_config"
        const val SYNC_CONFIG = "sync_config"
        const val UPDATE_WIFI = "update_wifi"
        const val START = "start"
        const val STOP = "stop"
        const val IS_START = "is_start"
        const val START_GNSS_MOCK = "start_gnss_mock"
        const val STOP_GNSS_MOCK = "stop_gnss_mock"
        const val IS_GNSS_START = "is_gnss_start"
        const val START_WIFI_MOCK = "start_wifi_mock"
        const val STOP_WIFI_MOCK = "stop_wifi_mock"
        const val IS_WIFI_MOCK_START = "is_wifi_mock_start"
        const val PING = "ping"
        const val DUMP_AUDIT = "dump_audit"
        const val TELEMETRY_DUMP = "telemetry_dump"
        const val EXPORT_LOGS = "export_logs"
        const val GET_SPEED = "get_speed"
        const val GET_BEARING = "get_bearing"
    }

    object Keys {
        const val COMMAND_ID = "command_id"
        const val ENABLE = "enable"
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"
        const val ALTITUDE = "altitude"
        const val SPEED = "speed"
        const val ACCURACY = "accuracy"
        const val BEARING = "bearing"
        const val HAS_BEARINGS = "has_bearings"
        const val SPEED_AMPLITUDE = "speed_amplitude"
        const val LAST_LOCATION = "last_location"
        const val LOOP_BROADCAST_LOCATION = "loop_broadcast_location"
        const val ENABLE_NMEA = "enable_nmea"
        const val ENABLE_AGPS = "enable_agps"
        const val MIN_SATELLITES = "min_satellites"
        const val ENABLE_MOCK_WIFI = "enable_mock_wifi"
        const val HOOK_WIFI = "hook_wifi"
        const val DISABLE_WIFI_SCAN = "disable_wifi_scan"
        const val MOCK_SSID = "mock_ssid"
        const val MOCK_BSSID = "mock_bssid"
        const val MOCK_MAC = "mock_mac"
        const val MOCK_IP = "mock_ip"
        const val MOCK_WIFI_SCAN_COUNT = "mock_wifi_scan_count"
        const val HIDE_BASE_STATION = "hide_base_station"
        const val NEED_DOWNGRADE_TO_2G = "need_downgrade_to_2g"
        const val HOOK_SENSOR = "hook_sensor"
        const val ENABLE_STEP_COUNT_MOCK = "enable_step_count_mock"
        const val STEP_COUNT = "step_count"
        const val ENABLE_SHAKE_MOCK = "enable_shake_mock"
        const val KEEP_WALKING_WAVEFORM = "keep_walking_waveform"
        const val DOZE_SENSOR_INTERVAL_MS = "doze_sensor_interval_ms"
        const val GAIT_FREQ = "gait_freq"
        const val GAIT_AMP_SCALE = "gait_amp_scale"
        const val GAIT_PARAMS_JSON = "gait_params_json"
        const val DISABLE_GET_CURRENT_LOCATION = "disable_get_current_location"
        const val DISABLE_REGISTER_LOCATION_LISTENER = "disable_register_location_listener"
        const val DISABLE_FUSED_LOCATION = "disable_fused_location"
        const val DISABLE_REQUEST_GEOFENCE = "disable_request_geofence"
        const val DISABLE_GET_FROM_LOCATION = "disable_get_from_location"
        const val IS_APP_IN_BACKGROUND = "is_app_in_background"
        const val IS_SCREEN_OFF = "is_screen_off"
        const val IS_DEVICE_IDLE_MODE = "is_device_idle_mode"
        const val MIN_SPEED = "min_speed"
        const val MAX_SPEED = "max_speed"
        const val ENABLE_LOG = "enable_log"
        const val ENABLE_DEBUG_LOG = "enable_debug_log"
        const val HIDE_MOCK = "hide_mock"
        const val DISABLE_NETWORK_LOCATION = "disable_network_location"
        const val TELEMETRY_JSONL = "telemetry_jsonl"
    }

    object LegacyKeys {
        const val DISABLE_REGISTER_MISSPELLED = "disable_regitser_location_listener"
        const val LOOP_BROADCAST_MISSPELLED = "loop_broadcastlocation"
        const val DISABLE_FUSED_PROVIDER = "disableFusedProvider"
        const val MIN_SATELLITE_COUNT_CAMEL = "minSatelliteCount"
    }

    object Extract {
        fun double(b: Bundle, k: String, d: Double = 0.0): Double =
            if (b.containsKey(k)) b.getDouble(k) else d
        fun float(b: Bundle, k: String, d: Float = 0f): Float =
            if (b.containsKey(k)) b.getFloat(k) else d
        fun int(b: Bundle, k: String, d: Int = 0): Int =
            if (b.containsKey(k)) b.getInt(k) else d
        fun long(b: Bundle, k: String, d: Long = 0L): Long =
            if (b.containsKey(k)) b.getLong(k) else d
        fun numberLong(b: Bundle, k: String, d: Long = 0L): Long =
            (if (b.containsKey(k)) b.get(k) as? Number else null)?.toLong() ?: d
        fun boolean(b: Bundle, k: String, d: Boolean = false): Boolean =
            if (b.containsKey(k)) b.getBoolean(k) else d
        fun string(b: Bundle, k: String, d: String? = null): String? =
            if (b.containsKey(k)) b.getString(k) else d
    }

    private fun sanitizeMinSatellites(value: Int): Int = when {
        value <= 0 -> 12
        value > 35 -> 35
        else -> value
    }

    fun applyPutConfig(rely: Bundle) {
        synchronized(FakeLoc) {
            with(Extract) {
                if (rely.containsKey(Keys.ENABLE)) FakeLoc.enable = boolean(rely, Keys.ENABLE)
                if (rely.containsKey(Keys.SPEED)) FakeLoc.speed = double(rely, Keys.SPEED)
                if (rely.containsKey(Keys.ALTITUDE)) FakeLoc.altitude = double(rely, Keys.ALTITUDE)
                if (rely.containsKey(Keys.ACCURACY)) FakeLoc.accuracy = float(rely, Keys.ACCURACY)

                if (rely.containsKey(Keys.ENABLE_MOCK_WIFI)) FakeLoc.enableMockWifi = boolean(rely, Keys.ENABLE_MOCK_WIFI)
                if (rely.containsKey(Keys.DISABLE_WIFI_SCAN)) FakeLoc.disableWifiScan = boolean(rely, Keys.DISABLE_WIFI_SCAN)
                if (rely.containsKey(Keys.MOCK_SSID)) FakeLoc.mockSSID = string(rely, Keys.MOCK_SSID) ?: ""
                if (rely.containsKey(Keys.MOCK_BSSID)) FakeLoc.mockBSSID = string(rely, Keys.MOCK_BSSID) ?: ""
                if (rely.containsKey(Keys.MOCK_MAC)) FakeLoc.mockMac = string(rely, Keys.MOCK_MAC) ?: ""
                if (rely.containsKey(Keys.MOCK_IP)) FakeLoc.mockIp = string(rely, Keys.MOCK_IP) ?: ""
                if (rely.containsKey(Keys.MOCK_WIFI_SCAN_COUNT)) FakeLoc.mockWifiScanCount = int(rely, Keys.MOCK_WIFI_SCAN_COUNT)

                if (rely.containsKey(Keys.HIDE_BASE_STATION)) FakeLoc.hideBaseStation = boolean(rely, Keys.HIDE_BASE_STATION)
                if (rely.containsKey(Keys.MIN_SATELLITES)) FakeLoc.minSatellites = sanitizeMinSatellites(int(rely, Keys.MIN_SATELLITES))

                if (rely.containsKey(Keys.HOOK_SENSOR)) FakeLoc.hookSensor = boolean(rely, Keys.HOOK_SENSOR)
                if (rely.containsKey(Keys.ENABLE_STEP_COUNT_MOCK)) FakeLoc.enableStepCountMock = boolean(rely, Keys.ENABLE_STEP_COUNT_MOCK)
                if (rely.containsKey(Keys.STEP_COUNT)) FakeLoc.stepCount = int(rely, Keys.STEP_COUNT)
                if (rely.containsKey(Keys.ENABLE_SHAKE_MOCK)) FakeLoc.enableShakeMock = boolean(rely, Keys.ENABLE_SHAKE_MOCK)
                if (rely.containsKey(Keys.KEEP_WALKING_WAVEFORM)) FakeLoc.keepWalkingWaveform = boolean(rely, Keys.KEEP_WALKING_WAVEFORM)
                if (rely.containsKey(Keys.DOZE_SENSOR_INTERVAL_MS)) FakeLoc.dozeSensorIntervalMs = numberLong(rely, Keys.DOZE_SENSOR_INTERVAL_MS)
                if (rely.containsKey(Keys.GAIT_FREQ)) FakeLoc.gaitFreq = float(rely, Keys.GAIT_FREQ)
                if (rely.containsKey(Keys.GAIT_AMP_SCALE)) FakeLoc.gaitAmpScale = float(rely, Keys.GAIT_AMP_SCALE)
                if (rely.containsKey(Keys.GAIT_PARAMS_JSON)) FakeLoc.gaitParamsJson = string(rely, Keys.GAIT_PARAMS_JSON) ?: ""

                if (rely.containsKey(Keys.DISABLE_GET_CURRENT_LOCATION)) FakeLoc.disableGetCurrentLocation = boolean(rely, Keys.DISABLE_GET_CURRENT_LOCATION)
                if (rely.containsKey(Keys.DISABLE_REGISTER_LOCATION_LISTENER)) FakeLoc.disableRegisterLocationListener = boolean(rely, Keys.DISABLE_REGISTER_LOCATION_LISTENER)
                if (rely.containsKey(Keys.DISABLE_FUSED_LOCATION)) FakeLoc.disableFusedLocation = boolean(rely, Keys.DISABLE_FUSED_LOCATION)
                if (rely.containsKey(Keys.LOOP_BROADCAST_LOCATION)) FakeLoc.loopBroadcastLocation = boolean(rely, Keys.LOOP_BROADCAST_LOCATION)
                if (rely.containsKey(Keys.DISABLE_REQUEST_GEOFENCE)) FakeLoc.disableRequestGeofence = boolean(rely, Keys.DISABLE_REQUEST_GEOFENCE)
                if (rely.containsKey(Keys.DISABLE_GET_FROM_LOCATION)) FakeLoc.disableGetFromLocation = boolean(rely, Keys.DISABLE_GET_FROM_LOCATION)

                if (rely.containsKey(Keys.IS_APP_IN_BACKGROUND)) FakeLoc.isAppInBackground = boolean(rely, Keys.IS_APP_IN_BACKGROUND)
                if (rely.containsKey(Keys.IS_SCREEN_OFF)) FakeLoc.isScreenOff = boolean(rely, Keys.IS_SCREEN_OFF)
                if (rely.containsKey(Keys.IS_DEVICE_IDLE_MODE)) FakeLoc.isDeviceIdleMode = boolean(rely, Keys.IS_DEVICE_IDLE_MODE)

                if (rely.containsKey(Keys.MIN_SPEED)) FakeLoc.minSpeed = double(rely, Keys.MIN_SPEED)
                if (rely.containsKey(Keys.MAX_SPEED)) FakeLoc.maxSpeed = double(rely, Keys.MAX_SPEED)
                if (rely.containsKey(Keys.ENABLE_DEBUG_LOG)) FakeLoc.enableDebugLog = boolean(rely, Keys.ENABLE_DEBUG_LOG)
                if (rely.containsKey(Keys.HOOK_WIFI)) FakeLoc.wifiData.hookWifi = boolean(rely, Keys.HOOK_WIFI)
                if (rely.containsKey(Keys.SPEED_AMPLITUDE)) FakeLoc.speedAmplitude = double(rely, Keys.SPEED_AMPLITUDE)
                if (rely.containsKey(Keys.HIDE_MOCK)) FakeLoc.locationData.hideMock = boolean(rely, Keys.HIDE_MOCK)
                if (rely.containsKey(Keys.DISABLE_NETWORK_LOCATION)) FakeLoc.disableNetworkLocation = boolean(rely, Keys.DISABLE_NETWORK_LOCATION)

                if (!rely.containsKey(Keys.DISABLE_REGISTER_LOCATION_LISTENER) && rely.containsKey(LegacyKeys.DISABLE_REGISTER_MISSPELLED)) {
                    FakeLoc.disableRegisterLocationListener = boolean(rely, LegacyKeys.DISABLE_REGISTER_MISSPELLED)
                }
                if (!rely.containsKey(Keys.LOOP_BROADCAST_LOCATION) && rely.containsKey(LegacyKeys.LOOP_BROADCAST_MISSPELLED)) {
                    FakeLoc.loopBroadcastLocation = boolean(rely, LegacyKeys.LOOP_BROADCAST_MISSPELLED)
                }
            }
        }
    }

    fun populateSyncReply(rely: Bundle) {
        with(FakeLoc) {
            rely.putBoolean(Keys.ENABLE, enable)
            rely.putDouble(Keys.LATITUDE, latitude)
            rely.putDouble(Keys.LONGITUDE, longitude)
            rely.putDouble(Keys.ALTITUDE, altitude)
            rely.putDouble(Keys.SPEED, speed)
            rely.putDouble(Keys.SPEED_AMPLITUDE, 0.0)
            rely.putString(Keys.LAST_LOCATION, null)
            rely.putBoolean(Keys.HAS_BEARINGS, hasBearings)
            rely.putBoolean(Keys.ENABLE_AGPS, enableAGPS)
            rely.putBoolean(Keys.ENABLE_NMEA, enableNMEA)
            rely.putInt(Keys.MIN_SATELLITES, minSatellites)
            rely.putBoolean(Keys.ENABLE_MOCK_WIFI, enableMockWifi)
            rely.putBoolean(Keys.HOOK_WIFI, hookWifi)
            rely.putBoolean(Keys.DISABLE_WIFI_SCAN, disableWifiScan)
            rely.putString(Keys.MOCK_SSID, mockSSID)
            rely.putString(Keys.MOCK_BSSID, mockBSSID)
            rely.putString(Keys.MOCK_MAC, mockMac)
            rely.putString(Keys.MOCK_IP, mockIp)
            rely.putInt(Keys.MOCK_WIFI_SCAN_COUNT, mockWifiScanCount)
            rely.putBoolean(Keys.HIDE_BASE_STATION, hideBaseStation)
            rely.putBoolean(Keys.NEED_DOWNGRADE_TO_2G, needDowngradeToCdma)
            rely.putBoolean(Keys.HOOK_SENSOR, hookSensor)
            rely.putBoolean(Keys.ENABLE_STEP_COUNT_MOCK, enableStepCountMock)
            rely.putInt(Keys.STEP_COUNT, stepCount)
            rely.putBoolean(Keys.ENABLE_SHAKE_MOCK, enableShakeMock)
            rely.putBoolean(Keys.KEEP_WALKING_WAVEFORM, keepWalkingWaveform)
            rely.putInt(Keys.DOZE_SENSOR_INTERVAL_MS, dozeSensorIntervalMs.toInt())
            rely.putFloat(Keys.GAIT_FREQ, gaitFreq)
            rely.putFloat(Keys.GAIT_AMP_SCALE, gaitAmpScale)
            rely.putString(Keys.GAIT_PARAMS_JSON, gaitParamsJson)
            rely.putBoolean(Keys.DISABLE_GET_CURRENT_LOCATION, disableGetCurrentLocation)
            rely.putBoolean(Keys.DISABLE_REGISTER_LOCATION_LISTENER, disableRegisterLocationListener)
            rely.putBoolean(LegacyKeys.DISABLE_REGISTER_MISSPELLED, disableRegisterLocationListener)
            rely.putBoolean(Keys.DISABLE_FUSED_LOCATION, disableFusedLocation)
            rely.putBoolean(Keys.LOOP_BROADCAST_LOCATION, loopBroadcastLocation)
            rely.putBoolean(Keys.DISABLE_REQUEST_GEOFENCE, disableRequestGeofence)
            rely.putBoolean(Keys.DISABLE_GET_FROM_LOCATION, disableGetFromLocation)
            rely.putBoolean(Keys.IS_APP_IN_BACKGROUND, isAppInBackground)
            rely.putBoolean(Keys.IS_SCREEN_OFF, isScreenOff)
            rely.putBoolean(Keys.IS_DEVICE_IDLE_MODE, isDeviceIdleMode)
            rely.putDouble(Keys.MIN_SPEED, minSpeed)
            rely.putDouble(Keys.MAX_SPEED, maxSpeed)
            rely.putBoolean(Keys.ENABLE_LOG, enableLog)
            rely.putBoolean(Keys.ENABLE_DEBUG_LOG, enableDebugLog)
            rely.putBoolean(Keys.HIDE_MOCK, hideMock)
            rely.putBoolean(Keys.DISABLE_NETWORK_LOCATION, disableNetworkLocation)
        }
    }
}
