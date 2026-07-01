package tv.kyshu.dobby

object Dobby {
    external fun setStatus(status: Boolean)
    external fun checkCrashMeltdown(): Int
    external fun recordCrashRecovery()
    external fun resetCrashCounter()
    external fun initSafetyFramework(cacheDir: String)
    external fun writeArtVersionLock()
    external fun writeTerminalDespairLock()
    external fun isTerminalDespairLocked(): Boolean
    external fun installProcMapsHooks()
    external fun kitIntegrityCheck(pkgName: String, version: String, versionCode: Int): Boolean
}
