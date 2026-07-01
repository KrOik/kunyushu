package tv.kyshu.xposed.utils

import java.util.concurrent.ConcurrentHashMap

/**
 * Obfuscation-safe class name store.
 *
 * Centralizes all string-literal class names used in reflection
 * (XposedHelpers.findClass, loadClass, findClassIfExists). At build
 * time with R8 enabled, string literals in this object are not
 * obfuscated, but the call sites that reference them use symbols
 * rather than bare strings — this makes string-table analysis
 * of the compiled DEX harder.
 *
 * Populated during FakeLocation.initZygote() and accessed throughout
 * the hook lifecycle via cached entries.
 */
object ClassNameStore {
    private val cache = ConcurrentHashMap<String, Class<*>>()

    // --- System Service Classes ---
    const val SERVICE_MANAGER = "android.os.ServiceManager"
    const val ACTIVITY_THREAD = "android.app.ActivityThread"
    const val LOCATION_MANAGER = "android.location.LocationManager"
    const val LOCATION_MANAGER_SERVICE = "com.android.server.LocationManagerService"
    const val TELEPHONY_REGISTRY = "com.android.server.TelephonyRegistry"

    // --- Location Provider Classes ---
    const val FUSED_LOCATION_PROVIDER = "com.android.location.fused.FusedLocationProvider"
    const val LOCATION_PROVIDER_BASE = "com.android.location.provider.LocationProviderBase"

    // --- MIUI-specific ---
    const val MIUI_LOCATION_MANAGER = "com.android.server.location.MiuiLocationManager"
    const val MIUI_DEVICE_CONFIG_PROVIDER = "com.android.server.location.MiuiDeviceConfigProvider"
    const val MIUI_BLUR_LOCATION_MANAGER = "com.android.server.location.MiuiBlurLocationManagerImpl"
    const val MIUI_TELEPHONY_MANAGER = "com.miui.internal.telephony.BaseTelephonyManagerAndroidImpl"
    const val MIUI_TELEPHONY_MANAGER_EX = "miui.telephony.TelephonyManagerEx"

    // --- Third-party SDK Classes ---
    const val AMAP_NETWORK_LOCATION = "com.amap.android.location.NetworkLocationManager"
    const val BAIDU_LOCATION_CLIENT = "com.baidu.location.LocationClient"
    const val BAIDU_LOCATION_SERVICE = "com.baidu.location.BDLocation"
    const val TENCENT_LOCATION_MANAGER = "com.tencent.map.geolocation.TencentLocationManager"

    // --- Self-module ---
    const val MOCK_SERVICE_HELPER = "tv.kyshu.service.MockServiceHelper"

    /**
     * Find a class by name and cache the result.
     * Returns null if the class is not found (unlike XposedHelpers.findClass
     * which throws). Safe for use in runCatching blocks.
     */
    fun findClass(className: String, classLoader: ClassLoader): Class<*>? {
        return cache.computeIfAbsent(className) { key ->
            kotlin.runCatching {
                Class.forName(key, false, classLoader)
            }.getOrNull()
        }
    }

    /**
     * Clear the class cache. Called when the module is reset or
     * the process is being restarted.
     */
    fun invalidate() {
        cache.clear()
    }
}
