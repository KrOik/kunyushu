package tv.kyshu.xposed.utils

import android.content.Context

/**
 * [DEPRECATED] 使用 [Log.crash] / [Log.skip] 替代。
 */
@Deprecated("Use Log.crash / Log.skip", ReplaceWith("Log"))
object CrashForensics {
    fun setContext(context: Context?) = Log.init(context)
    fun skip(stepId: String, gateFailed: String) = Log.skip(stepId, gateFailed)
    fun crash(scope: String, reason: String, error: Throwable? = null) = Log.crash(scope, reason, error)
    fun readCrashLog(): String = Log.dumpCrash()
    fun clearForTest() = Log.clearForTest()
}
