package tv.kyshu.xposed.utils

import android.content.Context

/**
 * [DEPRECATED] 使用 [Log.telemetry] 替代。
 */
@Deprecated("Use Log.telemetry", ReplaceWith("Log"))
object FlightRecorder {
    fun setContext(context: Context?) = Log.init(context)
    fun gatePass(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) = Log.telemetry(stepId, "GATE_PASS", detail, extra)
    fun gateSkip(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) = Log.telemetry(stepId, "GATE_SKIP", detail, extra)
    fun hookInstall(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) = Log.telemetry(stepId, "HOOK_INSTALL", detail, extra)
    fun hookFail(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) = Log.telemetry(stepId, "HOOK_FAIL", detail, extra)
    fun execStart(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) = Log.telemetry(stepId, "EXEC_START", detail, extra)
    fun execDone(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) = Log.telemetry(stepId, "EXEC_DONE", detail, extra)
    fun execFail(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) = Log.telemetry(stepId, "EXEC_FAIL", detail, extra)
    fun crashMarker(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) = Log.telemetry(stepId, "CRASH_MARKER", detail, extra)
    fun state(stepId: String, detail: String, extra: Map<String, Any?> = emptyMap()) = Log.telemetry(stepId, "STATE", detail, extra)
    fun bootstrapState(source: String, extra: Map<String, Any?> = emptyMap()) = Log.telemetry("BOOT", "STATE", source, extra)
    fun record(stepId: String, eventType: String, detail: String, extra: Map<String, Any?> = emptyMap()) = Log.telemetry(stepId, eventType, detail, extra)
    fun dump(lastN: Int = 256, includePreviousBoot: Boolean = true): String = Log.dumpTelemetry(lastN)
    fun clearForTest() = Log.clearForTest()
}
