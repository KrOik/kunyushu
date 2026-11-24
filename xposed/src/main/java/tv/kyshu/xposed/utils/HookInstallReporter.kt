package tv.kyshu.xposed.utils

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

object HookInstallReporter {
    private const val MAX_EVENTS = 128
    private const val REASON_METHOD_MISS = "method_not_found_or_no_overload"

    private val installedCount = AtomicInteger()
    private val skippedCount = AtomicInteger()
    private val failureCount = AtomicInteger()
    private val fatalFuseCount = AtomicInteger()
    private val skipReasons = ConcurrentHashMap<String, AtomicInteger>()
    private val events = ConcurrentLinkedDeque<HookInstallEvent>()

    fun recordInstall(
        label: String,
        installed: Int = 1,
        mode: String = "",
        classLoader: ClassLoader? = null,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val count = installed.coerceAtLeast(0)
        if (count == 0) {
            recordSkip(
                label = label,
                reason = REASON_METHOD_MISS,
                mode = mode,
                classLoader = classLoader,
                extra = extra,
            )
            return
        }
        installedCount.addAndGet(count)
        addEvent(
            HookInstallEvent(
                status = "installed",
                label = label,
                count = count,
                mode = mode,
                classLoaderSource = classLoaderSource(classLoader),
                reason = "",
                fatalFuse = false,
                extra = extra,
            )
        )
    }

    fun recordSkip(
        label: String,
        reason: String,
        mode: String = "",
        classLoader: ClassLoader? = null,
        classLoaderSource: String = classLoaderSource(classLoader),
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val normalizedReason = reason.ifBlank { "unspecified" }
        skippedCount.incrementAndGet()
        skipReasons.getOrPut(normalizedReason) { AtomicInteger() }.incrementAndGet()
        addEvent(
            HookInstallEvent(
                status = "skipped",
                label = label,
                count = 0,
                mode = mode,
                classLoaderSource = classLoaderSource,
                reason = normalizedReason,
                fatalFuse = false,
                extra = extra,
            )
        )
    }

    fun recordFailure(
        label: String,
        reason: String,
        fatalFuse: Boolean,
        mode: String = "",
        classLoader: ClassLoader? = null,
        classLoaderSource: String = classLoaderSource(classLoader),
        extra: Map<String, Any?> = emptyMap(),
    ) {
        failureCount.incrementAndGet()
        if (fatalFuse) fatalFuseCount.incrementAndGet()
        addEvent(
            HookInstallEvent(
                status = "failed",
                label = label,
                count = 0,
                mode = mode,
                classLoaderSource = classLoaderSource,
                reason = reason.ifBlank { "unspecified" },
                fatalFuse = fatalFuse,
                extra = extra,
            )
        )
    }

    fun dumpJson(): String = snapshot().toJson().toString()

    fun snapshot(): HookInstallSnapshot = HookInstallSnapshot(
        installedCount = installedCount.get(),
        skippedCount = skippedCount.get(),
        failureCount = failureCount.get(),
        fatalFuseCount = fatalFuseCount.get(),
        sdkInt = Build.VERSION.SDK_INT,
        fingerprint = Build.FINGERPRINT.orEmpty(),
        events = events.toList(),
        skipReasons = skipReasons.mapValues { it.value.get() }.toSortedMap(),
    )

    fun clearForTest() {
        installedCount.set(0)
        skippedCount.set(0)
        failureCount.set(0)
        fatalFuseCount.set(0)
        skipReasons.clear()
        events.clear()
    }

    private fun addEvent(event: HookInstallEvent) {
        events.addLast(event)
        while (events.size > MAX_EVENTS) events.pollFirst()
    }

    private fun classLoaderSource(classLoader: ClassLoader?): String = when (classLoader) {
        null -> "bootstrap"
        ClassLoader.getSystemClassLoader() -> "system"
        else -> classLoader.javaClass.name
    }

    data class HookInstallSnapshot(
        val installedCount: Int,
        val skippedCount: Int,
        val failureCount: Int,
        val fatalFuseCount: Int,
        val sdkInt: Int,
        val fingerprint: String,
        val events: List<HookInstallEvent>,
        val skipReasons: Map<String, Int>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("installed_count", installedCount)
            put("skipped_count", skippedCount)
            put("failure_count", failureCount)
            put("fatal_fuse_count", fatalFuseCount)
            put("sdk_int", sdkInt)
            put("fingerprint", fingerprint)
            put("skip_reasons", JSONObject().apply {
                skipReasons.forEach { (reason, count) -> put(reason, count) }
            })
            put("events", JSONArray().apply {
                events.forEach { put(it.toJson()) }
            })
        }
    }

    data class HookInstallEvent(
        val status: String,
        val label: String,
        val count: Int,
        val mode: String,
        val classLoaderSource: String,
        val reason: String,
        val fatalFuse: Boolean,
        val extra: Map<String, Any?>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("status", status)
            put("label", label)
            put("count", count)
            put("mode", mode)
            put("classloader_source", classLoaderSource)
            put("reason", reason)
            put("fatal_fuse", fatalFuse)
            put("extra", JSONObject().apply {
                extra.forEach { (key, value) -> put(key, value?.toString() ?: JSONObject.NULL) }
            })
        }
    }
}
