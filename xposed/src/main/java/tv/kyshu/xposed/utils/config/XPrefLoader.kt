package tv.kyshu.xposed.utils.config

import tv.kyshu.xposed.utils.Logger
import android.os.SystemClock

/**
 * Best-effort fallback config loader for Xposed environments that already
 * expose module preferences. It must not change host app file permissions.
 *
 * Handles stale APK paths gracefully (common after module update/reinstall)
 * by throttling retries when the framework's XSharedPreferences fails.
 */
class XPrefLoader : ConfigLoader {

    override val name: String = "XSharedPreferences"

    private var prefs: de.robv.android.xposed.XSharedPreferences? = null
    private var lastFailTimeMs: Long = 0L
    private var failCount: Int = 0

    private fun getOrCreatePrefs(): de.robv.android.xposed.XSharedPreferences? {
        val existing = prefs
        if (existing != null) return existing

        // Throttle: if we failed recently, skip retry to avoid framework log noise
        val now = SystemClock.elapsedRealtime()
        if (failCount > 0) {
            val backoffMs = minOf(60_000L, (1L shl minOf(failCount, 5)) * 1000L)
            if (now - lastFailTimeMs < backoffMs) return null
        }

        return try {
            de.robv.android.xposed.XSharedPreferences(
                "com.kunyushu.kunyushu",
                "gnss_debug"
            ).also { prefs = it; failCount = 0 }
        } catch (t: Throwable) {
            lastFailTimeMs = now
            failCount++
            Logger.warn("XPrefLoader: Failed to create XSharedPreferences (attempt $failCount)", t)
            null
        }
    }

    override fun load(): Map<String, Any?>? {
        val p = getOrCreatePrefs() ?: return null
        return try {
            p.reload()
            val all = p.all
            if (all.isEmpty()) null else all
        } catch (t: Throwable) {
            Logger.warn("XPrefLoader: Failed to load preferences", t)
            null
        }
    }
}
