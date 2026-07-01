package tv.kyshu.xposed.utils

/**
 * [DEPRECATED] 使用 [Log] 直接记录。
 */
@Deprecated("Use Log directly", ReplaceWith("Log"))
object MockProbeLog {
    fun uiAction(action: String, detail: String = "") = Log.i("Probe", "[UI_ACTION] $action $detail".trim())
    fun ipcSent(command: String, detail: String = "") = Log.i("Probe", "[IPC_SENT] $command $detail".trim())
    fun ipcReceived(command: String, result: String = "") = Log.i("Probe", "[IPC_RCVD] $command -> $result".trim())
    fun configSync(detail: String) = Log.i("Probe", "[CONFIG_SYNC] $detail")
    fun stateChange(what: String, from: String, to: String) = Log.i("Probe", "[STATE] $what: $from -> $to")
    fun stepDiag(detail: String) = Log.i("Probe", "[STEP] $detail")
}
