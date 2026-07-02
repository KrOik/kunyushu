package tv.kyshu.xposed.utils

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [DEPRECATED] 使用 [Log] 直接记录。
 */
@Deprecated("Use Log directly", ReplaceWith("Log"))
object MockProbeLog {
    private const val MOVE_IPC_LOG_INTERVAL_MS = 5_000L
    private val lastMoveIpcLogMs = AtomicLong(Long.MIN_VALUE)
    private val suppressedMoveIpcLogs = AtomicInteger(0)

    internal fun formatIpcReceivedForTest(command: String, result: String = "", nowMs: Long): String? {
        return formatIpcReceived(command, result, nowMs)
    }

    internal fun resetRateLimitForTest() {
        lastMoveIpcLogMs.set(Long.MIN_VALUE)
        suppressedMoveIpcLogs.set(0)
    }

    private fun formatIpcReceived(
        command: String,
        result: String,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): String? {
        if (command != "move") {
            return "[IPC_RCVD] $command -> $result".trim()
        }

        val last = lastMoveIpcLogMs.get()
        val insideWindow = last != Long.MIN_VALUE && nowMs >= last && nowMs - last < MOVE_IPC_LOG_INTERVAL_MS
        if (insideWindow) {
            suppressedMoveIpcLogs.incrementAndGet()
            return null
        }

        if (!lastMoveIpcLogMs.compareAndSet(last, nowMs)) {
            suppressedMoveIpcLogs.incrementAndGet()
            return null
        }

        val suppressed = suppressedMoveIpcLogs.getAndSet(0)
        val suffix = if (suppressed > 0) " suppressed=$suppressed/${MOVE_IPC_LOG_INTERVAL_MS / 1000}s" else ""
        return "[IPC_RCVD] move -> $result$suffix".trim()
    }

    fun uiAction(action: String, detail: String = "") = Log.i("Probe", "[UI_ACTION] $action $detail".trim())
    fun ipcSent(command: String, detail: String = "") = Log.i("Probe", "[IPC_SENT] $command $detail".trim())
    fun ipcReceived(command: String, result: String = "") {
        formatIpcReceived(command, result)?.let { Log.i("Probe", it) }
    }
    fun configSync(detail: String) = Log.i("Probe", "[CONFIG_SYNC] $detail")
    fun stateChange(what: String, from: String, to: String) = Log.i("Probe", "[STATE] $what: $from -> $to")
    fun stepDiag(detail: String) = Log.i("Probe", "[STEP] $detail")
}
