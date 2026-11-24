package tv.kyshu.dobby

import tv.kyshu.xposed.utils.Log as XLog

object Dobby {
    private const val TAG = "Dobby"

    @Volatile
    var isLoaded = false
        private set

    @Volatile
    private var lastLoadError: Throwable? = null

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var pendingStatus: Boolean? = null

    /**
     * Absolute-path native loading is intentionally disabled.
     *
     * The supported path is [loadFromClassLoader], which resolves libportal.so
     * from the module's nativeLibraryDir/base.apk entry. Keeping this method as
     * a deny-only compatibility shim prevents old callers from reintroducing
     * /data/local style loading.
     */
    @Synchronized
    @Deprecated("Absolute native loading is disabled; use loadFromClassLoader")
    fun load(path: String): Boolean {
        if (isLoaded) return flushPendingStatusLocked()
        lastLoadError = SecurityException("absolute native load disabled")
        XLog.w(TAG, "absolute native runtime load denied")
        return false
    }

    /**
     * Load libportal.so via System.loadLibrary (classloader search).
     * Only works if the .so is in the classloader's native library path.
     * Falls back silently if unavailable.
     */
    @Synchronized
    fun loadFromClassLoader(): Boolean {
        if (isLoaded) return flushPendingStatusLocked()
        if (loadAttempted) return false
        loadAttempted = true
        return try {
            System.loadLibrary("portal")
            isLoaded = true
            lastLoadError = null
            XLog.i(TAG, "native runtime loaded via classloader")
            flushPendingStatusLocked()
            true
        } catch (e: UnsatisfiedLinkError) {
            lastLoadError = e
            XLog.w(TAG, "classloader native runtime load failed: ${e.javaClass.simpleName}")
            loadAttempted = false  // allow retry after transient classloader failures
            false
        }
    }

    /** Check if native library is loaded and JNI methods are usable */
    fun isAvailable(): Boolean = isLoaded

    val unavailableReason: String
        get() = lastLoadError?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "not loaded"

    @Synchronized
    fun setStatusWhenLoaded(status: Boolean): Boolean {
        pendingStatus = status
        if (!isLoaded) return false
        return flushPendingStatusLocked()
    }

    @Synchronized
    fun flushPendingStatus(): Boolean {
        return flushPendingStatusLocked()
    }

    @Synchronized
    internal fun resetForTest() {
        isLoaded = false
        lastLoadError = null
        loadAttempted = false
        pendingStatus = null
    }

    internal val pendingStatusForTest: Boolean?
        get() = pendingStatus

    private fun flushPendingStatusLocked(): Boolean {
        val status = pendingStatus ?: return true
        return try {
            setStatus(status)
            pendingStatus = null
            true
        } catch (t: Throwable) {
            lastLoadError = t
            false
        }
    }

    // ---- JNI methods ----
    // Available only after libportal.so is loaded

    external fun setStatus(status: Boolean)

    external fun checkCrashMeltdown(): Int

    external fun recordCrashRecovery()

    external fun resetCrashCounter()

    external fun initSafetyFramework(cacheDir: String)

    external fun installNativeSensorHook(): Boolean

    external fun writeArtVersionLock()

    external fun writeTerminalDespairLock()

    external fun isTerminalDespairLocked(): Boolean

    external fun kitIntegrityCheck(pkgName: String, pkgName2: String, version: String, versionCode: Int): Boolean

    external fun installProcMapsHooks(): Boolean
}
