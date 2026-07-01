package tv.kyshu.xposed.utils

import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

object Logger {
    private const val TAG = "Portal"
    private const val RATE_LIMIT_CAPACITY = 128

    // Per-key last-log timestamp for rate-limited debug logging
    private val rateLimitTimestamps = ConcurrentHashMap<String, Long>(RATE_LIMIT_CAPACITY)

    @Volatile
    var forceLogging: Boolean = false

    private fun isEnableLog(): Boolean {
        return forceLogging || FakeLoc.enableLog
    }

    private fun isEnableDebug(): Boolean {
        return forceLogging || (FakeLoc.enableLog && FakeLoc.enableDebugLog)
    }

    /**
     * Rate-limited debug log. Suppresses if the same [key] was logged
     * within [intervalMs]. Thread-safe, bounded to 128 entries.
     *
     * Use for hot paths (location broadcasts, sensor events) that
     * could otherwise flood logcat at >1Hz.
     */
    fun debugRL(key: String, msg: String, intervalMs: Long = 5000) {
        if (!isEnableDebug()) return
        val now = System.currentTimeMillis()
        val last = rateLimitTimestamps[key]
        if (last != null && now - last < intervalMs) return
        // Bounded map: put may exceed capacity briefly, bounded by cleanup on size check
        if (rateLimitTimestamps.size >= RATE_LIMIT_CAPACITY) {
            rateLimitTimestamps.clear()
        }
        rateLimitTimestamps[key] = now
        XposedBridge.log("[$TAG][DEBUG] $msg")
    }

    fun info(msg: String) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG] $msg")
        }
    }

    fun info(msg: String, throwable: Throwable) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG] $msg: ${throwable.stackTraceToString()}")
        }
    }

    fun debug(msg: String) {
        if (isEnableDebug()) {
            XposedBridge.log("[$TAG][DEBUG] $msg")
        }
    }

    fun debug(msg: String, throwable: Throwable) {
        if (isEnableDebug()) {
            XposedBridge.log("[$TAG][DEBUG] $msg: ${throwable.stackTraceToString()}")
        }
    }

    fun error(msg: String) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG][ERROR] $msg")
        }
        LogRouter.error("Portal", msg)
    }

    fun error(msg: String, throwable: Throwable) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG][ERROR] $msg: ${throwable.stackTraceToString()}")
        }
        LogRouter.error("Portal", "$msg: ${throwable.stackTraceToString()}")
    }

    fun warn(msg: String) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG][WARN] $msg")
        }
        LogRouter.warn("Portal", msg)
    }

    fun warn(msg: String, throwable: Throwable) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG][WARN] $msg: ${throwable.stackTraceToString()}")
        }
        LogRouter.warn("Portal", "$msg: ${throwable.stackTraceToString()}")
    }
}
