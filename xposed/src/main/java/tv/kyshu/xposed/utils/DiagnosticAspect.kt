package tv.kyshu.xposed.utils

import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Member
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central diagnostic aspect for hook callbacks and business entry points.
 *
 * This is intentionally implemented as a lightweight runtime aspect instead of
 * bytecode weaving, because the module already has a centralized Xposed helper
 * layer and runs inside system processes where build-time weaving is riskier.
 */
object DiagnosticAspect {
    private const val TAG = "AOP"
    private const val SLOW_CALLBACK_MS = 25L
    private const val SLOW_BUSINESS_MS = 50L
    private const val FIRST_HITS = 3
    private const val PERIODIC_HIT = 200
    private const val MAX_LABELS = 512

    private val hitCounts = ConcurrentHashMap<String, AtomicInteger>()

    fun hookInstall(owner: Class<*>, methodName: String, unhookCount: Int, mode: String) {
        val label = "${owner.name}#$methodName"
        if (unhookCount == 0) {
            HookInstallReporter.recordInstall(
                label = label,
                installed = 0,
                mode = mode,
                classLoader = owner.classLoader,
                extra = mapOf("owner" to owner.name, "method" to methodName),
            )
            logLimited("install.miss.$label.$mode", "[install-miss] $mode $label")
        } else {
            HookInstallReporter.recordInstall(
                label = label,
                installed = unhookCount,
                mode = mode,
                classLoader = owner.classLoader,
                extra = mapOf("owner" to owner.name, "method" to methodName),
            )
            logLimited("install.hit.$label.$mode", "[install] $mode $label overloads=$unhookCount")
        }
    }

    fun hookInstall(method: Member, installed: Boolean, mode: String) {
        val label = methodLabel(method)
        if (installed) {
            HookInstallReporter.recordInstall(
                label = label,
                installed = 1,
                mode = mode,
                classLoader = method.declaringClass.classLoader,
                extra = mapOf("owner" to method.declaringClass.name, "method" to method.name),
            )
            logLimited("install.hit.$label.$mode", "[install] $mode $label")
        } else {
            HookInstallReporter.recordInstall(
                label = label,
                installed = 0,
                mode = mode,
                classLoader = method.declaringClass.classLoader,
                extra = mapOf("owner" to method.declaringClass.name, "method" to method.name),
            )
            logLimited("install.miss.$label.$mode", "[install-miss] $mode $label")
        }
    }

    fun traceHookCallback(
        phase: String,
        param: XC_MethodHook.MethodHookParam,
        block: () -> Unit,
    ) {
        val label = "${methodLabel(param.method)}:$phase"
        val start = SystemClock.elapsedRealtimeNanos()
        val ordinal = increment(label)
        if (shouldLogHit(ordinal)) {
            Log.i(TAG, "[enter] $label hit=$ordinal args=${argTypes(param.args)} result=${typeName(param.result)}")
        }
        try {
            block()
            val elapsedMs = elapsedMs(start)
            if (shouldLogHit(ordinal) || elapsedMs >= SLOW_CALLBACK_MS || param.hasThrowable()) {
                Log.i(TAG, "[exit] $label hit=$ordinal cost=${elapsedMs}ms result=${typeName(param.result)} throwable=${typeName(param.throwable)}")
            }
        } catch (t: Throwable) {
            val elapsedMs = elapsedMs(start)
            Log.e(TAG, "[throw] $label hit=$ordinal cost=${elapsedMs}ms error=${t.javaClass.name}: ${t.message}")
            throw t
        }
    }

    fun <T> traceBusiness(
        label: String,
        detail: String = "",
        block: () -> T,
    ): T {
        val start = SystemClock.elapsedRealtimeNanos()
        val ordinal = increment("biz.$label")
        if (shouldLogHit(ordinal)) {
            Log.i(TAG, "[biz-enter] $label hit=$ordinal${detailSuffix(detail)}")
        }
        try {
            val result = block()
            val elapsedMs = elapsedMs(start)
            if (shouldLogHit(ordinal) || elapsedMs >= SLOW_BUSINESS_MS) {
                Log.i(TAG, "[biz-exit] $label hit=$ordinal cost=${elapsedMs}ms result=${typeName(result)}")
            }
            return result
        } catch (t: Throwable) {
            val elapsedMs = elapsedMs(start)
            Log.e(TAG, "[biz-throw] $label hit=$ordinal cost=${elapsedMs}ms error=${t.javaClass.name}: ${t.message}")
            throw t
        }
    }

    fun bundleSummary(bundle: Bundle?): String {
        if (bundle == null) return "bundle=null"
        return "keys=${bundle.keySet().sorted().joinToString(limit = 16)}, size=${bundle.keySet().size}"
    }

    private fun increment(label: String): Int {
        if (hitCounts.size >= MAX_LABELS) hitCounts.clear()
        return hitCounts.getOrPut(label) { AtomicInteger() }.incrementAndGet()
    }

    private fun logLimited(key: String, message: String) {
        val ordinal = increment(key)
        if (shouldLogHit(ordinal)) {
            Log.i(TAG, "$message hit=$ordinal")
        }
    }

    private fun shouldLogHit(hit: Int): Boolean {
        return hit <= FIRST_HITS || hit % PERIODIC_HIT == 0
    }

    private fun methodLabel(method: Member?): String {
        return if (method == null) {
            "<unknown>"
        } else {
            "${method.declaringClass.name}#${method.name}"
        }
    }

    private fun argTypes(args: Array<Any?>?): String {
        if (args == null) return "[]"
        return args.joinToString(prefix = "[", postfix = "]", limit = 12) { typeName(it) }
    }

    private fun typeName(value: Any?): String {
        return value?.javaClass?.name ?: "null"
    }

    private fun elapsedMs(startNs: Long): Long {
        return (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L
    }

    private fun detailSuffix(detail: String): String {
        return if (detail.isBlank()) "" else " $detail"
    }
}
