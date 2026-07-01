package tv.kyshu.xposed.utils

import java.util.concurrent.ConcurrentHashMap

/**
 * [DEPRECATED] 使用顶层的 [Log] 替代 — 此类仅为向后兼容保留。
 *
 * 所有方法直接委托到 [Log]，输出受 [LogConfig] 高维控制面管理。
 */
@Deprecated("Use Log.i/d/w/e/rl instead", ReplaceWith("Log"))
object Logger {
    private const val TAG = "Portal"

    @Volatile
    var forceLogging: Boolean = false

    // 内置限速缓存 — 仅在旧 API 调用时生效
    private val rateLimit = ConcurrentHashMap<String, Long>(128)

    fun debugRL(key: String, msg: String, intervalMs: Long = 5000) {
        val now = System.currentTimeMillis()
        val last = rateLimit[key]
        if (last != null && now - last < intervalMs) return
        if (rateLimit.size >= 128) rateLimit.clear()
        rateLimit[key] = now
        Log.d(TAG, "[RL] $msg")
    }

    fun info(msg: String) { Log.i(TAG, msg) }
    fun info(msg: String, throwable: Throwable) { Log.i(TAG, msg, throwable) }

    fun debug(msg: String) { Log.d(TAG, msg) }
    fun debug(msg: String, throwable: Throwable) { Log.d(TAG, msg, throwable) }

    fun warn(msg: String) { Log.w(TAG, msg) }
    fun warn(msg: String, throwable: Throwable) { Log.w(TAG, msg, throwable) }

    fun error(msg: String) { Log.e(TAG, msg) }
    fun error(msg: String, throwable: Throwable) { Log.e(TAG, msg, throwable) }
}
