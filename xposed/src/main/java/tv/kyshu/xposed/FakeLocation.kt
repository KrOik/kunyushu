@file:Suppress("LocalVariableName", "PrivateApi", "UNCHECKED_CAST")
package tv.kyshu.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
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
import tv.kyshu.xposed.utils.Logger
 import tv.kyshu.xposed.stability.DomainGuard
import tv.kyshu.xposed.stability.DomainGuard.Domain
import tv.kyshu.xposed.utils.CrashForensics
import tv.kyshu.xposed.utils.FlightRecorder
import tv.kyshu.xposed.utils.LogRouter

class FakeLocation: IXposedHookLoadPackage, IXposedHookZygoteInit {
    private lateinit var cServiceManager: Class<*> // android.os.ServiceManager
    private val mServiceManagerCache by lazy {
        kotlin.runCatching { cServiceManager.getDeclaredField("sCache") }.onSuccess {
            it.isAccessible = true
        }.getOrNull()
    }

    companion object {
        /**
         * 系统启动后延迟时间（毫秒），在此期间hook不会生效，
         * 确保用户有充足的操作时间。
         */
        const val BOOT_DELAY_MS = 80_000L
    }

    /**
     * Called very early during startup of Zygote.
     * @param startupParam Details about the module itself and the started process.
     * @throws Throwable everything is caught, but will prevent further initialization of the module.
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        if(startupParam == null) return

//        // 宇宙安全声明：以下代码仅供学习交流使用，切勿用于非法用途?
//        System.setProperty("portal.enable", "true")
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
        if (lpparam?.packageName != "android" && lpparam?.packageName != "com.android.phone") {
            return
        }

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

        if(System.getProperty("portal.injected_${lpparam.packageName}") == "true") {
            return
        } else {
            System.setProperty("portal.injected_${lpparam.packageName}", "true")
        }

        when (lpparam.packageName) {
            "com.android.phone" -> {
                Logger.info("Found com.android.phone, hooks will be installed after ${BOOT_DELAY_MS / 1000}s delay")
                Thread {
                    Thread.sleep(BOOT_DELAY_MS)
                    Logger.info("[FakeLocation] Boot delay finished, installing phone hooks...")
                    runCatching { TelephonyHook(lpparam.classLoader) }
                        .onFailure { e ->
                            Logger.error("[FakeLocation] TelephonyHook install failed", e)
                            DomainGuard.reportCrash(Domain.TELEPHONY, e)
                        }
                    runCatching { MiuiTelephonyManagerHook(lpparam.classLoader) }
                        .onFailure { e ->
                            Logger.error("[FakeLocation] MiuiTelephonyManagerHook install failed", e)
                            DomainGuard.reportCrash(Domain.TELEPHONY, e)
                        }
                }.start()
            }
            "android" -> {
                // Safety mode check: if 3+ consecutive install crashes, enter safe mode
                if (DomainGuard.checkInstallCrashLoop()) {
                    DomainGuard.enterSafeMode("install crash loop (3 consecutive crashes)")
                    Logger.error("[FakeLocation] Entering SAFE MODE - all hooks skipped")
                    return
                }

                // Initialize observability + safety infrastructure
                val systemContext = runCatching {
                    val atCls = lpparam.classLoader.loadClass("android.app.ActivityThread")
                    val at = atCls.getMethod("currentActivityThread").invoke(null)
                    at.javaClass.getMethod("getSystemContext").invoke(at) as? android.content.Context
                }.getOrNull()
                if (systemContext != null) {
                    FakeLoc.setContext(systemContext)
                    LogRouter.setContext(systemContext)
                    FlightRecorder.setContext(systemContext)
                    CrashForensics.setContext(systemContext)
                    FlightRecorder.bootstrapState("system_server")
                    runCatching {
                        tv.kyshu.dobby.Dobby.initSafetyFramework(systemContext.cacheDir.absolutePath)
                    }.onFailure { e ->
                        FlightRecorder.hookFail("BOOT", "SAFETY_FRAMEWORK_INIT",
                            mapOf("error" to e.javaClass.simpleName))
                    }
                    runCatching {
                        tv.kyshu.dobby.Dobby.writeArtVersionLock()
                    }.onFailure { e ->
                        FlightRecorder.hookFail("BOOT", "ART_VERSION_LOCK_WRITE",
                            mapOf("error" to e.javaClass.simpleName))
                    }
                }

                Logger.info("Debug Log Status: ${FakeLoc.enableDebugLog}")
                FakeLoc.isSystemServerProcess = true

                Logger.info("[FakeLocation] Hooks will be installed after ${BOOT_DELAY_MS / 1000}s boot delay")

                // 延迟安装hook，确保用户有80秒操作时间
                Thread {
                    Thread.sleep(BOOT_DELAY_MS)
                    Logger.info("[FakeLocation] Boot delay finished, installing hooks...")

                    // BBN: each hook installation independently wrapped
                    runCatching { startFakeLocHook(systemClassLoader) }
                        .onFailure { e ->
                            Logger.error("[FakeLocation] startFakeLocHook failed", e)
                            DomainGuard.reportCrash(Domain.LOCATION_SERVICE, e)
                        }
                    runCatching { TelephonyHook.hookSubOnTransact(lpparam.classLoader) }
                        .onFailure { e ->
                            Logger.error("[FakeLocation] TelephonyHook.hookSubOnTransact failed", e)
                            DomainGuard.reportCrash(Domain.TELEPHONY, e)
                        }
                    runCatching { WlanHook(systemClassLoader) }
                        .onFailure { e ->
                            Logger.error("[FakeLocation] WlanHook install failed", e)
                            DomainGuard.reportCrash(Domain.WLAN, e)
                        }
                    runCatching { AndroidFusedLocationProviderHook(lpparam.classLoader) }
                        .onFailure { e ->
                            Logger.error("[FakeLocation] AndroidFusedLocationProviderHook failed", e)
                            DomainGuard.reportCrash(Domain.FUSED_LOCATION, e)
                        }
                    runCatching { SystemSensorManagerHook(lpparam.classLoader) }
                        .onFailure { e ->
                            Logger.error("[FakeLocation] SystemSensorManagerHook failed", e)
                            DomainGuard.reportCrash(Domain.SENSOR, e)
                        }
                    runCatching { ThirdPartyLocationHook(lpparam.classLoader) }
                        .onFailure { e ->
                            Logger.error("[FakeLocation] ThirdPartyLocationHook failed", e)
                            DomainGuard.reportCrash(Domain.FUSED_LOCATION, e)
                        }

                    // Load complete: reset install guard + report status
                    DomainGuard.resetInstallCrashCount()
                    val installedHooks = Domain.entries.filter { !DomainGuard.isDomainDisabled(it) }
                        .joinToString(",") { it.label }
                    Logger.info("[FakeLocation] load complete: hooks=$installedHooks")
                    FlightRecorder.gatePass("BOOT", "BOOT_GATE",
                        mapOf("hooks" to installedHooks))
                    LogRouter.info("BBN:BOOT", "load complete: hooks=$installedHooks")
                }.start()
            }
            "com.android.location.fused" -> {
                runCatching { AndroidFusedLocationProviderHook(lpparam.classLoader) }
                    .onFailure { e ->
                        Logger.error("[FakeLocation] AndroidFusedLocationProviderHook (fused process) failed", e)
                        DomainGuard.reportCrash(Domain.FUSED_LOCATION, e)
                    }
            }
            "com.xiaomi.location.fused" -> {
                runCatching { ThirdPartyLocationHook(lpparam.classLoader) }
                    .onFailure { e ->
                        Logger.error("[FakeLocation] ThirdPartyLocationHook (xiaomi fused) failed", e)
                        DomainGuard.reportCrash(Domain.FUSED_LOCATION, e)
                    }
            }
            "com.oplus.location" -> {
                runCatching { OplusLocationHook(lpparam.classLoader) }
                    .onFailure { e ->
                        Logger.error("[FakeLocation] OplusLocationHook failed", e)
                        DomainGuard.reportCrash(Domain.FUSED_LOCATION, e)
                    }
            }
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