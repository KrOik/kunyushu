package tv.kyshu.xposed.utils

/**
 * [DEPRECATED] 使用顶层的 [Log] 替代 — Log 内部已包含文件持久化。
 */
@Deprecated("Log.file is handled internally by Log", ReplaceWith("Log"))
object LogRouter {
    fun setContext(context: android.content.Context?) = Log.init(context)
    fun isEnabled(): Boolean = true
    fun enable() {}
    fun disable() {}
    fun info(tag: String, msg: String) = Log.i(tag, msg)
    fun warn(tag: String, msg: String) = Log.w(tag, msg)
    fun error(tag: String, msg: String) = Log.e(tag, msg)
    fun debug(tag: String, msg: String) = Log.d(tag, msg)
}
