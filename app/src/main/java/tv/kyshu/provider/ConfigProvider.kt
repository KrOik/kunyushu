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
    /** true if the current caller is authorized to read sensitive location data. */
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

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    // ---- Auth ----

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        val ctx = context ?: throw SecurityException("No context")

        when {
            uid == 1000 -> {
                // system_server (UID 1000): always allowed for all data
                isSensitiveAllowed = true
                val pkgs = kotlin.runCatching {
                    ctx.packageManager.getPackagesForUid(uid)
                }.getOrNull()
                android.util.Log.i("ConfigProvider", "system_server(1000) call packages=$pkgs")
            }
            uid < 10000 -> {
                // Other system UIDs — deny sensitive data, allow non-sensitive
                isSensitiveAllowed = false
                val pkgs = kotlin.runCatching {
                    ctx.packageManager.getPackagesForUid(uid)
                }.getOrNull()
                android.util.Log.w("ConfigProvider", "system UID $uid call packages=$pkgs, sensitive data denied")
            }
            else -> {
                val packages = ctx.packageManager.getPackagesForUid(uid) ?: emptyArray()
                if (packages.none { it == "tv.kyshu" }) {
                    throw SecurityException("UID $uid ($packages) not allowed to access ConfigProvider")
                }
                val ourPkg = ctx.packageManager.getPackagesForUid(
                    android.os.Process.myUid()
                )?.firstOrNull() ?: throw SecurityException("Cannot determine own package")
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
                isSensitiveAllowed = true
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

        fun safeRead(key: String, block: () -> Unit) {
            try { block() } catch (e: Exception) {
                android.util.Log.w("ConfigProvider", "Failed to read config key '$key': ${e.message}")
            }
        }

        // Read config values from FakeLoc (static cached state synced from xposed module)
        safeRead("enable_log") { val v = FakeLoc.enableLog; addRow(cursor, "enable_log", v.toString(), "boolean") }
        safeRead("enable_debug_log") { val v = FakeLoc.enableDebugLog; addRow(cursor, "enable_debug_log", v.toString(), "boolean") }
        safeRead("enable") { val v = FakeLoc.enable; addRow(cursor, "enable", v.toString(), "boolean") }
        safeRead("enable_mock_gnss") { val v = FakeLoc.enableMockGnss; addRow(cursor, "enable_mock_gnss", v.toString(), "boolean") }
        safeRead("enable_mock_wifi") { val v = FakeLoc.enableMockWifi; addRow(cursor, "enable_mock_wifi", v.toString(), "boolean") }
        safeRead("latitude") { val v = FakeLoc.latitude; addRow(cursor, "latitude", v.toString(), "double") }
        safeRead("longitude") { val v = FakeLoc.longitude; addRow(cursor, "longitude", v.toString(), "double") }
        safeRead("altitude") { val v = FakeLoc.altitude; addRow(cursor, "altitude", v.toString(), "double") }
        safeRead("speed") { val v = FakeLoc.speed; addRow(cursor, "speed", v.toString(), "double") }
        safeRead("accuracy") { val v = FakeLoc.accuracy; addRow(cursor, "accuracy", v.toString(), "float") }
        safeRead("bearing") { val v = FakeLoc.bearing; addRow(cursor, "bearing", v.toString(), "double") }
        safeRead("hook_sensor") { val v = FakeLoc.hookSensor; addRow(cursor, "hook_sensor", v.toString(), "boolean") }
        safeRead("is_app_in_background") { val v = FakeLoc.isAppInBackground; addRow(cursor, "is_app_in_background", v.toString(), "boolean") }
        safeRead("is_screen_off") { val v = FakeLoc.isScreenOff; addRow(cursor, "is_screen_off", v.toString(), "boolean") }
        safeRead("is_device_idle_mode") { val v = FakeLoc.isDeviceIdleMode; addRow(cursor, "is_device_idle_mode", v.toString(), "boolean") }
        safeRead("disable_fused_location") { val v = FakeLoc.disableFusedLocation; addRow(cursor, "disable_fused_location", v.toString(), "boolean") }
        safeRead("hide_mock") { val v = FakeLoc.locationData.hideMock; addRow(cursor, "hide_mock", v.toString(), "boolean") }
        safeRead("disable_network_location") { val v = FakeLoc.disableNetworkLocation; addRow(cursor, "disable_network_location", v.toString(), "boolean") }
        safeRead("speed_amplitude") { val v = FakeLoc.speedAmplitude; addRow(cursor, "speed_amplitude", v.toString(), "double") }
        safeRead("min_speed") { val v = FakeLoc.minSpeed; addRow(cursor, "min_speed", v.toString(), "double") }

        return cursor
    }

}
