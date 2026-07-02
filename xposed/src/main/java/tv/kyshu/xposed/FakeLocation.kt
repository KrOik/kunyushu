@file:Suppress("LocalVariableName", "PrivateApi", "UNCHECKED_CAST")
package tv.kyshu.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import tv.kyshu.dobby.Dobby
import tv.kyshu.xposed.hooks.blindhook.BlindHookLocation
import tv.kyshu.xposed.hooks.LocationManagerHook
import tv.kyshu.xposed.hooks.LocationServiceHook
import tv.kyshu.xposed.hooks.fused.AndroidFusedLocationProviderHook
import tv.kyshu.xposed.hooks.fused.ThirdPartyLocationHook
import tv.kyshu.xposed.hooks.oplus.OplusLocationHook
import tv.kyshu.xposed.hooks.telephony.miui.MiuiTelephonyManagerHook
import tv.kyshu.xposed.hooks.sensor.SystemSensorManagerHook
import tv.kyshu.xposed.hooks.telephony.TelephonyHook
import tv.kyshu.xposed.hooks.wlan.WlanHook
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.ChannelIdentity
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.stability.DomainGuard
import tv.kyshu.xposed.stability.DomainGuard.Domain
import tv.kyshu.xposed.stability.SafeFaultHarness
import tv.kyshu.xposed.stability.BootStateStore
import tv.kyshu.xposed.stability.SafeFaultResult
import tv.kyshu.xposed.utils.CrashForensics
import tv.kyshu.xposed.RemoteCommandHandler
import tv.kyshu.xposed.security.RemoteCommandBundleCodec
import tv.kyshu.xposed.utils.FlightRecorder
import tv.kyshu.xposed.utils.LogRouter
import tv.kyshu.xposed.utils.PortalIdentity
import tv.kyshu.xposed.utils.safeCacheDir
import tv.kyshu.xposed.utils.safeFilesDir
import java.io.File

class FakeLocation: IXposedHookLoadPackage, IXposedHookZygoteInit {
    private lateinit var cServiceManager: Class<*> // android.os.ServiceManager
    private val mServiceManagerCache by lazy {
        kotlin.runCatching { cServiceManager.getDeclaredField("sCache") }.onFailure { Logger.debug("[FakeLocation] sCache reflect failed", it) }.onSuccess {
            it.isAccessible = true
        }.getOrNull()
    }

    companion object {
        /**
         * 系统启动后延迟时间（毫秒），在此期间hook不会生效，
         * 确保用户有充足的操作时间。
         */
        const val BOOT_DELAY_MS = 80_000L

        private const val DELAY_MARKER_PREFIX = "portal.delay_active_"

        private val TARGET_PACKAGES = setOf(
            "android",
            "com.android.phone",
            "com.android.location.fused",
            "com.xiaomi.location.fused",
            "com.oplus.location",
            "com.vivo.location.fused",
            "com.huawei.location.fused",
            "com.samsung.location.fused",
        ) + PortalIdentity.CONTROL_PACKAGES

        /**
         * Injected package set — survives System property clearing,
         * provides defense-in-depth re-entry prevention.
         */
        private val injectedPkgs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /**
         * Concurrency lock for startFakeLocHook — prevents double-install
         * when handleLoadPackage fires twice in rapid succession.
         */
        private val installLock = java.util.concurrent.locks.ReentrantLock()

        fun isPackageInjected(pkgName: String): Boolean = injectedPkgs.contains(pkgName)

        fun markPackageInjected(pkgName: String) { injectedPkgs.add(pkgName) }

        @JvmStatic
        fun cancelBootDelay(packageName: String) {
            System.setProperty(DELAY_MARKER_PREFIX + packageName, "false")
            Logger.warn("[FakeLocation] Boot delay cancelled for $packageName")
        }

        /**
         * ContentProvider backup channel poller.
         * Periodically reads pending commands from the ContentProvider
         * when the primary sendExtraCommand path is unavailable.
         */
        object DelayedCommandPoller {
            private var polling = false
            private val periodMs = 5000L
            @Volatile
            private var pollerThread: Thread? = null


            fun start() {
                if (polling) {
                    if (pollerThread?.isAlive == true) return
                    polling = false
                }
                polling = true
                val thread = Thread({
                    try {
                        while (polling) {
                            kotlin.runCatching {
                                val cursor = FakeLoc.getContext()?.contentResolver?.query(
                                    android.net.Uri.parse("content://tv.kyshu.xposed.config/cmd"),
                                    null, null, null, null
                                )
                                cursor?.use {
                                    while (it.moveToNext()) {
                                        val json = it.getString(it.getColumnIndexOrThrow("command"))
                                        val obj = org.json.JSONObject(json)
                                        val sessionKey = obj.optString("session_key").takeIf { value ->
                                            value.isNotBlank()
                                        } ?: run {
                                            Logger.warn("[DelayedCommandPoller] Missing session key")
                                            return@runCatching
                                        }
                                        val bundle = RemoteCommandBundleCodec.decode(obj.getString("bundle"))
                                        val commandId = bundle.getString("command_id") ?: run {
                                            Logger.warn("[DelayedCommandPoller] Missing command_id")
                                            return@runCatching
                                        }

                                        if (!RemoteCommandHandler.handleSessionInstruction(sessionKey, bundle)) {
                                            Logger.warn("[DelayedCommandPoller] Auth rejected for command: $commandId")
                                            return@runCatching
                                        }
                                    }
                                }
                            }.onFailure { e ->
                                if (polling) {
                                    Logger.warn("[DelayedCommandPoller] poll failed", e)
                                }
                            }
                            try { Thread.sleep(periodMs) } catch (_: InterruptedException) { break }
                        }
                    } finally {
                        polling = false
                        pollerThread = null
                    }
                }, "Portal-CmdPoller")
                pollerThread = thread
                thread.start()
                Logger.info("[DelayedCommandPoller] Started polling every ${periodMs}ms")
            }

            fun stop() {
                polling = false
                pollerThread?.interrupt()
                pollerThread = null
                Logger.info("[DelayedCommandPoller] Stopped")
            }
        }
    }

    /**
     * 验证ClassLoader是否仍然有效，防止延迟线程醒来后访问已回收的ClassLoader。
     * 通过尝试加载android.app.ActivityThread基础类来判断。
     */
    private fun isClassLoaderValid(classLoader: ClassLoader): Boolean {
        return kotlin.runCatching {
            classLoader.loadClass("android.app.ActivityThread")
        }.isSuccess
    }

    private fun runBootInitStep(step: String, block: () -> Unit) {
        runCatching(block).onFailure { e ->
            Logger.warn("[FakeLocation] $step init skipped", e)
        }
    }

    private fun resolveNativeSafetyCacheDir(context: android.content.Context): File? {
        val candidates = listOfNotNull(
            context.safeCacheDir(),
            context.safeFilesDir()?.resolve("cache"),
            File("/data/system/portal-cache"),
        )
        return candidates.firstOrNull { dir ->
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                dir.isDirectory && dir.canWrite()
            }.getOrDefault(false)
        }
    }

    private fun ensureNativeLoaded(stage: String): Boolean {
        if (Dobby.isAvailable()) return true
        val loaded = Dobby.loadFromClassLoader()
        if (!loaded) {
            Logger.warn("[FakeLocation] Native library unavailable at $stage: ${Dobby.unavailableReason}")
        }
        return loaded
    }

    private fun isTerminalDespairLocked(pkgName: String): Boolean {
        if (!Dobby.isAvailable()) return false
        return kotlin.runCatching { Dobby.isTerminalDespairLocked() }
            .onFailure { Logger.warn("[FakeLocation] Terminal despair check failed for $pkgName", it) }
            .getOrDefault(false)
    }

    private fun runKitIntegrityCheck(): Boolean {
        if (!Dobby.isAvailable()) return true
        val integrityOk = try {
            Dobby.kitIntegrityCheck(
                PortalIdentity.APPLICATION_ID,
                PortalIdentity.LEGACY_PACKAGE_ID,
                PortalIdentity.VERSION_NAME,
                PortalIdentity.VERSION_CODE
            )
        } catch (t: Throwable) {
            Logger.warn("[FakeLocation] Kit integrity check unavailable; continuing without native gate", t)
            null
        }
        when (integrityOk) {
            true -> Logger.info("[FakeLocation] Kit integrity check PASSED")
            false -> Logger.error("[FakeLocation] Kit integrity check FAILED; terminal lock written, hooks skipped")
            null -> Unit
        }
        return integrityOk != false
    }

    /**
     * Called very early during startup of Zygote.
     * @param startupParam Details about the module itself and the started process.
     * @throws Throwable everything is caught, but will prevent further initialization of the module.
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        if(startupParam == null) return
        // Dobby/native initialization is intentionally deferred until a concrete
        // target process is loaded. Loading JNI from zygote can poison every fork.
    }

    /**
     * This method is called when an app is loaded. It's called very early, even before
     * [Application.onCreate] is called.
     * Modules can set up their app-specific hooks here.
     *
     * @param lpparam Information about the app.
     * @throws Throwable Everything the callback throws is caught and logged.
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        val pkgName = lpparam?.packageName ?: return
        if (pkgName !in TARGET_PACKAGES) {
            return
        }

        // Hardened two-phase re-entry guard:
        // Phase 1 — derived property key (not string-table searchable)
        val guardKey = ("portal.injected_" + pkgName).hashCode().toString()
        // Phase 2 — companion-level set (survives System property clearing)
        if (System.getProperty(guardKey) == "true" || isPackageInjected(pkgName)) {
            return
        }
        // Check native despair lock before proceeding
        if (isTerminalDespairLocked(pkgName)) {
            Logger.error("[FakeLocation] Terminal despair lock active, all hooks skipped for $pkgName")
            return
        }
        System.setProperty(guardKey, "true")
        markPackageInjected(pkgName)



        when (lpparam.packageName) {
            "com.android.phone" -> {
                Logger.info("Found $pkgName, hooks will be installed after ${BOOT_DELAY_MS / 1000}s delay")

                System.setProperty(DELAY_MARKER_PREFIX + pkgName, "true")

                Thread({
                    try {
                        Thread.sleep(BOOT_DELAY_MS)
                    } catch (e: InterruptedException) {
                        Logger.warn("[FakeLocation] Boot delay interrupted for $pkgName, hooks skipped")
                        Thread.currentThread().interrupt()
                        return@Thread
                    }

                    if (System.getProperty(DELAY_MARKER_PREFIX + pkgName) != "true") {
                        Logger.warn("[FakeLocation] Boot delay cancelled for $pkgName, hooks skipped")
                        return@Thread
                    }

                    if (!isClassLoaderValid(lpparam.classLoader)) {
                        Logger.error("[FakeLocation] ClassLoader invalid after delay for $pkgName, hooks skipped")
                        FlightRecorder.hookFail("BOOT", "CLASSLOADER_INVALID",
                            mapOf("package" to pkgName))
                        return@Thread
                    }

                    Logger.info("[FakeLocation] Boot delay finished, installing phone hooks...")

                    // 使用 SafeFaultHarness 隔离电话模块hook安装
                    val phoneResult = SafeFaultHarness.run("install:TelephonyHook",
                        timeoutMs = 10_000L) {
                        TelephonyHook(lpparam.classLoader)
                    }
                    when (phoneResult) {
                        is SafeFaultResult.Completed -> {}
                        is SafeFaultResult.Blocked -> {
                            Logger.error("[FakeLocation] TelephonyHook BLOCKED: ${phoneResult.message}")
                            DomainGuard.reportCrash(Domain.TELEPHONY,
                                RuntimeException("SafeFaultHarness blocked: ${phoneResult.message}"))
                        }
                        is SafeFaultResult.Recovered -> {
                            Logger.warn("[FakeLocation] TelephonyHook RECOVERED: ${phoneResult.message}")
                            DomainGuard.reportCrash(Domain.TELEPHONY,
                                RuntimeException("SafeFaultHarness recovered: ${phoneResult.message}"))
                        }
                    }

                    val miuiResult = SafeFaultHarness.run("install:MiuiTelephonyHook",
                        timeoutMs = 10_000L) {
                        MiuiTelephonyManagerHook(lpparam.classLoader)
                    }
                    when (miuiResult) {
                        is SafeFaultResult.Completed -> {}
                        is SafeFaultResult.Blocked -> {
                            Logger.error("[FakeLocation] MiuiTelephonyManagerHook BLOCKED: ${miuiResult.message}")
                            DomainGuard.reportCrash(Domain.TELEPHONY,
                                RuntimeException("SafeFaultHarness blocked: ${miuiResult.message}"))
                        }
                        is SafeFaultResult.Recovered -> {
                            Logger.warn("[FakeLocation] MiuiTelephonyManagerHook RECOVERED: ${miuiResult.message}")
                            DomainGuard.reportCrash(Domain.TELEPHONY,
                                RuntimeException("SafeFaultHarness recovered: ${miuiResult.message}"))
                        }
                    }
                }, "PortalBootDelay-$pkgName").start()
            }
            "android" -> {
                val systemClassLoader = (kotlin.runCatching {
                    lpparam.classLoader.loadClass("android.app.ActivityThread")
                        ?: Class.forName("android.app.ActivityThread")
                }.onFailure {
                    Logger.error("Failed to find ActivityThread", it)
                }.getOrNull() ?: return)
                    .getMethod("currentActivityThread")
                    .invoke(null)
                    .javaClass
                    .getClassLoader()

                if (systemClassLoader == null) {
                    Logger.error("Failed to get system class loader")
                    return
                }
                // Initialize observability + safety infrastructure
                val systemContext = runCatching {
                    val atCls = lpparam.classLoader.loadClass("android.app.ActivityThread")
                    val at = atCls.getMethod("currentActivityThread").invoke(null)
                    at.javaClass.getMethod("getSystemContext").invoke(at) as? android.content.Context
                }.getOrNull()
                if (systemContext != null) {
                    runBootInitStep("FakeLoc context") {
                        FakeLoc.setContext(systemContext)
                    }
                    runBootInitStep("LogRouter") {
                        LogRouter.setContext(systemContext)
                    }
                    runBootInitStep("FlightRecorder") {
                        FlightRecorder.setContext(systemContext)
                    }
                    runBootInitStep("CrashForensics") {
                        CrashForensics.setContext(systemContext)
                    }
                    runBootInitStep("ChannelIdentity") {
                        ChannelIdentity.init(systemContext)
                    }
                    // 持久化状态存储 — 必须在 checkInstallCrashLoop 之前初始化
                    runBootInitStep("BootStateStore") {
                        BootStateStore.setContext(systemContext)
                    }
                    FlightRecorder.bootstrapState("system_server")
                    val nativeReady = ensureNativeLoaded("system_server")
                    if (nativeReady) {
                        var nativeSafetyInitialized = false
                        runCatching {
                            val cacheDir = resolveNativeSafetyCacheDir(systemContext)
                            if (cacheDir == null) {
                                Logger.warn("[FakeLocation] Safety framework cache dir unavailable, native safety disabled")
                            } else {
                                Dobby.initSafetyFramework(cacheDir.absolutePath)
                                nativeSafetyInitialized = true
                            }
                        }.onFailure { e ->
                            FlightRecorder.hookFail("BOOT", "SAFETY_FRAMEWORK_INIT",
                                mapOf("error" to e.javaClass.simpleName))
                        }
                        if (isTerminalDespairLocked(pkgName)) {
                            Logger.error("[FakeLocation] Terminal despair lock active, all hooks skipped for $pkgName")
                            return
                        }
                        if (!runKitIntegrityCheck()) {
                            return
                        }
                        if (!nativeSafetyInitialized) {
                            Logger.warn("[FakeLocation] Native safety cache unavailable; integrity failure lock cannot be persisted")
                        }
                    }

                    // Safety mode check: BootStateStore 已加载持久化状态，方可判断安全模式
                    if (DomainGuard.checkInstallCrashLoop()) {
                        DomainGuard.enterSafeMode("install crash loop (3 consecutive crashes)")
                        Logger.error("[FakeLocation] Entering SAFE MODE - all hooks skipped")
                        return
                    }

                    // Uncaught exception handler — captures any missed crash
                    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
                    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                        CrashForensics.crash("UNCAUGHT", thread.name, throwable)
                        FlightRecorder.execFail("UNCAUGHT", thread.name,
                            mapOf("error" to throwable.javaClass.simpleName))
                        defaultHandler?.uncaughtException(thread, throwable)
                    }
                    if (!nativeReady) {
                        Logger.warn("[FakeLocation] Native safety disabled: ${Dobby.unavailableReason}")
                    } else {
                        runCatching {
                            Dobby.writeArtVersionLock()
                        }.onFailure { e ->
                            FlightRecorder.hookFail("BOOT", "ART_VERSION_LOCK_WRITE",
                                mapOf("error" to e.javaClass.simpleName))
                        }
                        runCatching {
                            Dobby.installProcMapsHooks()
                        }.onFailure { e ->
                            FlightRecorder.hookFail("BOOT", "PROC_MAPS_HOOK_INSTALL",
                                mapOf("error" to e.javaClass.simpleName))
                        }
                    }
                }
                // Start DelayedCommandPoller for ContentProvider fallback command channel
                DelayedCommandPoller.start()

                Logger.info("Debug Log Status: ${FakeLoc.enableDebugLog}")
                FakeLoc.isSystemServerProcess = true

                Logger.info("[FakeLocation] Hooks will be installed after ${BOOT_DELAY_MS / 1000}s boot delay")

                // 设置生命周期标记，用于外部取消和线程醒来后的状态检查
                System.setProperty(DELAY_MARKER_PREFIX + pkgName, "true")

                val bootThreadLabel = "PortalBootDelay-$pkgName"
                FlightRecorder.execStart("BOOT", "DELAY_THREAD_START",
                    mapOf("delay_ms" to BOOT_DELAY_MS, "thread" to bootThreadLabel))

                // Watchdog：如果启动线程超过预期时间仍未完成，记录告警
                val watchdogStartedAt = System.currentTimeMillis()
                val watchdogTimeout = BOOT_DELAY_MS + 60_000L // 80s 延迟 + 60s 安装窗口
                Thread({
                    try {
                        Thread.sleep(watchdogTimeout)
                    } catch (_: InterruptedException) { return@Thread }
                    // 如果线程还在运行（installLock仍被持有或未释放），记录异常
                    if (installLock.isLocked) {
                        Logger.warn("[FakeLocation] Boot delay watchdog: thread STILL RUNNING after ${watchdogTimeout / 1000}s")
                        FlightRecorder.execFail("BOOT", "WATCHDOG_TIMEOUT",
                            mapOf("elapsed_ms" to (System.currentTimeMillis() - watchdogStartedAt),
                                  "thread" to bootThreadLabel))
                    }
                }, "${bootThreadLabel}-Watchdog").apply { isDaemon = true }.start()

                Thread({
                    try {
                        Thread.sleep(BOOT_DELAY_MS)
                    } catch (e: InterruptedException) {
                        Logger.warn("[FakeLocation] Boot delay interrupted for $pkgName, hooks skipped")
                        FlightRecorder.execFail("BOOT", "DELAY_INTERRUPTED",
                            mapOf("package" to pkgName))
                        Thread.currentThread().interrupt()
                        return@Thread
                    }

                    // 检查延迟任务是否已被外部取消
                    if (System.getProperty(DELAY_MARKER_PREFIX + pkgName) != "true") {
                        Logger.warn("[FakeLocation] Boot delay cancelled for $pkgName, hooks skipped")
                        FlightRecorder.gateSkip("BOOT", "DELAY_CANCELLED",
                            mapOf("package" to pkgName))
                        return@Thread
                    }

                    // 验证ClassLoader有效性，防止访问已回收的ClassLoader
                    if (!isClassLoaderValid(lpparam.classLoader)) {
                        Logger.error("[FakeLocation] ClassLoader invalid after delay for $pkgName, hooks skipped")
                        FlightRecorder.hookFail("BOOT", "CLASSLOADER_INVALID",
                            mapOf("package" to pkgName))
                        DomainGuard.reportInstallCrash(
                            IllegalStateException("ClassLoader invalid after delay"))
                        return@Thread
                    }

                    Logger.info("[FakeLocation] Boot delay finished, installing hooks...")
                    FlightRecorder.execDone("BOOT", "DELAY_COMPLETE",
                        mapOf("package" to pkgName))

                    // Concurrency safety: tryLock prevents double-install on rapid handleLoadPackage fires
                    if (!installLock.tryLock(15, java.util.concurrent.TimeUnit.SECONDS)) {
                        Logger.error("[FakeLocation] installLock acquire timed out for $pkgName — concurrent install detected, skipping")
                        FlightRecorder.hookFail("BOOT", "CONCURRENT_INSTALL",
                            mapOf("package" to pkgName))
                        DomainGuard.reportInstallCrash(
                            IllegalStateException("installLock timeout"))
                        return@Thread
                    }

                    // 使用 SafeFaultHarness 隔离关键安装路径，防止死循环或死锁阻塞进程
                    var hasFatalFailure = false

                    FlightRecorder.execStart("BOOT", "HOOK_INSTALL_BATCH",
                        mapOf("package" to pkgName))

                    // startFakeLocHook（最长允许15秒）
                    val locResult = SafeFaultHarness.run("install:LocationServiceHook",
                        timeoutMs = 15_000L) {
                        startFakeLocHook(systemClassLoader)
                    }
                    when (locResult) {
                        is SafeFaultResult.Completed -> {
                            FlightRecorder.hookInstall("BOOT", "LOCATION_HOOK_OK",
                                mapOf("package" to pkgName))
                        }
                        is SafeFaultResult.Blocked -> {
                            Logger.error("[FakeLocation] startFakeLocHook BLOCKED: ${locResult.message}")
                            FlightRecorder.hookFail("BOOT", "LOCATION_HOOK_BLOCKED",
                                mapOf("fault" to locResult.faultClass.name, "message" to locResult.message))
                            DomainGuard.reportCrash(Domain.LOCATION_SERVICE,
                                RuntimeException("SafeFaultHarness blocked: ${locResult.message}"))
                            hasFatalFailure = true
                        }
                        is SafeFaultResult.Recovered -> {
                            Logger.warn("[FakeLocation] startFakeLocHook RECOVERED: ${locResult.message}")
                            FlightRecorder.hookFail("BOOT", "LOCATION_HOOK_RECOVERED",
                                mapOf("fault" to locResult.faultClass.name, "message" to locResult.message))
                            DomainGuard.reportCrash(Domain.LOCATION_SERVICE,
                                RuntimeException("SafeFaultHarness recovered: ${locResult.message}"))
                        }
                    }

                    // TelephonyHook.hookSubOnTransact（最长允许10秒）
                    val telResult = SafeFaultHarness.run("install:TelephonyHook",
                        timeoutMs = 10_000L) {
                        TelephonyHook.hookSubOnTransact(lpparam.classLoader)
                    }
                    when (telResult) {
                        is SafeFaultResult.Completed -> {}
                        is SafeFaultResult.Blocked -> {
                            Logger.error("[FakeLocation] TelephonyHook BLOCKED: ${telResult.message}")
                            DomainGuard.reportCrash(Domain.TELEPHONY,
                                RuntimeException("SafeFaultHarness blocked: ${telResult.message}"))
                        }
                        is SafeFaultResult.Recovered -> {
                            Logger.warn("[FakeLocation] TelephonyHook RECOVERED: ${telResult.message}")
                            DomainGuard.reportCrash(Domain.TELEPHONY,
                                RuntimeException("SafeFaultHarness recovered: ${telResult.message}"))
                        }
                    }

                    // WlanHook（最长允许10秒）
                    val wlanResult = SafeFaultHarness.run("install:WlanHook",
                        timeoutMs = 10_000L) {
                        WlanHook(systemClassLoader)
                    }
                    when (wlanResult) {
                        is SafeFaultResult.Completed -> {}
                        is SafeFaultResult.Blocked -> {
                            Logger.error("[FakeLocation] WlanHook BLOCKED: ${wlanResult.message}")
                            DomainGuard.reportCrash(Domain.WLAN,
                                RuntimeException("SafeFaultHarness blocked: ${wlanResult.message}"))
                        }
                        is SafeFaultResult.Recovered -> {
                            Logger.warn("[FakeLocation] WlanHook RECOVERED: ${wlanResult.message}")
                            DomainGuard.reportCrash(Domain.WLAN,
                                RuntimeException("SafeFaultHarness recovered: ${wlanResult.message}"))
                        }
                    }

                    // SystemSensorManagerHook（最长允许10秒）
                    val sensorResult = SafeFaultHarness.run("install:SensorHook",
                        timeoutMs = 10_000L) {
                        SystemSensorManagerHook(lpparam.classLoader)
                    }
                    when (sensorResult) {
                        is SafeFaultResult.Completed -> {}
                        is SafeFaultResult.Blocked -> {
                            Logger.error("[FakeLocation] SystemSensorManagerHook BLOCKED: ${sensorResult.message}")
                            DomainGuard.reportCrash(Domain.SENSOR,
                                RuntimeException("SafeFaultHarness blocked: ${sensorResult.message}"))
                        }
                        is SafeFaultResult.Recovered -> {
                            Logger.warn("[FakeLocation] SystemSensorManagerHook RECOVERED: ${sensorResult.message}")
                            DomainGuard.reportCrash(Domain.SENSOR,
                                RuntimeException("SafeFaultHarness recovered: ${sensorResult.message}"))
                        }
                    }

                    FlightRecorder.execDone("BOOT", "HOOK_INSTALL_BATCH",
                        mapOf("package" to pkgName, "fatal" to hasFatalFailure.toString()))

                    // 如果出现不可恢复的阻塞故障，计数安装崩溃
                    if (hasFatalFailure) {
                        DomainGuard.reportInstallCrash(
                            RuntimeException("Fatal hook install block"))
                        installLock.unlock()
                        return@Thread
                    }

                    // Load complete: reset install guard + report status
                    DomainGuard.resetInstallCrashCount()
                    val installedHooks = Domain.entries.filter { !DomainGuard.isDomainDisabled(it) }
                        .joinToString(",") { it.label }
                    Logger.info("[FakeLocation] load complete: hooks=$installedHooks")
                    FlightRecorder.gatePass("BOOT", "BOOT_GATE",
                        mapOf("hooks" to installedHooks))
                    LogRouter.info("BBN:BOOT", "load complete: hooks=$installedHooks")
                    installLock.unlock()
                }, bootThreadLabel).start()
            }
            "com.android.location.fused" -> {
                Logger.info("[FakeLocation] Fused process: $pkgName, installing AndroidFusedLocationProviderHook...")
                FlightRecorder.hookInstall("FUSED", "ANDROID_FUSED", mapOf("process" to pkgName))
                runCatching { AndroidFusedLocationProviderHook(lpparam.classLoader) }
                    .onSuccess {
                        Logger.info("[FakeLocation] AndroidFusedLocationProviderHook ($pkgName) installed successfully")
                        FlightRecorder.hookInstall("FUSED", "ANDROID_FUSED_OK", mapOf("process" to pkgName))
                    }
                    .onFailure { e ->
                        Logger.error("[FakeLocation] AndroidFusedLocationProviderHook ($pkgName) failed", e)
                        FlightRecorder.hookFail("FUSED", "ANDROID_FUSED_FAIL",
                            mapOf("process" to pkgName, "error" to e.javaClass.simpleName))
                        DomainGuard.reportCrash(Domain.FUSED_LOCATION, e)
                    }
            }
            "com.xiaomi.location.fused" -> {
                Logger.info("[FakeLocation] Fused process: $pkgName, installing ThirdPartyLocationHook...")
                FlightRecorder.hookInstall("FUSED", "XIAOMI_FUSED", mapOf("process" to pkgName))
                runCatching { ThirdPartyLocationHook(lpparam.classLoader) }
                    .onSuccess {
                        Logger.info("[FakeLocation] ThirdPartyLocationHook ($pkgName) installed successfully")
                        FlightRecorder.hookInstall("FUSED", "XIAOMI_FUSED_OK", mapOf("process" to pkgName))
                    }
                    .onFailure { e ->
                        Logger.error("[FakeLocation] ThirdPartyLocationHook ($pkgName) failed", e)
                        FlightRecorder.hookFail("FUSED", "XIAOMI_FUSED_FAIL",
                            mapOf("process" to pkgName, "error" to e.javaClass.simpleName))
                        DomainGuard.reportCrash(Domain.FUSED_LOCATION, e)
                    }
            }
            "com.oplus.location" -> {
                Logger.info("[FakeLocation] Fused process: $pkgName, installing OplusLocationHook...")
                FlightRecorder.hookInstall("FUSED", "OPLUS_LOCATION", mapOf("process" to pkgName))
                runCatching { OplusLocationHook(lpparam.classLoader) }
                    .onSuccess {
                        Logger.info("[FakeLocation] OplusLocationHook ($pkgName) installed successfully")
                        FlightRecorder.hookInstall("FUSED", "OPLUS_LOCATION_OK", mapOf("process" to pkgName))
                    }
                    .onFailure { e ->
                        Logger.error("[FakeLocation] OplusLocationHook ($pkgName) failed", e)
                        FlightRecorder.hookFail("FUSED", "OPLUS_LOCATION_FAIL",
                            mapOf("process" to pkgName, "error" to e.javaClass.simpleName))
                        DomainGuard.reportCrash(Domain.FUSED_LOCATION, e)
                    }
            }
            "com.vivo.location.fused" -> installBlindHook(lpparam, Domain.FUSED_LOCATION, "VIVO_FUSED")
            "com.huawei.location.fused" -> installBlindHook(lpparam, Domain.FUSED_LOCATION, "HUAWEI_FUSED")
            "com.samsung.location.fused" -> installBlindHook(lpparam, Domain.FUSED_LOCATION, "SAMSUNG_FUSED")
            in PortalIdentity.CONTROL_PACKAGES -> {
                Logger.info("[FakeLocation] Self-module package: $pkgName, installing anti-forensics hooks...")
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        "tv.kyshu.service.MockServiceHelper",
                        lpparam.classLoader,
                        "isModuleLoaded",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.result = false
                            }
                        }
                    )
                }.onSuccess {
                    Logger.info("[FakeLocation] Self-module anti-forensics hook installed")
                }.onFailure { e ->
                    FlightRecorder.hookFail("SELF", "isModuleLoaded_HOOK_FAIL",
                        mapOf("error" to e.javaClass.simpleName))
                }
            }
        }
    }

    /**
     * Generic blind-hook installer for vendor fused location processes.
     * Searches the process ClassLoader for common location SDK classes
     * and applies BlindHookLocation injection. All failures are logged
     * via FlightRecorder and reported to DomainGuard.
     */
    private fun installBlindHook(lpparam: XC_LoadPackage.LoadPackageParam, domain: Domain, label: String) {
        Logger.info("[FakeLocation] Fused process: ${lpparam.packageName}, installing blind hook ($label)...")
        FlightRecorder.hookInstall("FUSED", label, mapOf("process" to lpparam.packageName))
        runCatching {
            // Search for common location service classes in the fused process
            val candidateClasses = listOf(
                "com.android.location.provider.LocationProviderBase",
                "com.amap.android.location.NetworkLocationManager",
                "com.baidu.location.LocationClient",
                "com.tencent.map.geolocation.TencentLocationManager",
                "com.vivo.services.location.NetworkLocationManager",
            )
            var hookedCount = 0
            for (className in candidateClasses) {
                runCatching {
                    val clazz = lpparam.classLoader.loadClass(className)
                    BlindHookLocation(clazz, lpparam.classLoader)
                    hookedCount++
                }
            }
            if (hookedCount > 0) {
                Logger.info("[FakeLocation] BlindHook ($label) installed on $hookedCount class(es)")
                FlightRecorder.hookInstall("FUSED", "${label}_OK",
                    mapOf("process" to lpparam.packageName, "hooked" to hookedCount.toString()))
            } else {
                Logger.warn("[FakeLocation] BlindHook ($label) found no target classes in ${lpparam.packageName}")
                FlightRecorder.hookFail("FUSED", "${label}_NO_TARGET",
                    mapOf("process" to lpparam.packageName))
            }
        }.onFailure { e ->
            Logger.error("[FakeLocation] BlindHook ($label) general failure", e)
            FlightRecorder.hookFail("FUSED", "${label}_FAIL",
                mapOf("process" to lpparam.packageName, "error" to e.javaClass.simpleName))
            DomainGuard.reportCrash(domain, e)
        }
    }

    private fun startFakeLocHook(classLoader: ClassLoader) {
        cServiceManager = XposedHelpers.findClass("android.os.ServiceManager", classLoader)

        XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)?.let {
            TelephonyHook.hookTelephonyRegistry(it)
        } // for MUMU emulator

        val cLocationManager =
            XposedHelpers.findClass("android.location.LocationManager", classLoader)

        LocationServiceHook(classLoader)
        LocationManagerHook(cLocationManager)  // intrusive hooks
    }
}
