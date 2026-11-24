package tv.kyshu.environment

import android.content.Context

class EnvironmentSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("environment_settings", Context.MODE_PRIVATE)

    var wifiReplayEnabled: Boolean
        get() = prefs.getBoolean("wifi_replay", true)
        set(value) = prefs.edit().putBoolean("wifi_replay", value).apply()
    var cellReplayEnabled: Boolean
        get() = prefs.getBoolean("cell_replay", true)
        set(value) = prefs.edit().putBoolean("cell_replay", value).apply()
    var bleReplayEnabled: Boolean
        get() = prefs.getBoolean("ble_replay", true)
        set(value) = prefs.edit().putBoolean("ble_replay", value).apply()
    var scanWifi: Boolean
        get() = prefs.getBoolean("scan_wifi", true)
        set(value) = prefs.edit().putBoolean("scan_wifi", value).apply()
    var scanCell: Boolean
        get() = prefs.getBoolean("scan_cell", true)
        set(value) = prefs.edit().putBoolean("scan_cell", value).apply()
    var scanBle: Boolean
        get() = prefs.getBoolean("scan_ble", true)
        set(value) = prefs.edit().putBoolean("scan_ble", value).apply()
    var scanIntervalSeconds: Int
        get() = prefs.getInt("scan_interval", 10).coerceIn(5, 60)
        set(value) = prefs.edit().putInt("scan_interval", value.coerceIn(5, 60)).apply()
    var minimumScanDistanceMeters: Float
        get() = prefs.getFloat("scan_distance", 20f).coerceIn(1f, 500f)
        set(value) = prefs.edit().putFloat("scan_distance", value.coerceIn(1f, 500f)).apply()
    var replayRadiusMeters: Float
        get() = prefs.getFloat("replay_radius", 50f).coerceIn(5f, 500f)
        set(value) = prefs.edit().putFloat("replay_radius", value.coerceIn(5f, 500f)).apply()
    var naturalDriftEnabled: Boolean
        get() = prefs.getBoolean("natural_drift", true)
        set(value) = prefs.edit().putBoolean("natural_drift", value).apply()
    var liveMapUpdateEnabled: Boolean
        get() = prefs.getBoolean("live_map_update", true)
        set(value) = prefs.edit().putBoolean("live_map_update", value).apply()
}
