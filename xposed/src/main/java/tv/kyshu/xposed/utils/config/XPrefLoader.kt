package tv.kyshu.xposed.utils.config

import android.os.FileObserver
import android.os.SystemClock
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger

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

object XPrefWatcher {
    private var observer: FileObserver? = null
    private var lastModifyMs: Long = 0L

    /**
     * Start watching the portal SharedPreferences XML file for external
     * config changes. When a MODIFY event fires, triggers a FakeLoc.reload()
     * with 500ms debounce to avoid flood.
     */
    fun startWatching(context: android.content.Context) {
        kotlin.runCatching {
            val prefsDir = File(context.dataDir, "shared_prefs")
            val targetFile = File(prefsDir, "tv.kyshu_preferences.xml")

            // Also try alternate file names
            val filesToWatch = listOf(
                targetFile,
                File(prefsDir, "portal.xml"),
                File(prefsDir, "com.kunyushu.kunyushu_preferences.xml"),
            )

            val existingFile = filesToWatch.firstOrNull { it.exists() } ?: run {
                Logger.warn("[XPrefWatcher] No preferences file found to watch")
                return
            }

            val parentDir = existingFile.parentFile ?: return

            observer = object : FileObserver(parentDir.absolutePath, FileObserver.MODIFY) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    if (event and FileObserver.MODIFY == 0) return
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastModifyMs < 500) return // debounce 500ms
                    lastModifyMs = now

                    Logger.info("[XPrefWatcher] Config file $path modified, triggering reload")
                    kotlin.runCatching {
                        FakeLoc.reload()
                    }.onFailure { e ->
                        Logger.warn("[XPrefWatcher] reload failed", e)
                    }
                }
            }.also { it.startWatching() }

            Logger.info("[XPrefWatcher] Started watching ${existingFile.absolutePath}")
        }.onFailure { e ->
            Logger.warn("[XPrefWatcher] Failed to start file watcher", e)
        }
    }

    fun stopWatching() {
        observer?.stopWatching()
        observer = null
    }
}
