package tv.kyshu.xposed.utils

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import tv.kyshu.xposed.BuildConfig
import kotlin.concurrent.withLock

/**
 * Structured in-process telemetry buffer for hook lifecycle and stability events.
 *
 * The recorder is intentionally passive: it records events and optionally mirrors
 * durable checkpoints to the module cache directory, but it does not install or
 * alter hooks.
 */
object FlightRecorder {
    private const val MAX_EVENTS = 256
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    private val persistTypes = setOf("HOOK_INSTALL", "EXEC_DONE", "CRASH_MARKER", "GATE_SKIP", "EXEC_FAIL", "HOOK_FAIL")

    data class TelemetryEvent(
        val timestampMs: Long,
        val bootHash: String,
        val stepId: String,
        val eventType: String,
        val detail: String,
        val extra: Map<String, Any?> = emptyMap(),
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("t", timestampMs)
            json.put("b", bootHash)
            json.put("s", stepId)
            json.put("e", eventType)
            json.put("d", detail)
            val extraJson = JSONObject()
            extra.forEach { (key, value) ->
                extraJson.put(key, value ?: JSONObject.NULL)
            }
            json.put("x", extraJson)
            return json.toString()
        }
    }

    private val lock = ReentrantLock()
    private val events = ArrayDeque<TelemetryEvent>(MAX_EVENTS)
    private val bootHash by lazy { resolveBootHash() }
    private var currentFile: File? = null
    private var previousBootLines: List<String> = emptyList()

    fun setContext(context: Context?) {
        val cacheDir = context?.cacheDir ?: return
        lock.withLock {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            currentFile = File(cacheDir, ".portal_telemetry_$bootHash.jsonl")
            previousBootLines = loadPreviousBootTelemetryLocked(cacheDir)
            cleanupLocked(cacheDir)
        }
    }

    fun gatePass(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) =
        record(stepId, "GATE_PASS", detail, extra)

    fun gateSkip(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) =
        record(stepId, "GATE_SKIP", detail, extra)

    fun hookInstall(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) =
        record(stepId, "HOOK_INSTALL", detail, extra)

    fun hookFail(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) =
        record(stepId, "HOOK_FAIL", detail, extra)

    fun execStart(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) =
        record(stepId, "EXEC_START", detail, extra)

    fun execDone(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) =
        record(stepId, "EXEC_DONE", detail, extra)

    fun execFail(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) =
        record(stepId, "EXEC_FAIL", detail, extra)

    fun crashMarker(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) =
        record(stepId, "CRASH_MARKER", detail, extra)

    fun state(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) =
        record(stepId, "STATE", detail, extra)

    // N5 FIX (2026-06-30): Enhanced bootstrap snapshot capturing
    // device identity, module version, and process context.
    // Runs once per process load. Survives in ring buffer for later dump
    // even if the module crashes before producing more events.
    fun bootstrapState(source: String, extra: Map<String, Any?> = emptyMap()) {
        state(
            stepId = "BOOT",
            detail = source,
            extra = mapOf(
                "device_model" to (Build.MODEL ?: "unknown").take(32),
                "fingerprint_hash" to hashShort(Build.FINGERPRINT.orEmpty()),
                "sdk_int" to Build.VERSION.SDK_INT,
                "build_type" to (if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"),
                "safety_gates_enabled" to 0,
            ) + extra,
        )
    }

    fun record(
        stepId: String,
        eventType: String,
        detail: String,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val event = TelemetryEvent(
            timestampMs = System.currentTimeMillis(),
            bootHash = bootHash,
            stepId = stepId,
            eventType = eventType,
            detail = detail,
            extra = extra,
        )
        lock.withLock {
            events.addLast(event)
            while (events.size > MAX_EVENTS) {
                events.removeFirst()
            }
            if (eventType in persistTypes) {
                currentFile?.appendText(event.toJson() + "\n")
            }
        }
    }

    fun dump(lastN: Int = MAX_EVENTS, includePreviousBoot: Boolean = true): String {
        val limit = lastN.takeIf { it > 0 }?.coerceAtMost(MAX_EVENTS) ?: MAX_EVENTS
        return lock.withLock {
            val current = events.toList().takeLast(limit).map { it.toJson() }
            val previous = if (includePreviousBoot) previousBootLines else emptyList()
            (previous + current).joinToString("\n")
        }
    }

    fun clearForTest() {
        lock.withLock {
            events.clear()
            previousBootLines = emptyList()
            currentFile = null
        }
    }

    private fun loadPreviousBootTelemetryLocked(cacheDir: File): List<String> {
        val previous = cacheDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith(".portal_telemetry_") &&
                file.name.endsWith(".jsonl") &&
                !file.name.contains(bootHash)
        }?.maxByOrNull { it.lastModified() } ?: return emptyList()

        val marker = TelemetryEvent(
            timestampMs = System.currentTimeMillis(),
            bootHash = bootHash,
            stepId = "LIFECYCLE",
            eventType = "CRASH_MARKER",
            detail = "PREVIOUS_BOOT_TELEMETRY_LOADED",
            extra = mapOf("source_file" to previous.name),
        ).toJson()
        return listOf(marker) + previous.readLines().takeLast(MAX_EVENTS)
    }

    private fun cleanupLocked(cacheDir: File) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(".portal_telemetry_") && file.lastModified() < cutoff
        }?.forEach { it.delete() }
    }

    private fun resolveBootHash(): String {
        val bootId = runCatching {
            File("/proc/sys/kernel/random/boot_id").takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "elapsed-${SystemClock.elapsedRealtime()}"
        return hashShort(bootId)
    }

    private fun hashShort(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
