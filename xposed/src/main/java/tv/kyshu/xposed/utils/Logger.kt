package tv.kyshu.xposed.utils

import de.robv.android.xposed.XposedBridge

object Logger {
    private const val TAG = "Portal"

    @Volatile
    var forceLogging: Boolean = false

    private fun isEnableLog(): Boolean {
        return forceLogging || FakeLoc.enableLog
    }

    private fun isEnableDebug(): Boolean {
        // TODO: add power-state gating (isAppInBackground, isScreenOff, isDeviceIdleMode) from VoxLoction
        return forceLogging || (FakeLoc.enableLog && FakeLoc.enableDebugLog)
    }

    fun info(msg: String) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG] $msg")
        }
    }

    fun info(msg: String, throwable: Throwable) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG] $msg: ${throwable.stackTraceToString()}")
        }
    }

    fun debug(msg: String) {
        if (isEnableDebug()) {
            XposedBridge.log("[$TAG][DEBUG] $msg")
        }
    }

    fun debug(msg: String, throwable: Throwable) {
        if (isEnableDebug()) {
            XposedBridge.log("[$TAG][DEBUG] $msg: ${throwable.stackTraceToString()}")
        }
    }

    fun error(msg: String) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG][ERROR] $msg")
        }
        // Always write errors to LogRouter for forensic persistence
        LogRouter.error("Portal", msg)
    }

    fun error(msg: String, throwable: Throwable) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG][ERROR] $msg: ${throwable.stackTraceToString()}")
        }
        LogRouter.error("Portal", "$msg: ${throwable.stackTraceToString()}")
    }

    fun warn(msg: String) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG][WARN] $msg")
        }
        LogRouter.warn("Portal", msg)
    }

    fun warn(msg: String, throwable: Throwable) {
        if (isEnableLog()) {
            XposedBridge.log("[$TAG][WARN] $msg: ${throwable.stackTraceToString()}")
        }
        LogRouter.warn("Portal", "$msg: ${throwable.stackTraceToString()}")
    }
}
