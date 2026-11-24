package tv.kyshu.xposed.stability

import tv.kyshu.xposed.utils.Log

/** Build a complete replacement locally; any non-fatal failure keeps the exact original value. */
inline fun <T> replaceOrPassthrough(
    domain: DomainGuard.Domain,
    stage: String,
    original: T,
    build: () -> T?,
): T {
    if (DomainGuard.isDomainDisabled(domain)) return original
    return try {
        val replacement = build() ?: return original
        Log.telemetry("WIRELESS_CALLBACK", "CALLBACK_REPLACED", stage,
            mapOf("domain" to domain.label, "result_type" to replacement.javaClass.simpleName))
        replacement
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        DomainGuard.tripNow(domain, stage, error)
        original
    }
}
