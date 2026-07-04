package tv.kyshu.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.alibaba.fastjson2.JSONObject
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.mock.HistoricalLocation
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.PortalIdentity
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ContentProvider for App <-> Xposed module cross-process IPC.
 *
 * Paths:
 *   content://tv.kyshu.xposed.config/cmd    — command insert/query (polling)
 *   content://tv.kyshu.xposed.config/config  — config key/value/type query
 *
 * Auth:
 *   - UID 1000 (system_server, where Xposed module runs): always allowed + audit log
 *   - App UID (>= 10000): must belong to the Portal control app, plus signature check
 *   - Other system UID (< 10000): allowed but audit-logged
 */
class ConfigProvider : ContentProvider() {
    /** true if the current caller is authorized to read sensitive location data.
     *  Set by enforceCaller(); read by queryConfig() to filter coordinate fields. */
    @Volatile
    private var isSensitiveAllowed = true

    companion object {
        const val AUTHORITY = "tv.kyshu.xposed.config"
        private const val PATH_CMD = "cmd"
        private const val PATH_CONFIG = "config"
        private const val CODE_CMD = 1
        private const val CODE_CONFIG = 2

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_CMD, CODE_CMD)
            addURI(AUTHORITY, PATH_CONFIG, CODE_CONFIG)
        }
    }

    /** In-memory command queue for /cmd path (FIFO). */
    private val commandQueue = ConcurrentLinkedQueue<String>()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        enforceCaller()
        return when (uriMatcher.match(uri)) {
            CODE_CMD -> queryCommands()
            CODE_CONFIG -> queryConfig()
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceCaller()
        return when (uriMatcher.match(uri)) {
            CODE_CMD -> insertCommand(values)
            else -> null
        }
    }

    override fun getType(uri: Uri): String {
        enforceCaller()
        return when (uriMatcher.match(uri)) {
            CODE_CMD -> "vnd.android.cursor.item/command"
            CODE_CONFIG -> "vnd.android.cursor.dir/config"
            else -> "vnd.android.cursor.dir/unknown"
        }
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        return 1 // Report 1 row updated to prevent unnecessary fallback-to-insert
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0


    // ---- Auth ----

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        val ctx = context ?: throw SecurityException("No context")

        when {
            uid == 1000 -> {
                // system_server (UID 1000): always allowed for all data
                isSensitiveAllowed = true
            }
            uid < 10000 -> {
                // Other system UIDs — deny sensitive data, allow non-sensitive
                isSensitiveAllowed = false
            }
            else -> {
                val packages = ctx.packageManager.getPackagesForUid(uid) ?: emptyArray()
                if (!PortalIdentity.areAllControlPackages(packages)) {
                    throw SecurityException("UID $uid not allowed to access ConfigProvider")
                }
                val callerPackage = packages.first()
                val ourPkg = ctx.packageManager.getPackagesForUid(
                    android.os.Process.myUid()
                )?.firstOrNull() ?: throw SecurityException("Cannot determine own package")
                val theirSig = kotlin.runCatching {
                    ctx.packageManager.getPackageInfo(
                        callerPackage,
                        android.content.pm.PackageManager.GET_SIGNATURES
                    )
                }.getOrNull()?.signatures?.firstOrNull()?.toByteArray()
                val ourSig = kotlin.runCatching {
                    ctx.packageManager.getPackageInfo(
                        ourPkg,
                        android.content.pm.PackageManager.GET_SIGNATURES
                    )
                }.getOrNull()?.signatures?.firstOrNull()?.toByteArray()
                if (theirSig == null || ourSig == null || !theirSig.contentEquals(ourSig)) {
                    throw SecurityException("UID $uid signature mismatch — rejecting")
                }
                isSensitiveAllowed = true
            }
        }
    }

    // ---- /cmd path ----

    private fun insertCommand(values: ContentValues?): Uri? {
        val json = values?.getAsString("command") ?: return null
        // Cap queue to prevent OOM from uncontrolled inserts
        if (commandQueue.size >= 1024) {
            commandQueue.poll() // drain oldest
        }
        commandQueue.offer(json)
        return Uri.parse("content://$AUTHORITY/$PATH_CMD/${commandQueue.size}")
    }

    private fun queryCommands(): Cursor {
        val cursor = MatrixCursor(arrayOf("command"))
        var item = commandQueue.poll()
        while (item != null) {
            cursor.addRow(arrayOf(item))
            item = commandQueue.poll()
        }
        return cursor
    }

    // ---- /config path ----

    private fun queryConfig(): Cursor {
        val cursor = MatrixCursor(arrayOf("key", "value", "type"))
        val ctx = context ?: return cursor
        val prefs = ctx.getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)

        fun safeRead(key: String, block: () -> Unit) {
            try { block() } catch (e: Exception) {
                android.util.Log.w("ConfigProvider", "Failed to read config key '$key': ${e.message}")
            }
        }
        fun bool(key: String, prefKey: String, default: Boolean) =
            safeRead(key) { addRow(cursor, key, prefs.getBoolean(prefKey, default).toString(), "boolean") }
        fun int(key: String, prefKey: String, default: Int) =
            safeRead(key) { addRow(cursor, key, prefs.getInt(prefKey, default).toString(), "int") }
        fun long(key: String, prefKey: String, default: Long) =
            safeRead(key) { addRow(cursor, key, prefs.getLong(prefKey, default).toString(), "long") }
        fun float(key: String, prefKey: String, default: Float) =
            safeRead(key) { addRow(cursor, key, prefs.getFloat(prefKey, default).toString(), "float") }
        fun doubleFromFloat(key: String, prefKey: String, default: Double) =
            safeRead(key) { addRow(cursor, key, prefs.getFloat(prefKey, default.toFloat()).toDouble().toString(), "double") }
        fun string(key: String, prefKey: String, default: String) =
            safeRead(key) { addRow(cursor, key, prefs.getString(prefKey, default).orEmpty(), "string") }
        val selectedLocation = runCatching {
            prefs.getString("selectedLocation", null)?.let { HistoricalLocation.fromString(it) }
        }.onFailure {
            android.util.Log.w("ConfigProvider", "Failed to parse selectedLocation: ${it.message}")
        }.getOrNull()

        // Read persisted app settings. The xposed side accepts these snake_case keys.
        bool("enable_log", "enableLog", FakeLoc.enableLog)
        bool("enable_debug_log", "debug", FakeLoc.enableDebugLog)
        bool("enable", "enable", false)
        bool("enable_mock_gnss", "enableMockGnss", FakeLoc.enableMockGnss)
        bool("enable_mock_wifi", "enableMockWifi", FakeLoc.enableMockWifi)
        bool("enable_nmea", "enableNMEA", FakeLoc.enableNMEA)
        bool("enable_agps", "enableAGPS", FakeLoc.enableAGPS)
        int("min_satellites", "minSatelliteCount", FakeLoc.minSatellites)
        // Sensitive fields: only if caller is authorized
        if (isSensitiveAllowed) {
            safeRead("latitude") { addRow(cursor, "latitude", (selectedLocation?.lat ?: FakeLoc.latitude).toString(), "double") }
            safeRead("longitude") { addRow(cursor, "longitude", (selectedLocation?.lon ?: FakeLoc.longitude).toString(), "double") }
            doubleFromFloat("altitude", "altitude", FakeLoc.altitude)
            doubleFromFloat("speed", "speed", FakeLoc.speed)
            float("accuracy", "accuracy", FakeLoc.accuracy)
            safeRead("bearing") { val v = FakeLoc.bearing; addRow(cursor, "bearing", v.toString(), "double") }
        }
        bool("hook_wifi", "hookWifi", FakeLoc.hookWifi)
        bool("disable_wifi_scan", "disableWifiScan", FakeLoc.disableWifiScan)
        string("mock_ssid", "mockSSID", FakeLoc.mockSSID)
        string("mock_bssid", "mockBSSID", FakeLoc.mockBSSID)
        string("mock_mac", "mockMac", FakeLoc.mockMac)
        string("mock_ip", "mockIp", FakeLoc.mockIp)
        int("mock_wifi_scan_count", "mockWifiScanCount", FakeLoc.mockWifiScanCount)
        bool("hook_sensor", "hookSensor", false)
        bool("enable_step_count_mock", "enableStepCountMock", FakeLoc.enableStepCountMock)
        int("step_count", "stepCount", FakeLoc.stepCount)
        bool("enable_shake_mock", "enableShakeMock", FakeLoc.enableShakeMock)
        bool("enable_motion_mock", "enableMotionMock", FakeLoc.sensorData.enableMotionMock)
        bool("keep_walking_waveform", "keepWalkingWaveform", FakeLoc.keepWalkingWaveform)
        long("doze_sensor_interval_ms", "dozeSensorIntervalMs", FakeLoc.dozeSensorIntervalMs)
        float("gait_freq", "gaitFreq", FakeLoc.gaitFreq)
        float("gait_amp_scale", "gaitAmpScale", FakeLoc.gaitAmpScale)
        bool("is_app_in_background", "isAppInBackground", FakeLoc.isAppInBackground)
        bool("is_screen_off", "isScreenOff", FakeLoc.isScreenOff)
        bool("is_device_idle_mode", "isDeviceIdleMode", FakeLoc.isDeviceIdleMode)
        bool("disable_get_current_location", "disableGetCurrentLocation", FakeLoc.disableGetCurrentLocation)
        bool("disable_register_location_listener", "disableRegisterLocationListener", FakeLoc.disableRegisterLocationListener)
        bool("disable_fused_location", "disableFusedProvider", FakeLoc.disableFusedLocation)
        bool("disable_request_geofence", "disableRequestGeofence", FakeLoc.disableRequestGeofence)
        bool("disable_get_from_location", "disableGetFromLocation", FakeLoc.disableGetFromLocation)
        bool("hide_base_station", "hideBaseStation", FakeLoc.hideBaseStation)
        bool("need_downgrade_to_2g", "needDowngradeToCdma", FakeLoc.needDowngradeToCdma)
        bool("loop_broadcast_location", "loopBroadcastLocation", FakeLoc.loopBroadcastLocation)
        bool("hide_mock", "hideMock", FakeLoc.locationData.hideMock)
        bool("disable_network_location", "disableNetworkLocation", FakeLoc.disableNetworkLocation)
        doubleFromFloat("speed_amplitude", "speedAmplitude", FakeLoc.speedAmplitude)
        doubleFromFloat("min_speed", "minSpeed", FakeLoc.minSpeed)
        doubleFromFloat("max_speed", "maxSpeed", FakeLoc.maxSpeed)

        return cursor
    }


    private fun addRow(cursor: MatrixCursor, key: String, value: String, type: String) {
        cursor.addRow(arrayOf(key, value, type))
    }

}
