package tv.kyshu.dobby

import android.util.Log

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
     * Load libportal.so from an absolute path.
     * Must be called before any JNI method. Safe to call multiple times.
     */
    @Synchronized
    fun load(path: String): Boolean {
        if (isLoaded) return flushPendingStatusLocked()
        if (loadAttempted) return false
        loadAttempted = true
        return try {
            System.load(path)
            isLoaded = true
            lastLoadError = null
            Log.i(TAG, "libportal.so loaded from $path")
            flushPendingStatusLocked()
            true
        } catch (e: UnsatisfiedLinkError) {
            lastLoadError = e
            loadAttempted = false
            Log.e(TAG, "Failed to load libportal.so from $path", e)
            false
        }
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
            Log.i(TAG, "libportal.so loaded via classloader")
            flushPendingStatusLocked()
            true
        } catch (e: UnsatisfiedLinkError) {
            lastLoadError = e
            Log.w(TAG, "Classloader load failed (expected for LSPosed modules), use load(path) instead")
            loadAttempted = false  // allow retry with load(path)
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

    external fun writeArtVersionLock()

    external fun writeTerminalDespairLock()

    external fun isTerminalDespairLocked(): Boolean

    external fun kitIntegrityCheck(pkgName: String, version: String, versionCode: Int): Boolean

    external fun installProcMapsHooks(): Boolean
}
