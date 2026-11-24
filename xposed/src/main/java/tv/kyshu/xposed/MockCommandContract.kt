package tv.kyshu.xposed

import android.os.Bundle
import tv.kyshu.xposed.config.DomainSwitch
import tv.kyshu.xposed.config.GnssProfile
import tv.kyshu.xposed.config.LocationProfile
import tv.kyshu.xposed.config.MockDomain
import tv.kyshu.xposed.config.MockDomainConfig
import tv.kyshu.xposed.config.MockDomainConfigJson
import tv.kyshu.xposed.config.MockMode
import tv.kyshu.xposed.config.NmeaProfile
import tv.kyshu.xposed.config.WifiConnectedProfile
import tv.kyshu.xposed.config.WifiScanEntry
import tv.kyshu.xposed.config.WifiScanProfile
import tv.kyshu.xposed.config.UidPolicy
import tv.kyshu.xposed.utils.LocaleNumberParsing
import tv.kyshu.xposed.utils.FakeLoc

object MockCommandContract {

    object Commands {
        const val EXCHANGE_KEY = "exchange_key"
        const val PUT_CONFIG = "put_config"
        const val SET_UID_TARGETS = "set_uid_targets"
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
        const val GRANT_LOG_EXPORT = "grant_log_export"
        const val EXPORT_LOGS = "export_logs"
        const val GET_SPEED = "get_speed"
        const val GET_BEARING = "get_bearing"
        const val UPDATE_RINEX = "update_rinex"
        const val LOCATION_UPDATE = "location.update"
        const val GNSS_UPDATE_STATUS = "gnss.update_status"
        const val NMEA_UPDATE_STREAM = "nmea.update_stream"
        const val WIFI_UPDATE_CONNECTED = "wifi.update_connected"
        const val WIFI_UPDATE_SCAN = "wifi.update_scan"
        const val CELL_UPDATE = "cell.update"
        const val BLE_UPDATE = "ble.update"
        const val NFC_REPLAY = "nfc.replay"
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
        const val ENABLE_MOCK_GNSS = "enable_mock_gnss"
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
        const val ENABLE_MOTION_MOCK = "enable_motion_mock"
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
        const val RINEX_PATH = "rinex_path"
        const val TELEMETRY_JSONL = "telemetry_jsonl"
        const val DOMAIN_MODE = "domain_mode"
        const val PAYLOAD_HEX = "payload_hex"
        const val PAYLOAD_JSON = "payload_json"
        const val PAYLOAD_COUNT = "payload_count"
        const val MOCK_DOMAIN_CONFIG_JSON = "mock_domain_config_json"
        const val UID_TARGETS_JSON = "uid_targets_json"
        const val UID_TARGETS_CLEAR = "uid_targets_clear"
    }

    private val domainCommands = setOf(
        Commands.LOCATION_UPDATE,
        Commands.GNSS_UPDATE_STATUS,
        Commands.NMEA_UPDATE_STREAM,
        Commands.WIFI_UPDATE_CONNECTED,
        Commands.WIFI_UPDATE_SCAN,
        Commands.CELL_UPDATE,
        Commands.BLE_UPDATE,
        Commands.NFC_REPLAY,
    )

    fun isDomainCommand(commandId: String): Boolean = commandId in domainCommands

    object LegacyKeys {
        const val DISABLE_REGISTER_MISSPELLED = "disable_regitser_location_listener"
        const val LOOP_BROADCAST_MISSPELLED = "loop_broadcastlocation"
        const val DISABLE_FUSED_PROVIDER = "disableFusedProvider"
        const val MIN_SATELLITE_COUNT_CAMEL = "minSatelliteCount"
    }

    object Extract {
        fun requireDouble(b: Bundle, k: String): Double {
            val value = b.get(k) ?: throw IllegalArgumentException("Missing required numeric command field '$k'")
            val parsed = when (value) {
                is Number -> value.toDouble()
                is String -> LocaleNumberParsing.parseFiniteDouble(value)
                else -> null
            }
            if (parsed == null || !parsed.isFinite()) {
                throw IllegalArgumentException("Invalid numeric command field '$k': value=${describe(value)}")
            }
            return parsed
        }

        fun requireFloat(b: Bundle, k: String): Float {
            val value = b.get(k) ?: throw IllegalArgumentException("Missing required float command field '$k'")
            val parsed = when (value) {
                is Number -> value.toFloat()
                is String -> LocaleNumberParsing.parseFiniteFloat(value)
                else -> null
            }
            if (parsed == null || !parsed.isFinite()) {
                throw IllegalArgumentException("Invalid float command field '$k': value=${describe(value)}")
            }
            return parsed
        }

        fun double(b: Bundle, k: String, d: Double = 0.0): Double =
            if (b.containsKey(k)) requireDouble(b, k) else d
        fun float(b: Bundle, k: String, d: Float = 0f): Float =
            if (b.containsKey(k)) requireFloat(b, k) else d
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

        private fun describe(value: Any): String {
            return "raw='${value.toString().take(128)}', type=${value.javaClass.name}"
        }
    }

    fun requireLatitude(value: Double, key: String = Keys.LATITUDE): Double {
        if (value !in -90.0..90.0) {
            throw IllegalArgumentException("Invalid latitude command field '$key': out of range [-90,90], value=$value")
        }
        return value
    }

    fun requireLongitude(value: Double, key: String = Keys.LONGITUDE): Double {
        if (value !in -180.0..180.0) {
            throw IllegalArgumentException("Invalid longitude command field '$key': out of range [-180,180], value=$value")
        }
        return value
    }

    fun requireAltitude(value: Double, key: String = Keys.ALTITUDE): Double {
        if (!value.isFinite() || value < -1000.0 || value > 100000.0) {
            throw IllegalArgumentException("Invalid altitude command field '$key': out of safe range [-1000,100000], value=$value")
        }
        return value
    }

    fun requireSpeed(value: Double, key: String = Keys.SPEED): Double {
        if (!value.isFinite() || value < 0.0 || value > 1000.0) {
            throw IllegalArgumentException("Invalid speed command field '$key': out of safe range [0,1000], value=$value")
        }
        return value
    }

    fun requireAccuracy(value: Float, key: String = Keys.ACCURACY): Float {
        if (!value.isFinite() || value < 0f || value > 100000f) {
            throw IllegalArgumentException("Invalid accuracy command field '$key': out of safe range [0,100000], value=$value")
        }
        return value
    }

    fun requireBearing(value: Double, key: String = Keys.BEARING): Double {
        if (!value.isFinite() || value < 0.0 || value >= 360.0) {
            throw IllegalArgumentException("Invalid bearing command field '$key': out of range [0,360), value=$value")
        }
        return value
    }

    fun validateDomainCommand(commandId: String, rely: Bundle) {
        if (!isDomainCommand(commandId)) return
        val estimatedByteSize = estimateBundleByteSize(rely)
        if (estimatedByteSize > DOMAIN_COMMAND_MAX_BYTES) {
            throw IllegalArgumentException("Domain command payload too large: $estimatedByteSize > $DOMAIN_COMMAND_MAX_BYTES")
        }
        rely.keySet().forEach { key ->
            if (key.length > DOMAIN_COMMAND_MAX_FIELD_CHARS) {
                throw IllegalArgumentException("Domain command field name too long: ${key.length}")
            }
            when (val value = rely.get(key)) {
                is String -> requireStringLength(key, value, DOMAIN_COMMAND_MAX_STRING_CHARS)
                is ArrayList<*> -> validateStringArrayList(key, value)
            }
        }
        when (commandId) {
            Commands.LOCATION_UPDATE -> validateLocationUpdate(rely)
            Commands.GNSS_UPDATE_STATUS -> validateGnssUpdateStatus(rely)
            Commands.NMEA_UPDATE_STREAM -> validateNmeaUpdateStream(rely)
            Commands.WIFI_UPDATE_CONNECTED -> validateWifiUpdateConnected(rely)
            Commands.WIFI_UPDATE_SCAN -> validateWifiUpdateScan(rely)
            Commands.CELL_UPDATE -> validateCellUpdate(rely)
            Commands.BLE_UPDATE -> validateBleUpdate(rely)
            Commands.NFC_REPLAY -> validateNfcReplay(rely)
        }
    }

    fun estimateBundleByteSize(rely: Bundle): Int {
        return try {
            var size = 0
            for (key in rely.keySet()) {
                size += key.length * 2
                when (val value = rely.get(key)) {
                    is String -> size += value.length * 2
                    is ByteArray -> size += value.size
                    is IntArray -> size += value.size * 4
                    is LongArray -> size += value.size * 8
                    is FloatArray -> size += value.size * 4
                    is DoubleArray -> size += value.size * 8
                    is Boolean -> size += 1
                    is Int -> size += 4
                    is Long -> size += 8
                    is Float -> size += 4
                    is Double -> size += 8
                    is ArrayList<*> -> value.forEach { size += if (it is String) it.length * 2 else 4 }
                    else -> size += 64
                }
            }
            size
        } catch (_: Exception) {
            0
        }
    }

    private fun sanitizeMinSatellites(value: Int): Int = when {
        value <= 0 -> 12
        value > 35 -> 35
        else -> value
    }

    private fun validateLocationUpdate(rely: Bundle) {
        with(Extract) {
            if (rely.containsKey(Keys.LATITUDE)) requireLatitude(double(rely, Keys.LATITUDE))
            if (rely.containsKey(Keys.LONGITUDE)) requireLongitude(double(rely, Keys.LONGITUDE))
            if (rely.containsKey(Keys.ALTITUDE)) requireAltitude(double(rely, Keys.ALTITUDE))
            if (rely.containsKey(Keys.SPEED)) requireSpeed(double(rely, Keys.SPEED))
            if (rely.containsKey(Keys.ACCURACY)) requireAccuracy(float(rely, Keys.ACCURACY))
            if (rely.containsKey(Keys.BEARING)) requireBearing(double(rely, Keys.BEARING))
        }
    }

    private fun validateGnssUpdateStatus(rely: Bundle) {
        if (rely.containsKey(Keys.MIN_SATELLITES)) {
            val minSatellites = rely.getInt(Keys.MIN_SATELLITES)
            if (minSatellites !in 0..64) {
                throw IllegalArgumentException("Invalid GNSS min satellite count: $minSatellites")
            }
        }
    }

    private fun validateNmeaUpdateStream(rely: Bundle) {
        requirePayloadLimit(rely, maxHexBytes = 1024, maxJsonChars = 8192)
    }

    private fun validateWifiUpdateConnected(rely: Bundle) {
        requireOptionalMac(rely, Keys.MOCK_BSSID)
        requireOptionalMac(rely, Keys.MOCK_MAC)
        rely.getString(Keys.MOCK_SSID)?.let { requireStringLength(Keys.MOCK_SSID, it, 128) }
        rely.getString(Keys.MOCK_IP)?.let { requireStringLength(Keys.MOCK_IP, it, 64) }
    }

    private fun validateWifiUpdateScan(rely: Bundle) {
        val count = rely.getInt(Keys.PAYLOAD_COUNT, 0)
        if (count !in 0..32) throw IllegalArgumentException("Invalid WiFi scan payload count: $count")
        requirePayloadLimit(rely, maxHexBytes = 0, maxJsonChars = 32_768)
    }

    private fun validateCellUpdate(rely: Bundle) {
        val mode = rely.getString(Keys.DOMAIN_MODE)
        if (mode != null && mode !in setOf("mask_real", "replace_with_profile", "off")) {
            throw IllegalArgumentException("Invalid cell domain mode: $mode")
        }
        requirePayloadLimit(rely, maxHexBytes = 0, maxJsonChars = 16_384)
    }

    private fun validateBleUpdate(rely: Bundle) {
        val count = rely.getInt(Keys.PAYLOAD_COUNT, 0)
        if (count !in 0..64) throw IllegalArgumentException("Invalid BLE payload count: $count")
        requirePayloadLimit(rely, maxHexBytes = 4096, maxJsonChars = 32_768)
    }

    private fun validateNfcReplay(rely: Bundle) {
        requirePayloadLimit(rely, maxHexBytes = 4096, maxJsonChars = 8192)
    }

    private fun requirePayloadLimit(rely: Bundle, maxHexBytes: Int, maxJsonChars: Int) {
        rely.getString(Keys.PAYLOAD_HEX)?.let { payload ->
            if (maxHexBytes <= 0) throw IllegalArgumentException("Hex payload is not supported for this command")
            if (payload.length % 2 != 0 || !payload.all { it.isHexDigit() }) {
                throw IllegalArgumentException("Invalid hex payload")
            }
            if (payload.length / 2 > maxHexBytes) {
                throw IllegalArgumentException("Hex payload too large: ${payload.length / 2} > $maxHexBytes")
            }
        }
        rely.getString(Keys.PAYLOAD_JSON)?.let { payload ->
            requireStringLength(Keys.PAYLOAD_JSON, payload, maxJsonChars)
        }
    }

    private fun requireOptionalMac(rely: Bundle, key: String) {
        rely.getString(key)?.takeIf { it.isNotBlank() }?.let { value ->
            if (!macAddressRegex.matches(value)) throw IllegalArgumentException("Invalid MAC command field '$key'")
        }
    }

    private fun validateStringArrayList(key: String, values: ArrayList<*>) {
        if (values.size > DOMAIN_COMMAND_MAX_LIST_ITEMS) {
            throw IllegalArgumentException("Domain command list too large: $key")
        }
        values.forEachIndexed { index, value ->
            if (value is String) requireStringLength("$key[$index]", value, DOMAIN_COMMAND_MAX_STRING_CHARS)
        }
    }

    private fun requireStringLength(key: String, value: String, maxChars: Int) {
        if (value.length > maxChars) {
            throw IllegalArgumentException("Domain command string field '$key' too long: ${value.length} > $maxChars")
        }
    }

    /**
     * Apply a per-UID policy table push (`uid_targets_json`, Kuroko SET_TARGETS semantics).
     * JSON format: {"uid:policy_flags": ...} as array of [uid, policies] pairs, or
     * object mapping "uid" strings to policy bitmasks. Malformed input is rejected
     * without throwing (never crash system_server on config error).
     */
    fun applyUidTargets(rely: Bundle): Int {
        if (rely.containsKey(Keys.UID_TARGETS_CLEAR) &&
            Extract.booleanOrNull(rely, Keys.UID_TARGETS_CLEAR) == true
        ) {
            UidPolicy.clearTargets()
            return 0
        }
        val json = Extract.string(rely, Keys.UID_TARGETS_JSON) ?: return UidPolicy.targetCount()
        return try {
            val parsed = HashMap<Int, Int>()
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val pair = arr.optJSONArray(i) ?: continue
                if (pair.length() < 2) continue
                val uid = pair.optInt(0, -1)
                val policies = pair.optInt(1, 0)
                if (uid > 0 && policies > 0) parsed[uid] = policies
            }
            UidPolicy.setTargets(parsed)
        } catch (_: Throwable) {
            UidPolicy.targetCount()
        }
    }

    fun applyPutConfig(rely: Bundle) {
        with(Extract) {
            val importedDomainConfig = importDomainConfig(rely)
            val parsedSpeed = if (rely.containsKey(Keys.SPEED)) requireSpeed(double(rely, Keys.SPEED)) else null
            val parsedLatitude = if (rely.containsKey(Keys.LATITUDE)) requireLatitude(double(rely, Keys.LATITUDE)) else null
            val parsedLongitude = if (rely.containsKey(Keys.LONGITUDE)) requireLongitude(double(rely, Keys.LONGITUDE)) else null
            val parsedAltitude = if (rely.containsKey(Keys.ALTITUDE)) requireAltitude(double(rely, Keys.ALTITUDE)) else null
            val parsedAccuracy = if (rely.containsKey(Keys.ACCURACY)) requireAccuracy(float(rely, Keys.ACCURACY)) else null
            val parsedBearing = if (rely.containsKey(Keys.BEARING)) requireBearing(double(rely, Keys.BEARING)) else null
            val parsedMinSpeed = if (rely.containsKey(Keys.MIN_SPEED)) requireSpeed(double(rely, Keys.MIN_SPEED), Keys.MIN_SPEED) else null
            val parsedMaxSpeed = if (rely.containsKey(Keys.MAX_SPEED)) requireSpeed(double(rely, Keys.MAX_SPEED), Keys.MAX_SPEED) else null
            val parsedSpeedAmplitude = if (rely.containsKey(Keys.SPEED_AMPLITUDE)) requireSpeed(double(rely, Keys.SPEED_AMPLITUDE), Keys.SPEED_AMPLITUDE) else null

            synchronized(FakeLoc) {
                if (importedDomainConfig != null) applyDomainConfig(importedDomainConfig)
                if (rely.containsKey(Keys.ENABLE)) FakeLoc.enable = boolean(rely, Keys.ENABLE)
                if (parsedLatitude != null) FakeLoc.latitude = parsedLatitude
                if (parsedLongitude != null) FakeLoc.longitude = parsedLongitude
                if (parsedSpeed != null) FakeLoc.speed = parsedSpeed
                if (parsedAltitude != null) FakeLoc.altitude = parsedAltitude
                if (parsedAccuracy != null) FakeLoc.accuracy = parsedAccuracy
                if (parsedBearing != null) {
                    FakeLoc.bearing = parsedBearing
                    FakeLoc.hasBearings = true
                }

                if (rely.containsKey(Keys.ENABLE_MOCK_GNSS)) FakeLoc.enableMockGnss = boolean(rely, Keys.ENABLE_MOCK_GNSS)
                if (rely.containsKey(Keys.ENABLE_NMEA)) FakeLoc.enableNMEA = boolean(rely, Keys.ENABLE_NMEA)
                if (rely.containsKey(Keys.ENABLE_AGPS)) FakeLoc.enableAGPS = boolean(rely, Keys.ENABLE_AGPS)
                if (BuildConfig.ENABLE_WIFI_MOCK) {
                    if (rely.containsKey(Keys.ENABLE_MOCK_WIFI)) FakeLoc.enableMockWifi = boolean(rely, Keys.ENABLE_MOCK_WIFI)
                    if (rely.containsKey(Keys.DISABLE_WIFI_SCAN)) FakeLoc.disableWifiScan = boolean(rely, Keys.DISABLE_WIFI_SCAN)
                    if (rely.containsKey(Keys.MOCK_SSID)) FakeLoc.mockSSID = string(rely, Keys.MOCK_SSID) ?: ""
                    if (rely.containsKey(Keys.MOCK_BSSID)) FakeLoc.mockBSSID = string(rely, Keys.MOCK_BSSID) ?: ""
                    if (rely.containsKey(Keys.MOCK_MAC)) FakeLoc.mockMac = string(rely, Keys.MOCK_MAC) ?: ""
                    if (rely.containsKey(Keys.MOCK_IP)) FakeLoc.mockIp = string(rely, Keys.MOCK_IP) ?: ""
                    if (rely.containsKey(Keys.MOCK_WIFI_SCAN_COUNT)) FakeLoc.mockWifiScanCount = int(rely, Keys.MOCK_WIFI_SCAN_COUNT)
                } else {
                    FakeLoc.enableMockWifi = false
                    FakeLoc.disableWifiScan = false
                    FakeLoc.hookWifi = false
                    FakeLoc.mockWifiList = emptyList()
                }

                if (rely.containsKey(Keys.HIDE_BASE_STATION)) FakeLoc.hideBaseStation = boolean(rely, Keys.HIDE_BASE_STATION)
                if (rely.containsKey(Keys.NEED_DOWNGRADE_TO_2G)) FakeLoc.needDowngradeToCdma = boolean(rely, Keys.NEED_DOWNGRADE_TO_2G)
                if (rely.containsKey(Keys.MIN_SATELLITES)) FakeLoc.minSatellites = sanitizeMinSatellites(int(rely, Keys.MIN_SATELLITES))

                if (rely.containsKey(Keys.HOOK_SENSOR)) FakeLoc.hookSensor = boolean(rely, Keys.HOOK_SENSOR)
                if (rely.containsKey(Keys.ENABLE_STEP_COUNT_MOCK)) FakeLoc.enableStepCountMock = boolean(rely, Keys.ENABLE_STEP_COUNT_MOCK)
                if (rely.containsKey(Keys.STEP_COUNT)) FakeLoc.stepCount = int(rely, Keys.STEP_COUNT)
                if (rely.containsKey(Keys.ENABLE_SHAKE_MOCK)) FakeLoc.enableShakeMock = boolean(rely, Keys.ENABLE_SHAKE_MOCK)
                if (rely.containsKey(Keys.ENABLE_MOTION_MOCK)) FakeLoc.sensorData.enableMotionMock = boolean(rely, Keys.ENABLE_MOTION_MOCK)
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

                if (parsedMinSpeed != null) FakeLoc.minSpeed = parsedMinSpeed
                if (parsedMaxSpeed != null) FakeLoc.maxSpeed = parsedMaxSpeed
                if (rely.containsKey(Keys.ENABLE_DEBUG_LOG)) FakeLoc.enableDebugLog = boolean(rely, Keys.ENABLE_DEBUG_LOG)
                if (BuildConfig.ENABLE_WIFI_MOCK && rely.containsKey(Keys.HOOK_WIFI)) {
                    FakeLoc.wifiData.hookWifi = boolean(rely, Keys.HOOK_WIFI)
                }
                if (parsedSpeedAmplitude != null) FakeLoc.speedAmplitude = parsedSpeedAmplitude
                if (rely.containsKey(Keys.HIDE_MOCK)) FakeLoc.locationData.hideMock = boolean(rely, Keys.HIDE_MOCK)
                if (rely.containsKey(Keys.DISABLE_NETWORK_LOCATION)) FakeLoc.disableNetworkLocation = boolean(rely, Keys.DISABLE_NETWORK_LOCATION)
                if (rely.containsKey(Keys.RINEX_PATH)) {
                    val nextRinexPath = string(rely, Keys.RINEX_PATH)?.takeIf { it.isNotBlank() }
                    FakeLoc.rinexPath = nextRinexPath
                }

                if (!rely.containsKey(Keys.DISABLE_REGISTER_LOCATION_LISTENER) && rely.containsKey(LegacyKeys.DISABLE_REGISTER_MISSPELLED)) {
                    FakeLoc.disableRegisterLocationListener = boolean(rely, LegacyKeys.DISABLE_REGISTER_MISSPELLED)
                }
                if (!rely.containsKey(Keys.LOOP_BROADCAST_LOCATION) && rely.containsKey(LegacyKeys.LOOP_BROADCAST_MISSPELLED)) {
                    FakeLoc.loopBroadcastLocation = boolean(rely, LegacyKeys.LOOP_BROADCAST_MISSPELLED)
                }
                if (FakeLoc.enableStepCountMock || FakeLoc.enableShakeMock || FakeLoc.sensorData.enableMotionMock) {
                    FakeLoc.hookSensor = true
                }
            }
        }
    }

    private fun importDomainConfig(rely: Bundle): MockDomainConfig? {
        if (!rely.containsKey(Keys.MOCK_DOMAIN_CONFIG_JSON)) return null
        val json = rely.getString(Keys.MOCK_DOMAIN_CONFIG_JSON)
            ?: throw IllegalArgumentException("Missing ${Keys.MOCK_DOMAIN_CONFIG_JSON}")
        requireStringLength(Keys.MOCK_DOMAIN_CONFIG_JSON, json, DOMAIN_CONFIG_JSON_MAX_CHARS)
        return runCatching {
            MockDomainConfigJson.importValidatedJson(json, legalize = true)
        }.getOrElse { error ->
            throw IllegalArgumentException("Invalid ${Keys.MOCK_DOMAIN_CONFIG_JSON}", error)
        }
    }

    private fun applyDomainConfig(config: MockDomainConfig) {
        // Wireless hooks consume this one immutable publication. Legacy FakeLoc fields below
        // remain synchronized only for older hook paths and are never the source of truth.
        val wirelessSnapshot = tv.kyshu.xposed.stability.WirelessRuntime.load(config).snapshot
        val switches = config.switches
        val locationSwitch = switches[MockDomain.LOCATION]
        if (locationSwitch != null) {
            FakeLoc.enable = locationSwitch.runtimeEnabled &&
                locationSwitch.mode != MockMode.OFF &&
                !locationSwitch.killSwitch &&
                !locationSwitch.domainGuardDisabled
        }
        FakeLoc.latitude = requireLatitude(config.location.latitude)
        FakeLoc.longitude = requireLongitude(config.location.longitude)
        FakeLoc.altitude = requireAltitude(config.location.altitudeMeters)
        FakeLoc.speed = requireSpeed(config.location.speedMetersPerSecond.toDouble())
        FakeLoc.accuracy = requireAccuracy(config.location.horizontalAccuracyMeters)
        val bearing = if (config.location.bearingDegrees >= 360f) 0.0 else config.location.bearingDegrees.toDouble()
        FakeLoc.bearing = requireBearing(bearing)
        FakeLoc.hasBearings = true
        FakeLoc.locationData.hideMock = !config.location.preserveMockFlag
        FakeLoc.locationData.coordinateOverrides = config.coordinateOverrides.toMap()
        FakeLoc.locationData.motionNoise = config.motionNoise

        switches[MockDomain.GNSS]?.let { switch ->
            FakeLoc.enableMockGnss = switch.runtimeEnabled &&
                switch.mode != MockMode.OFF &&
                !switch.killSwitch &&
                !switch.domainGuardDisabled
        }
        FakeLoc.minSatellites = sanitizeMinSatellites(config.gnss.minSatelliteCount)

        switches[MockDomain.NMEA]?.let { switch ->
            FakeLoc.enableNMEA = (switch.runtimeEnabled || config.nmea.enabled) &&
                switch.mode != MockMode.OFF &&
                !switch.killSwitch &&
                !switch.domainGuardDisabled
        } ?: run {
            FakeLoc.enableNMEA = config.nmea.enabled
        }

        switches[MockDomain.SENSOR]?.let { switch ->
            FakeLoc.hookSensor = switch.runtimeEnabled &&
                switch.mode != MockMode.OFF &&
                !switch.killSwitch &&
                !switch.domainGuardDisabled
        }

        if (BuildConfig.ENABLE_WIFI_MOCK) {
            val connectedSwitch = switches[MockDomain.WLAN_CONNECTED]
            val scanSwitch = switches[MockDomain.WLAN_SCAN]
            FakeLoc.enableMockWifi = wirelessSnapshot.wifiConnectedEnabled || wirelessSnapshot.wifiScanEnabled
            FakeLoc.hookWifi = wirelessSnapshot.wifiConnectedEnabled
            FakeLoc.disableWifiScan = !wirelessSnapshot.wifiScanEnabled
            wirelessSnapshot.wifiConnected?.let { profile ->
                FakeLoc.mockSSID = profile.ssid
                FakeLoc.mockBSSID = profile.bssid
                FakeLoc.mockMac = profile.macAddress
                FakeLoc.mockIp = profile.ipv4Address
            }
            FakeLoc.mockWifiScanCount = wirelessSnapshot.wifiScanLimit
            FakeLoc.mockWifiList = wirelessSnapshot.wifiScan
        } else {
            FakeLoc.enableMockWifi = false
            FakeLoc.disableWifiScan = false
            FakeLoc.hookWifi = false
            FakeLoc.mockWifiList = emptyList()
        }

        switches[MockDomain.CELL]?.let { switch ->
            FakeLoc.hideBaseStation = switch.runtimeEnabled &&
                switch.mode == MockMode.MASK_REAL &&
                !switch.killSwitch &&
                !switch.domainGuardDisabled
            FakeLoc.cellTowers = wirelessSnapshot.cellTowers
            FakeLoc.needDowngradeToCdma = FakeLoc.cellTowers.isEmpty() && switch.runtimeEnabled &&
                switch.mode == MockMode.REPLACE_WITH_PROFILE &&
                !switch.killSwitch &&
                !switch.domainGuardDisabled
        }

        if (BuildConfig.ENABLE_BLE_MOCK) {
            val bleSwitch = switches[MockDomain.BLE]
            FakeLoc.enableBleMock = wirelessSnapshot.bleEnabled
            FakeLoc.bleDevices = wirelessSnapshot.bleDevices.take(MAX_BLE_PROFILES)
        } else {
            FakeLoc.enableBleMock = false
            FakeLoc.bleDevices = emptyList()
        }
    }

    fun exportDomainConfigJsonFromFakeLoc(): String =
        MockDomainConfigJson.exportJson(domainConfigFromFakeLocSnapshot())

    fun exportDomainConfigJson(rely: Bundle): String =
        MockDomainConfigJson.exportJson(domainConfigFromBundle(rely))

    fun populateSyncReply(rely: Bundle, includeSensitive: Boolean = true) {
        with(FakeLoc) {
            rely.putBoolean(Keys.ENABLE, enable)
            if (includeSensitive) {
                rely.putDouble(Keys.LATITUDE, latitude)
                rely.putDouble(Keys.LONGITUDE, longitude)
                rely.putDouble(Keys.ALTITUDE, altitude)
                rely.putDouble(Keys.SPEED, speed)
                rely.putDouble(Keys.ACCURACY, accuracy.toDouble())
                rely.putDouble(Keys.BEARING, bearing)
                rely.putDouble(Keys.SPEED_AMPLITUDE, 0.0)
                rely.putString(Keys.LAST_LOCATION, null)
                rely.putBoolean(Keys.HAS_BEARINGS, hasBearings)
                rely.putString(Keys.RINEX_PATH, rinexPath)
            }
            rely.putBoolean(Keys.ENABLE_MOCK_GNSS, enableMockGnss)
            rely.putBoolean(Keys.ENABLE_AGPS, enableAGPS)
            rely.putBoolean(Keys.ENABLE_NMEA, enableNMEA)
            rely.putInt(Keys.MIN_SATELLITES, minSatellites)
            rely.putBoolean(Keys.ENABLE_MOCK_WIFI, includeSensitive && BuildConfig.ENABLE_WIFI_MOCK && enableMockWifi)
            rely.putBoolean(Keys.HOOK_WIFI, includeSensitive && BuildConfig.ENABLE_WIFI_MOCK && hookWifi)
            rely.putBoolean(Keys.DISABLE_WIFI_SCAN, includeSensitive && BuildConfig.ENABLE_WIFI_MOCK && disableWifiScan)
            if (includeSensitive && BuildConfig.ENABLE_WIFI_MOCK) {
                rely.putString(Keys.MOCK_SSID, mockSSID)
                rely.putString(Keys.MOCK_BSSID, mockBSSID)
                rely.putString(Keys.MOCK_MAC, mockMac)
                rely.putString(Keys.MOCK_IP, mockIp)
                rely.putInt(Keys.MOCK_WIFI_SCAN_COUNT, mockWifiScanCount)
            }
            rely.putBoolean(Keys.HIDE_BASE_STATION, hideBaseStation)
            rely.putBoolean(Keys.NEED_DOWNGRADE_TO_2G, needDowngradeToCdma)
            rely.putBoolean(Keys.HOOK_SENSOR, hookSensor)
            rely.putBoolean(Keys.ENABLE_STEP_COUNT_MOCK, enableStepCountMock)
            rely.putInt(Keys.STEP_COUNT, stepCount)
            rely.putLong("current_step_count", tv.kyshu.xposed.hooks.sensor.SystemSensorManagerHook.getCurrentStepCount())
            rely.putBoolean(Keys.ENABLE_SHAKE_MOCK, enableShakeMock)
            rely.putBoolean(Keys.ENABLE_MOTION_MOCK, sensorData.enableMotionMock)
            rely.putBoolean(Keys.KEEP_WALKING_WAVEFORM, keepWalkingWaveform)
            rely.putInt(Keys.DOZE_SENSOR_INTERVAL_MS, dozeSensorIntervalMs.toInt())
            if (includeSensitive) {
                rely.putFloat(Keys.GAIT_FREQ, gaitFreq)
                rely.putFloat(Keys.GAIT_AMP_SCALE, gaitAmpScale)
                rely.putString(Keys.GAIT_PARAMS_JSON, gaitParamsJson)
            }
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
            if (includeSensitive) {
                rely.putString(Keys.MOCK_DOMAIN_CONFIG_JSON, exportDomainConfigJsonFromFakeLoc())
            }
        }
    }

    private fun domainConfigFromFakeLocSnapshot(): MockDomainConfig = synchronized(FakeLoc) {
        MockDomainConfig(
            switches = domainSwitches(
                locationEnabled = FakeLoc.enable,
                gnssEnabled = FakeLoc.enableMockGnss,
                nmeaEnabled = FakeLoc.enableNMEA,
                sensorEnabled = FakeLoc.hookSensor ||
                    FakeLoc.enableStepCountMock ||
                    FakeLoc.enableShakeMock ||
                    FakeLoc.sensorData.enableMotionMock,
                wifiConnectedEnabled = BuildConfig.ENABLE_WIFI_MOCK && FakeLoc.enableMockWifi && FakeLoc.hookWifi,
                wifiScanEnabled = BuildConfig.ENABLE_WIFI_MOCK && FakeLoc.enableMockWifi && !FakeLoc.disableWifiScan,
                cellMode = when {
                    FakeLoc.hideBaseStation -> MockMode.MASK_REAL
                    FakeLoc.needDowngradeToCdma -> MockMode.REPLACE_WITH_PROFILE
                    else -> MockMode.OFF
                },
                bleEnabled = BuildConfig.ENABLE_BLE_MOCK && FakeLoc.enableBleMock,
            ),
            location = LocationProfile(
                latitude = FakeLoc.latitude,
                longitude = FakeLoc.longitude,
                altitudeMeters = FakeLoc.altitude,
                speedMetersPerSecond = FakeLoc.speed.toFloat(),
                bearingDegrees = FakeLoc.bearing.toFloat(),
                horizontalAccuracyMeters = FakeLoc.accuracy,
                preserveMockFlag = !FakeLoc.hideMock,
            ),
            gnss = GnssProfile(minSatelliteCount = FakeLoc.minSatellites),
            nmea = NmeaProfile(enabled = FakeLoc.enableNMEA),
            wifiConnected = if (BuildConfig.ENABLE_WIFI_MOCK) {
                WifiConnectedProfile(
                    ssid = FakeLoc.mockSSID,
                    bssid = FakeLoc.mockBSSID,
                    macAddress = FakeLoc.mockMac,
                    ipv4Address = FakeLoc.mockIp,
                    linkSpeedMbps = FakeLoc.wifiData.currentLinkSpeed,
                )
            } else {
                WifiConnectedProfile()
            },
            wifiScan = if (BuildConfig.ENABLE_WIFI_MOCK) {
                WifiScanProfile(
                    entries = FakeLoc.mockWifiList.take(32).map { entry ->
                        WifiScanEntry(
                            ssid = entry.ssid,
                            bssid = entry.bssid,
                            rssiDbm = entry.rssi,
                            frequencyMhz = entry.frequency,
                            capabilities = entry.capabilities,
                            standard = entry.standard.toString(),
                            channelWidth = entry.channelWidth,
                        )
                    },
                    maxEntries = FakeLoc.mockWifiScanCount.coerceIn(0, 32),
                )
            } else {
                WifiScanProfile()
            },
            cellTowers = if (BuildConfig.ENABLE_CELL_MOCK) FakeLoc.cellTowers.take(32) else emptyList(),
            bleDevices = if (BuildConfig.ENABLE_BLE_MOCK) FakeLoc.bleDevices.take(MAX_BLE_PROFILES) else emptyList(),
            coordinateOverrides = FakeLoc.locationData.coordinateOverrides,
            motionNoise = FakeLoc.locationData.motionNoise,
        ).legalize()
    }

    private fun domainConfigFromBundle(rely: Bundle): MockDomainConfig {
        val enable = rely.getBoolean(Keys.ENABLE, FakeLoc.enable)
        val gnssEnabled = rely.getBoolean(Keys.ENABLE_MOCK_GNSS, FakeLoc.enableMockGnss)
        val nmeaEnabled = rely.getBoolean(Keys.ENABLE_NMEA, FakeLoc.enableNMEA)
        val wifiEnabled = BuildConfig.ENABLE_WIFI_MOCK &&
            rely.getBoolean(Keys.ENABLE_MOCK_WIFI, FakeLoc.enableMockWifi)
        val hookWifi = BuildConfig.ENABLE_WIFI_MOCK &&
            rely.getBoolean(Keys.HOOK_WIFI, FakeLoc.hookWifi)
        val disableWifiScan = rely.getBoolean(Keys.DISABLE_WIFI_SCAN, FakeLoc.disableWifiScan)
        val cellMode = when {
            rely.getString(Keys.DOMAIN_MODE) == "mask_real" -> MockMode.MASK_REAL
            rely.getString(Keys.DOMAIN_MODE) == "replace_with_profile" -> MockMode.REPLACE_WITH_PROFILE
            rely.getBoolean(Keys.HIDE_BASE_STATION, FakeLoc.hideBaseStation) -> MockMode.MASK_REAL
            rely.getBoolean(Keys.NEED_DOWNGRADE_TO_2G, FakeLoc.needDowngradeToCdma) -> MockMode.REPLACE_WITH_PROFILE
            else -> MockMode.OFF
        }
        return MockDomainConfig(
            switches = domainSwitches(
                locationEnabled = enable,
                gnssEnabled = gnssEnabled,
                nmeaEnabled = nmeaEnabled,
                sensorEnabled = rely.getBoolean(Keys.HOOK_SENSOR, FakeLoc.hookSensor) ||
                    rely.getBoolean(Keys.ENABLE_STEP_COUNT_MOCK, FakeLoc.enableStepCountMock) ||
                    rely.getBoolean(Keys.ENABLE_SHAKE_MOCK, FakeLoc.enableShakeMock) ||
                    rely.getBoolean(Keys.ENABLE_MOTION_MOCK, FakeLoc.sensorData.enableMotionMock),
                wifiConnectedEnabled = wifiEnabled && hookWifi,
                wifiScanEnabled = wifiEnabled && !disableWifiScan,
                cellMode = cellMode,
                bleEnabled = BuildConfig.ENABLE_BLE_MOCK && FakeLoc.enableBleMock,
            ),
            location = LocationProfile(
                latitude = rely.getDouble(Keys.LATITUDE, FakeLoc.latitude),
                longitude = rely.getDouble(Keys.LONGITUDE, FakeLoc.longitude),
                altitudeMeters = rely.getDouble(Keys.ALTITUDE, FakeLoc.altitude),
                speedMetersPerSecond = rely.getDouble(Keys.SPEED, FakeLoc.speed).toFloat(),
                bearingDegrees = rely.getDouble(Keys.BEARING, FakeLoc.bearing).toFloat(),
                horizontalAccuracyMeters = if (rely.containsKey(Keys.ACCURACY)) {
                    when (val accuracy = rely.get(Keys.ACCURACY)) {
                        is Number -> accuracy.toFloat()
                        else -> FakeLoc.accuracy
                    }
                } else {
                    FakeLoc.accuracy
                },
                preserveMockFlag = !rely.getBoolean(Keys.HIDE_MOCK, FakeLoc.hideMock),
            ),
            gnss = GnssProfile(
                minSatelliteCount = rely.getInt(Keys.MIN_SATELLITES, FakeLoc.minSatellites),
            ),
            nmea = NmeaProfile(enabled = nmeaEnabled),
            wifiConnected = if (BuildConfig.ENABLE_WIFI_MOCK) {
                WifiConnectedProfile(
                    ssid = rely.getString(Keys.MOCK_SSID, FakeLoc.mockSSID).orEmpty(),
                    bssid = rely.getString(Keys.MOCK_BSSID, FakeLoc.mockBSSID).orEmpty(),
                    macAddress = rely.getString(Keys.MOCK_MAC, FakeLoc.mockMac).orEmpty(),
                    ipv4Address = rely.getString(Keys.MOCK_IP, FakeLoc.mockIp).orEmpty(),
                    linkSpeedMbps = FakeLoc.wifiData.currentLinkSpeed,
                )
            } else {
                WifiConnectedProfile()
            },
            bleDevices = if (BuildConfig.ENABLE_BLE_MOCK) FakeLoc.bleDevices.take(MAX_BLE_PROFILES) else emptyList(),
        ).legalize()
    }

    private fun domainSwitches(
        locationEnabled: Boolean,
        gnssEnabled: Boolean,
        nmeaEnabled: Boolean,
        sensorEnabled: Boolean,
        wifiConnectedEnabled: Boolean,
        wifiScanEnabled: Boolean,
        cellMode: MockMode,
        bleEnabled: Boolean,
    ): Map<MockDomain, DomainSwitch> = mapOf(
        MockDomain.LOCATION to switch(
            MockDomain.LOCATION,
            buildTimeEnabled = BuildConfig.ENABLE_LOCATION_MOCK,
            runtimeEnabled = locationEnabled,
            mode = MockMode.REPLACE_WITH_PROFILE,
        ),
        MockDomain.GNSS to switch(
            MockDomain.GNSS,
            buildTimeEnabled = BuildConfig.ENABLE_GNSS_MOCK,
            runtimeEnabled = gnssEnabled,
            mode = MockMode.REPLACE_WITH_PROFILE,
        ),
        MockDomain.NMEA to switch(
            MockDomain.NMEA,
            buildTimeEnabled = BuildConfig.ENABLE_NMEA_MOCK,
            runtimeEnabled = nmeaEnabled,
            mode = MockMode.REPLACE_WITH_PROFILE,
        ),
        MockDomain.SENSOR to switch(
            MockDomain.SENSOR,
            buildTimeEnabled = BuildConfig.ENABLE_SENSOR_MOCK,
            runtimeEnabled = sensorEnabled,
            mode = MockMode.REPLACE_WITH_PROFILE,
        ),
        MockDomain.WLAN_CONNECTED to switch(
            MockDomain.WLAN_CONNECTED,
            buildTimeEnabled = BuildConfig.ENABLE_WIFI_MOCK,
            runtimeEnabled = wifiConnectedEnabled,
            mode = MockMode.REPLACE_WITH_PROFILE,
        ),
        MockDomain.WLAN_SCAN to switch(
            MockDomain.WLAN_SCAN,
            buildTimeEnabled = BuildConfig.ENABLE_WIFI_MOCK,
            runtimeEnabled = wifiScanEnabled,
            mode = MockMode.REPLACE_WITH_PROFILE,
        ),
        MockDomain.CELL to switch(
            MockDomain.CELL,
            buildTimeEnabled = BuildConfig.ENABLE_CELL_MOCK,
            runtimeEnabled = cellMode != MockMode.OFF,
            mode = cellMode,
        ),
        MockDomain.BLE to switch(
            MockDomain.BLE,
            buildTimeEnabled = BuildConfig.ENABLE_BLE_MOCK,
            runtimeEnabled = bleEnabled,
            mode = if (bleEnabled) MockMode.REPLACE_WITH_PROFILE else MockMode.OFF,
        ),
        MockDomain.NFC to switch(
            MockDomain.NFC,
            buildTimeEnabled = BuildConfig.ENABLE_NFC_MOCK,
            runtimeEnabled = false,
            mode = MockMode.OFF,
        ),
    )

    private fun switch(
        domain: MockDomain,
        buildTimeEnabled: Boolean,
        runtimeEnabled: Boolean,
        mode: MockMode,
    ): DomainSwitch = DomainSwitch(
        domain = domain,
        buildTimeEnabled = buildTimeEnabled,
        runtimeEnabled = runtimeEnabled,
        killSwitch = FakeLoc.forceDisable,
        mode = mode,
    )

    private const val DOMAIN_COMMAND_MAX_BYTES = 64 * 1024
    private const val DOMAIN_COMMAND_MAX_FIELD_CHARS = 128
    private const val DOMAIN_COMMAND_MAX_STRING_CHARS = 4096
    private const val DOMAIN_COMMAND_MAX_LIST_ITEMS = 256
    private const val DOMAIN_CONFIG_JSON_MAX_CHARS = 64 * 1024
    const val MAX_BLE_PROFILES = 64
    private val macAddressRegex = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
