package tv.kyshu.map

import android.content.Context
import tv.kyshu.security.SecretStore

enum class MapEngine { AUTO, BAIDU, AMAP, GOOGLE }

class MapEngineSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("portal_map_settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(appContext)

    var preferredEngine: MapEngine
        get() = runCatching { MapEngine.valueOf(prefs.getString("engine", null).orEmpty()) }
            .getOrDefault(MapEngine.AUTO)
        set(value) { prefs.edit().putString("engine", value.name).apply() }

    fun key(engine: MapEngine): String? = when (engine) {
        MapEngine.AMAP -> secrets.get(SecretStore.AMAP_KEY)
        MapEngine.BAIDU -> secrets.get(SecretStore.BAIDU_KEY) ?: manifestBaiduKey()
        MapEngine.GOOGLE -> secrets.get(SecretStore.GOOGLE_MAPS_KEY)
        MapEngine.AUTO -> null
    }

    fun setKey(engine: MapEngine, value: String) {
        val name = when (engine) {
            MapEngine.AMAP -> SecretStore.AMAP_KEY
            MapEngine.BAIDU -> SecretStore.BAIDU_KEY
            MapEngine.GOOGLE -> SecretStore.GOOGLE_MAPS_KEY
            MapEngine.AUTO -> return
        }
        secrets.put(name, value.trim())
    }

    fun routeEngines(): List<MapEngine> {
        val configured = listOf(MapEngine.AMAP, MapEngine.BAIDU, MapEngine.GOOGLE)
            .filter { !key(it).isNullOrBlank() }
        val preferred = preferredEngine.takeUnless { it == MapEngine.AUTO }
        return buildList {
            if (preferred != null && preferred in configured) add(preferred)
            addAll(configured.filterNot { it == preferred })
        }
    }

    /** Portal currently always has the bundled Baidu renderer; other engines are used for route data when configured. */
    fun effectiveDisplayEngine(): MapEngine = when (preferredEngine) {
        MapEngine.AUTO, MapEngine.BAIDU -> MapEngine.BAIDU
        MapEngine.AMAP -> if (classAvailable("com.amap.api.maps.MapView")) MapEngine.AMAP else MapEngine.BAIDU
        MapEngine.GOOGLE -> if (classAvailable("com.google.android.gms.maps.MapView")) MapEngine.GOOGLE else MapEngine.BAIDU
    }

    private fun manifestBaiduKey(): String? = runCatching {
        val info = appContext.packageManager.getApplicationInfo(
            appContext.packageName,
            android.content.pm.PackageManager.GET_META_DATA,
        )
        info.metaData?.getString("com.baidu.lbsapi.API_KEY")
    }.getOrNull()

    private fun classAvailable(name: String) = runCatching { Class.forName(name) }.isSuccess
}
