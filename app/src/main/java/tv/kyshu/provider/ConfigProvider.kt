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
import tv.kyshu.xposed.utils.FakeLoc
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
 *   - App UID (>= 10000): must belong to package "tv.kyshu", plus signature check
 *   - Other system UID (< 10000): allowed but audit-logged
 */
class ConfigProvider : ContentProvider() {

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

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    // ---- Auth ----

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        val ctx = context ?: throw SecurityException("No context")

        when {
            uid == 1000 -> {
                val pkgs = kotlin.runCatching {
                    ctx.packageManager.getPackagesForUid(uid)
                }.getOrNull()
                android.util.Log.i("ConfigProvider", "system_server(1000) call packages=$pkgs")
            }
            uid < 10000 -> {
                val pkgs = kotlin.runCatching {
                    ctx.packageManager.getPackagesForUid(uid)
                }.getOrNull()
                android.util.Log.w("ConfigProvider", "system UID $uid call packages=$pkgs")
            }
            else -> {
                val packages = ctx.packageManager.getPackagesForUid(uid) ?: emptyArray()
                if (packages.none { it == "tv.kyshu" }) {
                    throw SecurityException("UID $uid ($packages) not allowed to access ConfigProvider")
                }
                val ourPkg = ctx.packageManager.getPackagesForUid(
                    android.os.Process.myUid()
                )?.firstOrNull() ?: return
                val theirSig = kotlin.runCatching {
                    ctx.packageManager.getPackageInfo(
                        packages.first(),
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
                    android.util.Log.w("ConfigProvider",
                        "tv.kyshu package $uid signature mismatch — rejecting")
                    throw SecurityException("UID $uid signature mismatch")
                }
            }
        }
    }

    // ---- /cmd path ----

    private fun insertCommand(values: ContentValues?): Uri? {
        val json = values?.getAsString("command") ?: return null
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

        // Read config values from FakeLoc (static cached state synced from xposed module)
        try {
            val enableLog = FakeLoc.enableLog
            addRow(cursor, "enable_log", enableLog.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val enableDebugLog = FakeLoc.enableDebugLog
            addRow(cursor, "enable_debug_log", enableDebugLog.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val enable = FakeLoc.enable
            addRow(cursor, "enable", enable.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val enableMockGnss = FakeLoc.enableMockGnss
            addRow(cursor, "enable_mock_gnss", enableMockGnss.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val enableMockWifi = FakeLoc.enableMockWifi
            addRow(cursor, "enable_mock_wifi", enableMockWifi.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val latitude = FakeLoc.latitude
            addRow(cursor, "latitude", latitude.toString(), "double")
        } catch (_: Exception) {}

        try {
            val longitude = FakeLoc.longitude
            addRow(cursor, "longitude", longitude.toString(), "double")
        } catch (_: Exception) {}

        try {
            val altitude = FakeLoc.altitude
            addRow(cursor, "altitude", altitude.toString(), "double")
        } catch (_: Exception) {}

        try {
            val speed = FakeLoc.speed
            addRow(cursor, "speed", speed.toString(), "double")
        } catch (_: Exception) {}

        try {
            val accuracy = FakeLoc.accuracy
            addRow(cursor, "accuracy", accuracy.toString(), "float")
        } catch (_: Exception) {}

        try {
            val bearing = FakeLoc.bearing
            addRow(cursor, "bearing", bearing.toString(), "double")
        } catch (_: Exception) {}

        try {
            val hookSensor = FakeLoc.hookSensor
            addRow(cursor, "hook_sensor", hookSensor.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val isAppInBackground = FakeLoc.isAppInBackground
            addRow(cursor, "is_app_in_background", isAppInBackground.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val isScreenOff = FakeLoc.isScreenOff
            addRow(cursor, "is_screen_off", isScreenOff.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val isDeviceIdleMode = FakeLoc.isDeviceIdleMode
            addRow(cursor, "is_device_idle_mode", isDeviceIdleMode.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val disableFusedLocation = FakeLoc.disableFusedLocation
            addRow(cursor, "disable_fused_location", disableFusedLocation.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val hideMock = FakeLoc.locationData.hideMock
            addRow(cursor, "hide_mock", hideMock.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val disableNetworkLocation = FakeLoc.disableNetworkLocation
            addRow(cursor, "disable_network_location", disableNetworkLocation.toString(), "boolean")
        } catch (_: Exception) {}

        try {
            val speedAmplitude = FakeLoc.speedAmplitude
            addRow(cursor, "speed_amplitude", speedAmplitude.toString(), "double")
        } catch (_: Exception) {}

        try {
            val minSpeed = FakeLoc.minSpeed
            addRow(cursor, "min_speed", minSpeed.toString(), "double")
        } catch (_: Exception) {}

        try {
            val maxSpeed = FakeLoc.maxSpeed
            addRow(cursor, "max_speed", maxSpeed.toString(), "double")
        } catch (_: Exception) {}

        return cursor
    }

    private fun addRow(cursor: MatrixCursor, key: String, value: String, type: String) {
        cursor.addRow(arrayOf(key, value, type))
    }
}
