package tv.kyshu.xposed.stability

import tv.kyshu.xposed.utils.FlightRecorder
import tv.kyshu.xposed.utils.CrashForensics
import tv.kyshu.xposed.utils.LogRouter
import tv.kyshu.xposed.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-domain crash counter with progressive auto-disable.
 *
 * Priority 1: System stability. If a domain crashes more than THRESHOLD times
 * within the WINDOW_MS sliding window, it is auto-disabled until next module reload.
 *
 * Priority 2: Log retention. Every crash is recorded to LogRouter file + Logger error.
 */
object DomainGuard {

    data class DomainState(
        val crashes: MutableList<Long> = mutableListOf(),
        val disabled: AtomicInteger = AtomicInteger(0), // 0=active, 1=disabled
    )

    enum class Domain(val label: String) {
        WLAN("WLAN"),
        TELEPHONY("TELEPHONY"),
        GNSS("GNSS"),
        SENSOR("SENSOR"),
        FUSED_LOCATION("FUSED_LOCATION"),
        BLE("BLE"),
        PROXY("PROXY"),
        LOCATION_SERVICE("LOCATION_SERVICE"),
        NATIVE_LIB("NATIVE_LIB"),
    }

    // For install-time protection (catastrophic failures)
    enum class InstallGuard {
        INSTALL_THROTTLE,
        INSTALL_CRASH_LOOP,
    }

    private const val CRASH_THRESHOLD = 5
    private const val WINDOW_MS = 300_000L // 5 minutes sliding window

    private val domains = ConcurrentHashMap<Domain, DomainState>()

    // -- Install guard: tracks consecutive boot-time install failures --
    private val installCrashCount = AtomicInteger(0)
    private var lastInstallCrashTime = 0L
    private const val INSTALL_THRESHOLD = 3
    private const val INSTALL_WINDOW_MS = 120_000L // 2 min

    fun isDomainDisabled(domain: Domain): Boolean {
        val state = domains[domain]
        if (state == null || state.disabled.get() == 0) return false

        val reason = "[DomainGuard] ${domain.label} is DISABLED (${state.crashes.size} crashes in window)"
        Logger.warn(reason)
        LogRouter.warn("DomainGuard", reason)
        return true
    }

    /**
     * Report a crash for a domain. Returns true if the domain should be disabled after this.
     */
    fun reportCrash(domain: Domain, error: Throwable? = null): Boolean {
        val now = System.currentTimeMillis()
        val state = domains.getOrPut(domain) { DomainState() }

        synchronized(state.crashes) {
            // Purge expired crash records outside the window
            state.crashes.removeAll { now - it > WINDOW_MS }
            state.crashes.add(now)

            val count = state.crashes.size
            val msg = "[DomainGuard] ${domain.label} crash #$count: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown"}"
            Logger.forceLogging = true

            // Priority 2: log retention
            Logger.error(msg)
            LogRouter.error("DomainGuard", "$msg (window=${WINDOW_MS}ms)")
            CrashForensics.crash(domain.label, "DOMAIN_CRASH", error)

            // Priority 1: auto-disable if threshold exceeded
            if (count >= CRASH_THRESHOLD) {
                state.disabled.set(1)
                val disableMsg = "[DomainGuard] ${domain.label} DISABLED after $count crashes in ${WINDOW_MS / 1000}s"
                Logger.error(disableMsg)
                LogRouter.error("DomainGuard", disableMsg)
                CrashForensics.skip(domain.label, "DOMAIN_DISABLED")
                FlightRecorder.crashMarker(
                    stepId = "LIFECYCLE",
                    detail = "DOMAIN_DISABLED",
                    extra = mapOf("domain" to domain.label, "crashes" to count),
                )
                return true
            }
        }
        return false
    }

    fun resetDomain(domain: Domain) {
        domains.remove(domain)
        Logger.info("[DomainGuard] ${domain.label} guard reset")
    }

    fun resetAll() {
        domains.clear()
        Logger.info("[DomainGuard] all domains reset")
    }

    // -- Install-time crash loop detection --

    /**
     * Call on module load. If too many install crashes detected, return true to
     * skip all system_server hooks and enter safe mode.
     */
    fun checkInstallCrashLoop(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(installCrashCount) {
            // N5 FIX: DO NOT update lastInstallCrashTime here —
            // only reportInstallCrash() should advance the timestamp.
            // If we update it on every check, frequent non-crash loads
            // perpetually extend the window, preventing reset.
            if (now - lastInstallCrashTime > INSTALL_WINDOW_MS) {
                installCrashCount.set(0)
            }
            return installCrashCount.get() >= INSTALL_THRESHOLD
        }
    }

    fun reportInstallCrash(error: Throwable? = null) {
        val now = System.currentTimeMillis()
        synchronized(installCrashCount) {
            if (now - lastInstallCrashTime > INSTALL_WINDOW_MS) {
                installCrashCount.set(0)
            }
            installCrashCount.incrementAndGet()
            lastInstallCrashTime = now
            val count = installCrashCount.get()
            val msg = "[DomainGuard] Install crash #$count: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown"}"
            Logger.forceLogging = true
            Logger.error(msg)
            LogRouter.error("DomainGuard", msg)
            CrashForensics.crash("BOOT", "INSTALL_CRASH", error)
            FlightRecorder.execFail(
                stepId = "BOOT",
                detail = "INSTALL_CRASH",
                extra = mapOf("count" to count, "error" to (error?.javaClass?.simpleName ?: "unknown")),
            )
        }
    }

    fun resetInstallCrashCount() {
        installCrashCount.set(0)
        Logger.info("[DomainGuard] install crash counter reset")
    }

    // -- Safe mode flag --
    @Volatile
    var safeMode: Boolean = false
        private set

    fun enterSafeMode(reason: String) {
        safeMode = true
        val msg = "[DomainGuard] ENTERING SAFE MODE: $reason"
        Logger.forceLogging = true
        Logger.error(msg)
        LogRouter.error("DomainGuard", msg)
        CrashForensics.skip("BOOT", "BOOT_GATE")
        FlightRecorder.crashMarker(
            stepId = "LIFECYCLE",
            detail = "SAFE_MODE",
            extra = mapOf("reason" to reason),
        )
    }

    fun exitSafeMode() {
        safeMode = false
        installCrashCount.set(0)
        Logger.info("[DomainGuard] exited safe mode")
    }
}
