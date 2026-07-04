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
 *
 * ## 跨重启持久化
 *
 * 通过 [BootStateStore] 在重启间保留以下状态：
 * - `installCrashCount` + `lastInstallCrashTime` — 同boot内累计，跨boot重置
 * - `safeMode` — 跨boot继承，防止无限崩溃循环
 *
 * 详见 [BootStateStore] 的文档。
 */
object DomainGuard {

    data class DomainState(
        val crashes: MutableList<Long> = mutableListOf(),
        val disabled: AtomicInteger = AtomicInteger(0), // 0=active, 1=disabled
    )

    enum class Domain(val label: String) {
        WLAN("WLAN"),
        TELEPHONY("TELEPHONY"),
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
    @Volatile
    private var lastInstallCrashTime = 0L
    private const val INSTALL_THRESHOLD = 3
    private const val INSTALL_WINDOW_MS = 120_000L // 2 min

    /** 标记持久化状态是否已在当前启动中加载过，避免反复读盘。 */
    @Volatile
    private var persistedStateLoaded = false

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

    fun disableDomain(domain: Domain, reason: String) {
        val state = domains.getOrPut(domain) { DomainState() }
        state.disabled.set(1)
        val msg = "[DomainGuard] ${domain.label} DISABLED: $reason"
        Logger.forceLogging = true
        Logger.error(msg)
        LogRouter.error("DomainGuard", msg)
        CrashForensics.skip(domain.label, "DOMAIN_DISABLED")
        FlightRecorder.crashMarker(
            stepId = "LIFECYCLE",
            detail = "DOMAIN_DISABLED",
            extra = mapOf("domain" to domain.label, "reason" to reason),
        )
    }

    fun resetAll() {
        domains.clear()
        Logger.info("[DomainGuard] all domains reset")
    }

    // -- Install-time crash loop detection --

    /**
     * Call on module load. If too many install crashes detected, return true to
     * skip all system_server hooks and enter safe mode.
     *
     * 首次调用时从 [BootStateStore] 加载持久化的安装崩溃计数和安全模式状态。
     * 如果上次启动处于安全模式，直接返回 true。
     */
    fun checkInstallCrashLoop(): Boolean {
        loadPersistedStateOnce()

        // 如果上次启动因崩溃循环进入了安全模式，本次直接延续
        if (safeMode) {
            Logger.warn("[DomainGuard] safeMode is ACTIVE from previous boot — skipping all hooks")
            return true
        }

        val now = System.currentTimeMillis()
        synchronized(installCrashCount) {
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
            // 持久化：每次安装崩溃后写入磁盘
            persistInstallStateLocked()
        }
    }

    fun resetInstallCrashCount() {
        installCrashCount.set(0)
        lastInstallCrashTime = 0L
        // 清零时清除持久化状态文件（当前启动已安全加载）
        BootStateStore.clear()
        persistedStateLoaded = false
        Logger.info("[DomainGuard] install crash counter reset and persisted state cleared")
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
        // 持久化：安全模式标记写入磁盘，跨重启生效
        BootStateStore.save(
            BootStateStore.BootState(
                safeMode = true,
                installCrashCount = installCrashCount.get(),
                lastInstallCrashTime = lastInstallCrashTime,
            )
        )
    }

    fun exitSafeMode() {
        safeMode = false
        installCrashCount.set(0)
        lastInstallCrashTime = 0L
        BootStateStore.clear()
        persistedStateLoaded = false
        Logger.info("[DomainGuard] exited safe mode, persisted state cleared")
    }

    // ==================== 持久化辅助 ====================

    /**
     * 首次调用时从 [BootStateStore] 加载持久化状态。
     * 后续调用为 no-op（同一启动内只读一次盘）。
     */
    private fun loadPersistedStateOnce() {
        if (persistedStateLoaded) return
        persistedStateLoaded = true

        val persisted = BootStateStore.load()
        if (persisted.safeMode) {
            // 跨启动继承安全模式
            safeMode = true
            Logger.warn("[DomainGuard] Loaded safeMode=TRUE from persisted state (previous boot crash)")
        }
        if (persisted.installCrashCount > 0) {
            // 同boot内继承安装崩溃计数
            installCrashCount.set(persisted.installCrashCount)
            lastInstallCrashTime = persisted.lastInstallCrashTime
            Logger.info("[DomainGuard] Loaded persisted installCrashCount=${persisted.installCrashCount}")
        }
    }

    /**
     * 在 [installCrashCount] 锁内持久化安装崩溃状态。
     */
    private fun persistInstallStateLocked() {
        BootStateStore.save(
            BootStateStore.BootState(
                safeMode = safeMode,
                installCrashCount = installCrashCount.get(),
                lastInstallCrashTime = lastInstallCrashTime,
            )
        )
    }
}
