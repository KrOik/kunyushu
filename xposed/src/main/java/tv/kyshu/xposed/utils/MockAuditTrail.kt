package tv.kyshu.xposed.utils

/**
 * [DEPRECATED] 使用 [Log.audit] 替代。
 */
@Deprecated("Use Log.audit", ReplaceWith("Log"))
object MockAuditTrail {
    fun config(tag: String, message: String) = Log.audit("CONFIG", tag, message)
    fun hookInit(tag: String, message: String) = Log.audit("HOOK_INIT", tag, message)
    fun skip(tag: String, reason: String) = Log.audit("SKIP", tag, reason)
    fun inject(tag: String, detail: String) = Log.audit("INJECT", tag, detail)
    fun state(tag: String, message: String) = Log.audit("STATE", tag, message)
    fun heartbeat(tag: String, message: String) = Log.audit("HEARTBEAT", tag, message)
    fun record(category: MockAuditTrail.Category, tag: String, message: String) = Log.audit(category.name, tag, message)
    fun snapshot(): List<Any> = emptyList()
    fun dump(): String = Log.dumpAudit()
    fun dump(category: MockAuditTrail.Category): String = Log.dumpAudit(category.name)
    fun dumpLast(n: Int): String = Log.dumpAudit().lines().takeLast(n.coerceAtMost(256)).joinToString("\n")
    fun clear() {}

    enum class Category {
        CONFIG, HOOK_INIT, SKIP, INJECT, STATE, HEARTBEAT
    }
}
