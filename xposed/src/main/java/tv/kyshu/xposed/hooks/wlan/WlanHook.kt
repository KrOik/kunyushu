@file:Suppress("UNCHECKED_CAST", "PrivateApi")
package tv.kyshu.xposed.hooks.wlan

import android.net.wifi.WifiInfo
import android.net.wifi.ScanResult
import android.os.Build
import android.util.ArrayMap
import dalvik.system.PathClassLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.BuildConfig
import tv.kyshu.xposed.factory.WifiInfoFactory
import tv.kyshu.xposed.factory.WifiScanResultFactory
import tv.kyshu.xposed.stability.DomainGuard
import tv.kyshu.xposed.stability.WirelessRuntime
import tv.kyshu.xposed.stability.replaceOrPassthrough
import tv.kyshu.xposed.utils.BinderUtils
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.LogRedactor
import tv.kyshu.xposed.utils.afterHook
import tv.kyshu.xposed.utils.hookAllMethods
import tv.kyshu.xposed.utils.hookMethodAfter
import tv.kyshu.xposed.utils.toClass
import tv.kyshu.xposed.utils.onceHookAllMethod
import tv.kyshu.xposed.utils.onceHookBefore
import java.util.concurrent.ConcurrentHashMap

object WlanHook {
    private const val MAX_MOCK_SCAN_RESULTS = 32
    private val diagnosticKeys = ConcurrentHashMap.newKeySet<String>()

    private fun logOnce(key: String, message: String) {
        if (diagnosticKeys.add(key)) {
            Logger.info(message)
        }
    }

    operator fun invoke(classLoader: ClassLoader) {
        if (!BuildConfig.ENABLE_WIFI_MOCK) {
            FakeLoc.hookWifi = false
            FakeLoc.enableMockWifi = false
            Logger.info("[WlanHook] install skipped: WiFi mock feature disabled")
            return
        }
        Logger.debug("[WlanHook] install start: sdk=${Build.VERSION.SDK_INT}, hookWifi=${FakeLoc.hookWifi}, enableMockWifi=${FakeLoc.enableMockWifi}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cSystemServerClassLoaderFactory = XposedHelpers.findClassIfExists("com.android.internal.os.SystemServerClassLoaderFactory", classLoader)
            if (cSystemServerClassLoaderFactory == null) {
                Logger.warn("[WlanHook] Failed to find SystemServerClassLoaderFactory")
                return
            }
            val sLoadedPaths = XposedHelpers.getStaticObjectField(
                cSystemServerClassLoaderFactory,
                "sLoadedPaths"
            ) as? ArrayMap<*, *> ?: run {
                Logger.warn("[WlanHook] SystemServerClassLoaderFactory.sLoadedPaths has unexpected shape")
                return
            }
            Logger.debug("[WlanHook] SystemServerClassLoaderFactory paths=${sLoadedPaths.size}")
            val wifiClassLoader = sLoadedPaths.entries.firstNotNullOfOrNull { entry ->
                val path = entry.key as? String
                val loader = entry.value as? PathClassLoader
                if (path?.contains("service-wifi.jar") == true) loader else null
            }
            if (wifiClassLoader == null) {
                Logger.warn("[WlanHook] Failed to find wifiClassLoader for service-wifi.jar")
                return
            }
            Logger.debug("[WlanHook] wifi classloader resolved")
            val wifiClazz = "com.android.server.wifi.WifiServiceImpl".toClass(wifiClassLoader)
            if (wifiClazz == null) {
                Logger.warn("[WlanHook] Failed to find WifiServiceImpl class with wifiClassLoader")
                return
            }
            hookWifiServiceImpl(wifiClazz)
            hookWifiScannerService(wifiClassLoader, "wifiClassLoader")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cSystemServiceManager = XposedHelpers.findClassIfExists("com.android.server.SystemServiceManager", classLoader)
            if (cSystemServiceManager == null) {
                Logger.warn("[WlanHook] Failed to find SystemServiceManager")
                return
            }
            val count = cSystemServiceManager.hookAllMethods("loadClassFromLoader", afterHook {
                if (args[0] == "com.android.server.wifi.WifiService") {
                    kotlin.runCatching {
                        val classloader = args.getOrNull(1) as? PathClassLoader ?: run {
                            Logger.warn("[WlanHook] WifiService loader skipped: arg1=${args.getOrNull(1)?.javaClass?.name ?: "null"}")
                            return@runCatching
                        }
                        val wifiClazz = classloader.loadClass("com.android.server.wifi.WifiServiceImpl")
                        Logger.debug("[WlanHook] WifiService loaded via SystemServiceManager")
                        hookWifiServiceImpl(wifiClazz)
                        hookWifiScannerService(classloader, "wifiServiceLoader")
                    }.onFailure {
                        Logger.error("[WlanHook] Failed to hook WifiService", it)
                    }
                }
            })
            Logger.info("[WlanHook] hooked SystemServiceManager.loadClassFromLoader overloads=${count.size}")
        }

        // Android 12+ hook WifiScannerService for scan results
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hookWifiScannerService(classLoader, "systemClassLoader")
        }
    }

	    private fun hookWifiServiceImpl(wifiClazz: Class<*>) {
	        if (!FakeLoc.hookWifi) {
            Logger.warn("[WlanHook] WifiServiceImpl hook skipped: hookWifi=false")
            return
        }
        Logger.debug("[WlanHook] hooking WifiServiceImpl: methods=${wifiClazz.declaredMethods.size}")

        // Kuroko per-UID hide path (conservative semantics, priority 1):
        // for UIDs with POLICY_HIDE_WIFI, redact connection info via makeCopy(redactions)
        // instead of constructing fake WifiInfo objects — layout-change safe.
        val hideConnHooks = wifiClazz.hookAllMethods("getConnectionInfo", afterHook {
            val uid = BinderUtils.getCallerUid()
            if (uid <= 0 || tv.kyshu.xposed.config.UidPolicy.epochValue() == 0) return@afterHook
            if (!tv.kyshu.xposed.config.UidPolicy.hasPolicy(uid, tv.kyshu.xposed.config.UidPolicy.POLICY_HIDE_WIFI)) return@afterHook
            val orig = result as? WifiInfo ?: return@afterHook
            result = runCatching {
                val redactions = orig.applicableRedactions
                orig.makeCopy(if (redactions != 0L) redactions else -1L)
            }.getOrElse { orig }
            logOnce("wifi.hideconn.$uid", "[WlanHook] getConnectionInfo redacted (Kuroko hide path) uid=$uid")
        })
        Logger.info("[WlanHook] hooked getConnectionInfo hide-path overloads=${hideConnHooks.size}")

        // Kuroko per-UID hide path for scan results: empty list/slice, no object construction.
        val hideScanHooks = wifiClazz.hookAllMethods("getScanResults", afterHook {
            val uid = BinderUtils.getCallerUid()
            if (uid <= 0 || tv.kyshu.xposed.config.UidPolicy.epochValue() == 0) return@afterHook
            if (!tv.kyshu.xposed.config.UidPolicy.hasPolicy(uid, tv.kyshu.xposed.config.UidPolicy.POLICY_HIDE_WIFI)) return@afterHook
            if (result == null) return@afterHook
            result = when (result) {
                is List<*> -> emptyList<Any>()
                else -> runCatching {
                    // jarjar-renamed ParceledListSlice (A12+ APEX) — mirror the runtime return class
                    val sliceClass = result.javaClass
                    val empty = sliceClass.getDeclaredMethod("emptyList").apply { isAccessible = true }
                    empty.invoke(null) ?: sliceClass.getConstructor(List::class.java).apply {
                        isAccessible = true
                    }.newInstance(emptyList<Any>())
                }.getOrElse { emptyList<Any>() }
            }
            logOnce("wifi.hidescan.$uid", "[WlanHook] getScanResults emptied (Kuroko hide path) uid=$uid")
        })
        Logger.info("[WlanHook] hooked getScanResults hide-path overloads=${hideScanHooks.size}")

        // 使用 afterHook 让原方法执行，保留真实连接状态（networkId/supplicantState/linkSpeed/frequency 等），
        // 仅在副本上覆盖身份字段（MAC/BSSID/SSID/IP/RSSI），避免裸 WifiInfo 导致系统判定未连接而断网。
        val connectionHooks = wifiClazz.hookAllMethods("getConnectionInfo", afterHook {
            val original = result
            val snapshot = WirelessRuntime.snapshotOrLegacy()
            if (DomainGuard.isDomainDisabled(DomainGuard.Domain.WLAN) || !snapshot.wifiConnectedEnabled) return@afterHook
            val packageName = args.firstOrNull { it is String } as? String ?: run {
                logOnce("wifi.connection.noPackage", "[WlanHook] getConnectionInfo skipped: no package string args=${args.map { it?.javaClass?.simpleName }}")
                return@afterHook
            }
            if (FakeLoc.enableDebugLog)
                Logger.debug("In getConnectionInfo with caller: ${LogRedactor.stableIdentity(packageName)}, state: ${FakeLoc.enableMockWifi}")

            if (BinderUtils.isSystemPackages(packageName)) {
                logOnce("wifi.connection.system.${LogRedactor.stableIdentity(packageName)}", "[WlanHook] getConnectionInfo skipped for system caller=${LogRedactor.stableIdentity(packageName)}")
                return@afterHook
            }

            val realInfo = result as? WifiInfo ?: run {
                logOnce("wifi.connection.badResult", "[WlanHook] getConnectionInfo skipped: result=${result?.javaClass?.name ?: "null"}")
                return@afterHook
            }
            val profile = snapshot.wifiConnected ?: return@afterHook
            result = replaceOrPassthrough(DomainGuard.Domain.WLAN, "getConnectionInfo", original) {
                WifiInfoFactory.createConnectedInfo(realInfo, profile)?.info
                    ?: throw IllegalStateException("WifiInfo construction failed")
            }
            logOnce("wifi.connection.replaced.${LogRedactor.stableIdentity(packageName)}",
                "[WlanHook] getConnectionInfo replaced: caller=${LogRedactor.stableIdentity(packageName)} generation=${snapshot.generation}")
        })
        Logger.info("[WlanHook] hooked getConnectionInfo overloads=${connectionHooks.size}")

	        val scanHooks = wifiClazz.hookAllMethods("getScanResults", afterHook {
	            val original = result
	            val snapshot = WirelessRuntime.snapshotOrLegacy()
	            if (DomainGuard.isDomainDisabled(DomainGuard.Domain.WLAN) || !snapshot.wifiScanEnabled) return@afterHook
	            val packageName = args.firstOrNull { it is String } as? String
            if (packageName.isNullOrEmpty()) {
                logOnce("wifi.scan.noPackage", "[WlanHook] getScanResults skipped: no package string args=${args.map { it?.javaClass?.simpleName }}")
                return@afterHook
            }

            if (FakeLoc.enableDebugLog)
                Logger.debug("In getScanResults with caller: ${LogRedactor.stableIdentity(packageName)}, state: ${FakeLoc.enableMockWifi}")

	            if(snapshot.wifiScanEnabled) {
	                if(result == null) {
	                    logOnce("wifi.scan.null.${LogRedactor.stableIdentity(packageName)}", "[WlanHook] getScanResults skipped: null result caller=${LogRedactor.stableIdentity(packageName)}")
	                    return@afterHook
	                }

	                val replacement = replaceOrPassthrough(DomainGuard.Domain.WLAN, "getScanResults", original) {
	                    buildScanReplacement(original, buildMockScanResults(snapshot))
	                }
	                result = replacement

	                if (replacement !== original) {
                        logOnce("wifi.scan.replaced.${LogRedactor.stableIdentity(packageName)}",
                            "[WlanHook] getScanResults replaced: caller=${LogRedactor.stableIdentity(packageName)} resultType=${result?.javaClass?.name}, generation=${snapshot.generation}")
                        return@afterHook
                    }

                    Logger.warn("[WlanHook] getScanResults unsupported return type: ${result?.javaClass?.name}")
	            } else {
                    logOnce("wifi.scan.disabled", "[WlanHook] getScanResults skipped: enableMockWifi=false caller=${LogRedactor.stableIdentity(packageName)}")
	            }
	        })
        Logger.info("[WlanHook] hooked getScanResults overloads=${scanHooks.size}")

        val matchingHooks = wifiClazz.hookAllMethods("getMatchingScanResults", afterHook {
            val packageName = args.firstOrNull { it is String } as? String ?: return@afterHook
            if (FakeLoc.enableDebugLog)
                Logger.debug("In getMatchingScanResults with caller: ${LogRedactor.stableIdentity(packageName)}, state: ${FakeLoc.enableMockWifi}")

            if (!FakeLoc.enableMockWifi || result == null) {
                logOnce("wifi.matching.skipped.${LogRedactor.stableIdentity(packageName)}", "[WlanHook] getMatchingScanResults skipped: caller=${LogRedactor.stableIdentity(packageName)}, result=${result?.javaClass?.name ?: "null"}")
                return@afterHook
            }
            if (safeBuildAndReplaceScan("getMatchingScanResults")) {
                logOnce("wifi.matching.replaced.${LogRedactor.stableIdentity(packageName)}", "[WlanHook] getMatchingScanResults replaced: caller=${LogRedactor.stableIdentity(packageName)}")
            }
        })
        Logger.info("[WlanHook] hooked getMatchingScanResults overloads=${matchingHooks.size}")
	    }

    private fun hookWifiScannerService(classLoader: ClassLoader, source: String) {
        if (!FakeLoc.hookWifi) {
            Logger.warn("[WlanHook] WifiScannerService hook skipped from $source: hookWifi=false")
            return
        }

        // Android 12+ WifiScannerService
        val cWifiScannerService = XposedHelpers.findClassIfExists("com.android.server.wifi.scanner.WifiScannerService", classLoader)
            ?: XposedHelpers.findClassIfExists("com.android.server.wifi.WifiScannerServiceImpl", classLoader)
            ?: XposedHelpers.findClassIfExists("com.android.server.wifi.scanner.WifiScanningServiceImpl", classLoader)
            ?: run {
                Logger.warn("[WlanHook] WifiScannerService not found from $source")
                return
            }

        Logger.debug("[WlanHook] Found WifiScannerService from $source")

        val directHooks = cWifiScannerService.hookAllMethods("getScanResults", afterHook {
            if (result == null || !FakeLoc.enableMockWifi) {
                logOnce("wifi.scanner.direct.skipped.$source", "[WlanHook] WifiScannerService.getScanResults skipped: enableMockWifi=${FakeLoc.enableMockWifi}, result=${result?.javaClass?.name ?: "null"} source=$source")
                return@afterHook
            }
            if (safeBuildAndReplaceScan("WifiScannerService.getScanResults")) {
                logOnce("wifi.scanner.direct.replaced.$source", "[WlanHook] WifiScannerService.getScanResults replaced: source=$source")
                return@afterHook
            }
            Logger.warn("[WlanHook] WifiScannerService.getScanResults unsupported result=${result?.javaClass?.name}")
        })
        Logger.info("[WlanHook] hooked WifiScannerService.getScanResults overloads=${directHooks.size} from $source")

        // Also hook requestSingleScan and callback for scan results
        val listenerMethods = cWifiScannerService.declaredMethods.filter {
            it.name == "registerScanListener" || it.name == "registerWifiScanner"
        }
        if (listenerMethods.isEmpty()) {
            Logger.warn("[WlanHook] WifiScannerService listener registration methods not found on ${cWifiScannerService.name}")
        }
        listenerMethods.forEach { method ->
            method.onceHookBefore {
                if (!FakeLoc.enableMockWifi) {
                    logOnce("wifi.scanner.listener.disabled.$source", "[WlanHook] ${method.name} skipped: enableMockWifi=false source=$source")
                    return@onceHookBefore
                }
                logOnce("wifi.scanner.listener.hit.$source.${method.name}", "[WlanHook] ${method.name} hit: args=${args.map { it?.javaClass?.name }} source=$source")
                // Hook the listener's onScanResults/onResults callback
                args.filterNotNull().forEach { arg ->
                    runCatching {
                        val listenerClass = arg.javaClass
                        listenerClass.onceHookAllMethod("onScanResults", afterHook {
                            if (FakeLoc.enableMockWifi && result != null) {
                                if (safeBuildAndReplaceScan("WifiScannerService.onScanResults")) {
                                    logOnce("wifi.scanner.callback.onScanResults.${listenerClass.name}", "[WlanHook] onScanResults callback replaced")
                                    return@afterHook
                                }
                            }
                        })
                        listenerClass.onceHookAllMethod("onResults", afterHook {
                            if (FakeLoc.enableMockWifi && result != null) {
                                if (safeBuildAndReplaceScan("WifiScannerService.onResults")) {
                                    logOnce("wifi.scanner.callback.onResults.${listenerClass.name}", "[WlanHook] onResults callback replaced")
                                    return@afterHook
                                }
                            }
                        })
                    }.onFailure {
                        Logger.warn("[WlanHook] Failed to hook scan listener callbacks", it)
                    }
                }
            }
            Logger.info("[WlanHook] hooked WifiScannerService listener method: ${method.name} ${method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }}")
        }
    }

    private fun buildMockScanResults(snapshot: tv.kyshu.xposed.stability.WirelessRuntimeSnapshot = WirelessRuntime.snapshotOrLegacy()): List<ScanResult> {
        return WifiScanResultFactory.createFromSnapshot(snapshot.wifiScan, snapshot.wifiScanLimit.coerceAtMost(MAX_MOCK_SCAN_RESULTS))
    }

    private fun buildScanReplacement(original: Any?, scanResults: List<ScanResult>): Any? {
        return when (original) {
            is List<*> -> scanResults
            is Array<*> -> scanResults.toTypedArray()
            null -> null
            else -> if (original.javaClass.name.contains("ParceledListSlice")) {
                val constructor = original.javaClass.getConstructor(List::class.java)
                if (!constructor.isAccessible) constructor.isAccessible = true
                constructor.newInstance(scanResults)
            } else null
        }
    }

    private fun XC_MethodHook.MethodHookParam.safeBuildAndReplaceScan(stage: String): Boolean {
        val original = result
        val snapshot = WirelessRuntime.snapshotOrLegacy()
        if (!snapshot.wifiScanEnabled || DomainGuard.isDomainDisabled(DomainGuard.Domain.WLAN)) return false
        val replacement = replaceOrPassthrough(DomainGuard.Domain.WLAN, stage, original) {
            buildScanReplacement(original, buildMockScanResults(snapshot))
        }
        result = replacement
        return replacement !== original
    }

    private fun XC_MethodHook.MethodHookParam.replaceScanResult(scanResults: List<ScanResult>, label: String): Boolean {
        if (result is List<*>) {
            result = scanResults
            return true
        }

        if (result is Array<*>) {
            result = scanResults.toTypedArray()
            return true
        }

        val resultClass = result.javaClass
        if (resultClass.name.contains("ParceledListSlice")) {
            runCatching {
                val constructor = resultClass.getConstructor(List::class.java)
                if (!constructor.isAccessible) {
                    constructor.isAccessible = true
                }
                result = constructor.newInstance(scanResults)
                return true
            }.onFailure {
                Logger.error("$label: ParceledListSlice failed", it)
            }
        }

        return false
    }
}
