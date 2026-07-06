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
import android.os.ParcelFileDescriptor
import com.alibaba.fastjson2.JSONObject
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.mock.HistoricalLocation
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.PortalIdentity
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ContentProvider for App <-> Xposed module cross-process IPC.
 *
 * Paths:
 *   content://tv.kyshu.xposed.config/cmd    — command insert/query (polling)
 *   content://tv.kyshu.xposed.config/config  — config key/value/type query
 *
 * Auth:
 *   - UID 1000 (system_server): can read commands, sensitive config, and RINEX.
 *   - Portal control app UID: can write commands and read sensitive config/RINEX.
 *   - Hook target/system processes: can read non-sensitive config and bounded RINEX only.
 */
class ConfigProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "tv.kyshu.xposed.config"
        private const val PATH_CMD = "cmd"
        private const val PATH_CONFIG = "config"
        private const val PATH_RINEX = "rinex"
        private const val CODE_CMD = 1
        private const val CODE_CONFIG = 2
        private const val CODE_RINEX = 3
        private const val SYSTEM_UID = 1000
        private const val APP_UID_BASE = 10000
        private const val MAX_COMMAND_BYTES = 256 * 1024
        private const val MIN_RINEX_BYTES = 1024L
        private const val MAX_RINEX_BYTES = 64L * 1024L * 1024L
        private const val RINEX_HEADER_SCAN_BYTES = 8192

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_CMD, CODE_CMD)
            addURI(AUTHORITY, PATH_CONFIG, CODE_CONFIG)
            addURI(AUTHORITY, PATH_RINEX, CODE_RINEX)
        }
    }

    private data class CallerAccess(
        val uid: Int,
        val isSystemUid: Boolean,
        val isSystemServer: Boolean,
        val isControlPackage: Boolean,
        val canReadRinex: Boolean,
        val sensitiveAllowed: Boolean,
    )

    /** In-memory command queue for /cmd path (FIFO). */
    private val commandQueue = ConcurrentLinkedQueue<String>()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        val access = enforceCaller()
        return when (uriMatcher.match(uri)) {
            CODE_CMD -> {
                requireCommandReader(access)
                queryCommands()
            }
            CODE_CONFIG -> queryConfig(access)
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val access = enforceCaller()
        return when (uriMatcher.match(uri)) {
            CODE_CMD -> {
                requireCommandWriter(access)
                insertCommand(values)
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String {
        enforceCaller()
        return when (uriMatcher.match(uri)) {
            CODE_CMD -> "vnd.android.cursor.item/command"
            CODE_CONFIG -> "vnd.android.cursor.dir/config"
            CODE_RINEX -> "application/octet-stream"
            else -> "vnd.android.cursor.dir/unknown"
        }
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        val access = enforceCaller()
        return when (uriMatcher.match(uri)) {
            CODE_CONFIG -> {
                requireConfigWriter(access)
                1 // Report 1 row updated to prevent unnecessary fallback-to-insert
            }
            else -> 0
        }
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        enforceCaller()
        return 0
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val access = enforceCaller()
        if (uriMatcher.match(uri) != CODE_RINEX) return null
        if (mode != "r") throw SecurityException("RINEX provider is read-only")
        requireRinexReader(access)
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)
        val file = resolveRinexFile(ctx, prefs) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }


    // ---- Auth ----

    private fun enforceCaller(): CallerAccess {
        val uid = Binder.getCallingUid()
        val ctx = context ?: throw SecurityException("No context")

        return when {
            uid == SYSTEM_UID -> {
                // system_server (UID 1000): allowed to consume command queue and read all config.
                CallerAccess(
                    uid = uid,
                    isSystemUid = true,
                    isSystemServer = true,
                    isControlPackage = false,
                    canReadRinex = true,
                    sensitiveAllowed = true,
                )
            }
            uid < APP_UID_BASE -> {
                // Other system UIDs: allow non-sensitive config and bounded RINEX reads only.
                CallerAccess(
                    uid = uid,
                    isSystemUid = true,
                    isSystemServer = false,
                    isControlPackage = false,
                    canReadRinex = true,
                    sensitiveAllowed = false,
                )
            }
            else -> {
                val packages = ctx.packageManager.getPackagesForUid(uid) ?: emptyArray()
                if (!PortalIdentity.areAllControlPackages(packages)) {
                    // Xposed module code runs inside scoped target app UIDs. These callers
                    // need a read-only bootstrap path but must not access command writes or
                    // sensitive coordinate fields through the provider.
                    return CallerAccess(
                        uid = uid,
                        isSystemUid = false,
                        isSystemServer = false,
                        isControlPackage = false,
                        canReadRinex = true,
                        sensitiveAllowed = false,
                    )
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
                CallerAccess(
                    uid = uid,
                    isSystemUid = false,
                    isSystemServer = false,
                    isControlPackage = true,
                    canReadRinex = true,
                    sensitiveAllowed = true,
                )
            }
        }
    }

    private fun requireCommandReader(access: CallerAccess) {
        if (!access.isSystemServer) {
            throw SecurityException("UID ${access.uid} cannot read command queue")
        }
    }

    private fun requireCommandWriter(access: CallerAccess) {
        if (!access.isControlPackage) {
            throw SecurityException("UID ${access.uid} cannot write command queue")
        }
    }

    private fun requireConfigWriter(access: CallerAccess) {
        if (!access.isControlPackage) {
            throw SecurityException("UID ${access.uid} cannot write provider config")
        }
    }

    private fun requireRinexReader(access: CallerAccess) {
        if (!access.canReadRinex) {
            throw SecurityException("UID ${access.uid} cannot read RINEX provider")
        }
    }

    // ---- /cmd path ----

    private fun insertCommand(values: ContentValues?): Uri? {
        val json = values?.getAsString("command") ?: return null
        val byteSize = json.toByteArray(Charsets.UTF_8).size
        if (byteSize > MAX_COMMAND_BYTES) {
            android.util.Log.w("ConfigProvider", "Command payload too large: bytes=$byteSize")
            return null
        }
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

    private fun queryConfig(access: CallerAccess): Cursor {
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
        fun criticalRead(key: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.e("ConfigProvider", "Invalid critical config key '$key'; aborting config query", e)
                throw IllegalStateException("Invalid critical config key '$key'", e)
            }
        }
        fun criticalDoubleFromFloat(key: String, prefKey: String, default: Double) =
            criticalRead(key) { addRow(cursor, key, prefs.getFloat(prefKey, default.toFloat()).toDouble().toString(), "double") }
        fun criticalFloat(key: String, prefKey: String, default: Float) =
            criticalRead(key) { addRow(cursor, key, prefs.getFloat(prefKey, default).toString(), "float") }
        val selectedLocationRaw = prefs.getString("selectedLocation", null)
        val selectedLocation = selectedLocationRaw?.takeIf { it.isNotBlank() }?.let { raw ->
            try {
                HistoricalLocation.fromString(raw)
            } catch (e: Exception) {
                android.util.Log.e("ConfigProvider", "Invalid critical config key 'selectedLocation'; aborting config query", e)
                throw IllegalStateException("Invalid critical config key 'selectedLocation'", e)
            }
        }

        // Read persisted app settings. The xposed side accepts these snake_case keys.
        bool("enable_log", "enableLog", FakeLoc.enableLog)
        bool("enable_debug_log", "debug", FakeLoc.enableDebugLog)
        bool("enable", "enable", false)
        bool("enable_mock_gnss", "enableMockGnss", FakeLoc.enableMockGnss)
        bool("enable_mock_wifi", "enableMockWifi", FakeLoc.enableMockWifi)
        bool("enable_nmea", "enableNMEA", FakeLoc.enableNMEA)
        bool("enable_agps", "enableAGPS", FakeLoc.enableAGPS)
        int("min_satellites", "minSatelliteCount", FakeLoc.minSatellites)
        safeRead("rinex_path") {
            val rinexPath = prefs.getString("rinexPath", FakeLoc.rinexPath)?.takeIf { it.isNotBlank() }
            if (rinexPath != null) {
                addRow(cursor, "rinex_path", rinexPath, "string")
            }
        }
        // Sensitive fields: only if caller is authorized
        if (access.sensitiveAllowed) {
            criticalRead("latitude") { addRow(cursor, "latitude", (selectedLocation?.lat ?: FakeLoc.latitude).toString(), "double") }
            criticalRead("longitude") { addRow(cursor, "longitude", (selectedLocation?.lon ?: FakeLoc.longitude).toString(), "double") }
            criticalDoubleFromFloat("altitude", "altitude", FakeLoc.altitude)
            criticalDoubleFromFloat("speed", "speed", FakeLoc.speed)
            criticalFloat("accuracy", "accuracy", FakeLoc.accuracy)
            safeRead("bearing") { val v = FakeLoc.bearing; addRow(cursor, "bearing", v.toString(), "double") }
        }
        bool("hook_wifi", "hookWifi", FakeLoc.hookWifi)
        bool("disable_wifi_scan", "disableWifiScan", FakeLoc.disableWifiScan)
        string("mock_ssid", "mockSSID", FakeLoc.mockSSID)
        string("mock_bssid", "mockBSSID", FakeLoc.mockBSSID)
        string("mock_mac", "mockMac", FakeLoc.mockMac)
        string("mock_ip", "mockIp", FakeLoc.mockIp)
        int("mock_wifi_scan_count", "mockWifiScanCount", FakeLoc.mockWifiScanCount)
        safeRead("hook_sensor") {
            val hookSensor = prefs.getBoolean("hookSensor", false)
            val stepMock = prefs.getBoolean("enableStepCountMock", FakeLoc.enableStepCountMock)
            val shakeMock = prefs.getBoolean("enableShakeMock", FakeLoc.enableShakeMock)
            addRow(cursor, "hook_sensor", (hookSensor || stepMock || shakeMock).toString(), "boolean")
        }
        bool("enable_step_count_mock", "enableStepCountMock", FakeLoc.enableStepCountMock)
        int("step_count", "stepCount", FakeLoc.stepCount)
        bool("enable_shake_mock", "enableShakeMock", FakeLoc.enableShakeMock)
        safeRead("enable_motion_mock") {
            val value = prefs.getBoolean(
                "enableMotionMock",
                prefs.getBoolean("hookSensor", FakeLoc.sensorData.enableMotionMock)
            )
            addRow(cursor, "enable_motion_mock", value.toString(), "boolean")
        }
        bool("keep_walking_waveform", "keepWalkingWaveform", FakeLoc.keepWalkingWaveform)
        long("doze_sensor_interval_ms", "dozeSensorIntervalMs", FakeLoc.dozeSensorIntervalMs)
        float("gait_freq", "gaitFreq", FakeLoc.gaitFreq)
        float("gait_amp_scale", "gaitAmpScale", FakeLoc.gaitAmpScale)
        string("gait_params_json", "gaitParamsJson", FakeLoc.gaitParamsJson)
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

    private fun resolveRinexFile(ctx: Context, prefs: android.content.SharedPreferences): File? {
        val rawPath = prefs.getString("rinexPath", null)?.takeIf { it.isNotBlank() }
            ?: File(ctx.filesDir, "brdc.rnx").absolutePath
        val file = runCatching { File(rawPath).canonicalFile }.getOrElse {
            android.util.Log.w("ConfigProvider", "RINEX path canonicalization failed: ${it.message}")
            return null
        }
        val filesDir = runCatching { ctx.filesDir.canonicalFile }.getOrElse {
            android.util.Log.w("ConfigProvider", "RINEX filesDir canonicalization failed: ${it.message}")
            return null
        }
        if (!isUnderDirectory(file, filesDir)) {
            android.util.Log.w("ConfigProvider", "RINEX openFile rejected outside app filesDir: path=${file.absolutePath}")
            return null
        }
        val length = file.length()
        if (!file.isFile || !file.canRead() || length < MIN_RINEX_BYTES || length > MAX_RINEX_BYTES) {
            android.util.Log.w(
                "ConfigProvider",
                "RINEX openFile unavailable: path=${file.absolutePath} exists=${file.exists()} " +
                    "canRead=${file.canRead()} length=$length"
            )
            return null
        }
        if (!hasRinexHeader(file)) {
            android.util.Log.w("ConfigProvider", "RINEX openFile rejected invalid header: path=${file.absolutePath}")
            return null
        }
        return file
    }

    private fun isUnderDirectory(file: File, directory: File): Boolean {
        val dirPath = directory.path.trimEnd(File.separatorChar)
        val filePath = file.path
        return filePath == dirPath || filePath.startsWith(dirPath + File.separator)
    }

    private fun hasRinexHeader(file: File): Boolean {
        return runCatching {
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(RINEX_HEADER_SCAN_BYTES)
                val read = input.read(buffer)
                if (read <= 0) return@use false
                String(buffer, 0, read, Charsets.US_ASCII).contains("RINEX VERSION / TYPE")
            }
        }.getOrDefault(false)
    }


    private fun addRow(cursor: MatrixCursor, key: String, value: String, type: String) {
        cursor.addRow(arrayOf(key, value, type))
    }

}
