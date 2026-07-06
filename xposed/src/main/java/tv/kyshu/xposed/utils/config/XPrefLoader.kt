package tv.kyshu.xposed.utils.config

import android.os.FileObserver
import android.os.SystemClock
import java.io.File
import de.robv.android.xposed.XSharedPreferences
import tv.kyshu.xposed.utils.ChannelIdentity
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.PortalIdentity

/**
 * Best-effort fallback config loader for Xposed environments that already
 * expose module preferences. It must not change host app file permissions.
 *
 * Handles stale APK paths gracefully (common after module update/reinstall)
 * by throttling retries when the framework's XSharedPreferences fails.
 */
class XPrefLoader : ConfigLoader {

    override val name: String = "XSharedPreferences"

    private var prefs: XSharedPreferences? = null
    private var prefsLabel: String = ""
    private var lastFailTimeMs: Long = 0L
    private var failCount: Int = 0

    private data class PrefCandidate(
        val packageName: String,
        val prefName: String,
    ) {
        val label: String = "$packageName/$prefName"
    }

    companion object {
        private const val LOG_INTERVAL_MS = 60_000L
        private val SHAPE_KEYS = listOf(
            "enable",
            "enableMockGnss",
            "rinexPath",
            "hookSensor",
            "enableStepCountMock",
            "enableMotionMock",
            "stepCount",
            "enableShakeMock",
            "keepWalkingWaveform",
            "enable_mock_gnss",
            "rinex_path",
            "hook_sensor",
            "enable_step_count_mock",
            "enable_motion_mock",
            "step_count",
            "enable_shake_mock",
            "keep_walking_waveform",
            "gaitParamsJson",
            "gaitFreq",
            "gaitAmpScale",
            "gait_params_json",
            "gait_freq",
            "gait_amp_scale",
        )
        @Volatile private var lastShapeAt = 0L
        @Volatile private var lastShape = ""
        @Volatile private var lastEmptyAt = 0L
    }

    private fun candidatePrefs(): List<PrefCandidate> {
        val packages = listOf(
            PortalIdentity.APPLICATION_ID,
            PortalIdentity.LEGACY_PACKAGE_ID,
        ).distinct()
        val prefNames = listOf(
            ChannelIdentity.providerName.takeIf { it.isNotBlank() },
            "gnss_debug",
            "cmd",
        ).filterNotNull().distinct()
        return packages.flatMap { packageName ->
            prefNames.map { prefName -> PrefCandidate(packageName, prefName) }
        }
    }

    private fun shouldBackoff(): Boolean {
        // Throttle: if we failed recently, skip retry to avoid framework log noise
        val now = SystemClock.elapsedRealtime()
        if (failCount > 0) {
            val backoffMs = minOf(60_000L, (1L shl minOf(failCount, 5)) * 1000L)
            if (now - lastFailTimeMs < backoffMs) return true
        }
        return false
    }

    private fun readAll(candidate: PrefCandidate): Pair<XSharedPreferences, Map<String, *>>? {
        return try {
            val xPrefs = XSharedPreferences(candidate.packageName, candidate.prefName)
            xPrefs.reload()
            xPrefs to xPrefs.all
        } catch (t: Throwable) {
            Logger.warn("XPrefLoader: Failed to create/read XSharedPreferences for ${candidate.label}", t)
            null
        }
    }

    override fun load(): Map<String, Any?>? {
        val existing = prefs
        if (existing != null) {
            try {
                existing.reload()
                val all = existing.all
                if (all.isNotEmpty()) {
                    failCount = 0
                    logShape(prefsLabel, all)
                    return all
                }
            } catch (t: Throwable) {
                Logger.warn("XPrefLoader: Failed to reload cached preferences $prefsLabel", t)
            }
            prefs = null
            prefsLabel = ""
        }
        if (shouldBackoff()) return null

        var sawEmpty = false
        for (candidate in candidatePrefs()) {
            val loaded = readAll(candidate) ?: continue
            val all = loaded.second
            if (all.isNotEmpty()) {
                prefs = loaded.first
                prefsLabel = candidate.label
                failCount = 0
                logShape(candidate.label, all)
                return all
            }
            sawEmpty = true
        }
        val now = SystemClock.elapsedRealtime()
        lastFailTimeMs = now
        failCount++
        if (sawEmpty && now - lastEmptyAt > LOG_INTERVAL_MS) {
            lastEmptyAt = now
            Logger.warn("XPrefLoader: all candidate preference files were empty: ${candidatePrefs().joinToString { it.label }}")
        } else if (!sawEmpty) {
            Logger.warn("XPrefLoader: Failed to create XSharedPreferences (attempt $failCount)")
        }
        return null
    }

    private fun logShape(label: String, all: Map<String, *>) {
        val shape = SHAPE_KEYS
            .filter { all.containsKey(it) }
            .joinToString(prefix = "fields=[", postfix = "]") { key -> "$key=${formatShapeValue(key, all[key])}" }
            .ifEmpty { "fields=[]" }
        val status = "source=$label size=${all.size} $shape"
        val now = SystemClock.elapsedRealtime()
        if (status != lastShape || now - lastShapeAt > LOG_INTERVAL_MS) {
            lastShape = status
            lastShapeAt = now
            Logger.info("XPrefLoader: loaded config shape: $status")
        }
    }

    private fun formatShapeValue(key: String, value: Any?): String {
        return if (key.equals("gaitParamsJson", ignoreCase = true) ||
            key.equals("gait_params_json", ignoreCase = true)
        ) {
            "len=${value?.toString()?.length ?: 0}"
        } else {
            value.toString()
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
            val filesToWatch = listOf(
                ChannelIdentity.providerName.takeIf { it.isNotBlank() },
                "gnss_debug",
                "cmd",
                "${PortalIdentity.APPLICATION_ID}_preferences",
                "portal",
                "${PortalIdentity.LEGACY_PACKAGE_ID}_preferences",
            ).filterNotNull()
                .distinct()
                .map { name -> File(prefsDir, "$name.xml") }

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
