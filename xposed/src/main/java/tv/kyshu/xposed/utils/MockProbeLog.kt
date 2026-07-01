package tv.kyshu.xposed.utils

/**
 * Lightweight probe logger for tracing user actions in the KunYuShu APK
 * and the corresponding Xposed module responses.
 *
 * Emits tagged events to LSPosed logcat with a [Probe] prefix for easy filtering.
 */
object MockProbeLog {
    private const val TAG = "Probe"

    /** User toggled a switch in the app UI. */
    fun uiAction(action: String, detail: String = "") {
        log("UI_ACTION", "$action $detail".trim())
    }

    /** IPC command sent from app to Xposed module. */
    fun ipcSent(command: String, detail: String = "") {
        log("IPC_SENT", "$command $detail".trim())
    }

    /** IPC command received by Xposed module. */
    fun ipcReceived(command: String, result: String = "") {
        log("IPC_RCVD", "$command -> $result".trim())
    }

    /** Config synced to persistence. */
    fun configSync(detail: String) {
        log("CONFIG_SYNC", detail)
    }

    /** State transition in mock engine. */
    fun stateChange(what: String, from: String, to: String) {
        log("STATE", "$what: $from -> $to")
    }

    /** Step counter specific diagnostics. */
    fun stepDiag(detail: String) {
        log("STEP", detail)
    }

    private fun log(subTag: String, message: String) {
        Logger.debug("[$TAG] [$subTag] $message")
    }
}
