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
import tv.kyshu.xposed.utils.BinderUtils
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.afterHook
import tv.kyshu.xposed.utils.hookAllMethods
import tv.kyshu.xposed.utils.hookMethodAfter
import tv.kyshu.xposed.utils.toClass
import tv.kyshu.xposed.utils.onceHookAllMethod
import tv.kyshu.xposed.utils.onceHookBefore
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object WlanHook {
    private const val MAX_MOCK_SCAN_RESULTS = 32
    private val diagnosticKeys = ConcurrentHashMap.newKeySet<String>()

    private fun logOnce(key: String, message: String) {
        if (diagnosticKeys.add(key)) {
            Logger.info(message)
        }
    }

    operator fun invoke(classLoader: ClassLoader) {
        Logger.info("[WlanHook] install start: sdk=${Build.VERSION.SDK_INT}, hookWifi=${FakeLoc.hookWifi}, enableMockWifi=${FakeLoc.enableMockWifi}, classLoader=$classLoader")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cSystemServerClassLoaderFactory = XposedHelpers.findClassIfExists("com.android.internal.os.SystemServerClassLoaderFactory", classLoader)
            if (cSystemServerClassLoaderFactory == null) {
                Logger.warn("[WlanHook] Failed to find SystemServerClassLoaderFactory")
                return
            }
            val sLoadedPaths = XposedHelpers.getStaticObjectField(cSystemServerClassLoaderFactory, "sLoadedPaths") as ArrayMap<String, PathClassLoader>
            Logger.info("[WlanHook] SystemServerClassLoaderFactory paths=${sLoadedPaths.keys.joinToString(limit = 8)}")
            val wifiClassLoader = sLoadedPaths.firstNotNullOfOrNull {
                if (it.key.contains("service-wifi.jar")) it.value else null
            }
            if (wifiClassLoader == null) {
                Logger.warn("[WlanHook] Failed to find wifiClassLoader for service-wifi.jar")
                return
            }
            Logger.info("[WlanHook] wifiClassLoader resolved: $wifiClassLoader")
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
                        val classloader = args[1] as PathClassLoader
                        val wifiClazz = classloader.loadClass("com.android.server.wifi.WifiServiceImpl")
                        Logger.info("[WlanHook] WifiService loaded via SystemServiceManager: loader=$classloader")
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
        Logger.info("[WlanHook] hooking WifiServiceImpl: ${wifiClazz.name}, loader=${wifiClazz.classLoader}, methods=${wifiClazz.declaredMethods.size}")

        // 使用 afterHook 让原方法执行，保留真实连接状态（networkId/supplicantState/linkSpeed/frequency 等），
        // 仅在副本上覆盖身份字段（MAC/BSSID/SSID/IP/RSSI），避免裸 WifiInfo 导致系统判定未连接而断网。
        val connectionHooks = wifiClazz.hookAllMethods("getConnectionInfo", afterHook {
            val packageName = args.firstOrNull { it is String } as? String ?: run {
                logOnce("wifi.connection.noPackage", "[WlanHook] getConnectionInfo skipped: no package string args=${args.map { it?.javaClass?.simpleName }}")
                return@afterHook
            }
            if (FakeLoc.enableDebugLog)
                Logger.debug("In getConnectionInfo with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

            if (!FakeLoc.enableMockWifi) {
                logOnce("wifi.connection.disabled", "[WlanHook] getConnectionInfo skipped: enableMockWifi=false caller=$packageName")
                return@afterHook
            }
            if (BinderUtils.isSystemPackages(packageName)) {
                logOnce("wifi.connection.system.$packageName", "[WlanHook] getConnectionInfo skipped for system caller=$packageName")
                return@afterHook
            }

            val realInfo = result as? WifiInfo ?: run {
                logOnce("wifi.connection.badResult", "[WlanHook] getConnectionInfo skipped: result=${result?.javaClass?.name ?: "null"}")
                return@afterHook
            }
            val wifiData = FakeLoc.wifiData

            // 优先用拷贝构造保留全部真实字段；失败则回退到空实例
            val fakeInfo: WifiInfo = runCatching {
                WifiInfo::class.java.getConstructor(WifiInfo::class.java).newInstance(realInfo)
            }.getOrElse {
                Logger.warn("[WlanHook] WifiInfo copy ctor unavailable, fallback to empty instance", it)
                WifiInfo::class.java.getConstructor().newInstance()
            }

            val mac = if (wifiData.mockMac.isNotBlank() && wifiData.mockMac != "02:00:00:00:00:00")
                wifiData.mockMac
            else
                String.format("02:%02x:%02x:%02x:%02x:%02x", Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
            XposedHelpers.callMethod(fakeInfo, "setMacAddress", mac)

            val bssid = if (wifiData.mockBSSID.isNotBlank() && wifiData.mockBSSID != "02:00:00:00:00:00")
                wifiData.mockBSSID
            else
                String.format("%02x:%02x:%02x:%02x:%02x:%02x", Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
            XposedHelpers.callMethod(fakeInfo, "setBSSID", bssid)

            val ssid = if (wifiData.mockSSID.isNotBlank()) wifiData.mockSSID else "WiFi_${Random.nextInt(1000, 9999)}"
            XposedHelpers.callMethod(fakeInfo, "setSSID", ssid)

            kotlin.runCatching {
                val inetAddr = java.net.InetAddress.getByName(wifiData.mockIp)
                XposedHelpers.callMethod(fakeInfo, "setInetAddress", inetAddr)
            }.onFailure { Logger.warn("[WlanHook] setInetAddress failed", it) }

            wifiData.updateSignalState()
            XposedHelpers.setIntField(fakeInfo, "mRssi", (-65 + wifiData.currentRssiOffset).coerceIn(-127, -1))

            result = fakeInfo
            logOnce("wifi.connection.replaced.$packageName",
                "[WlanHook] getConnectionInfo replaced: caller=$packageName ssid=$ssid bssid=$bssid mac=$mac rssi=${(-65 + wifiData.currentRssiOffset).coerceIn(-127, -1)}")
        })
        Logger.info("[WlanHook] hooked getConnectionInfo overloads=${connectionHooks.size}")

	        val scanHooks = wifiClazz.hookAllMethods("getScanResults", afterHook {
	            val packageName = args.firstOrNull { it is String } as? String
            if (packageName.isNullOrEmpty()) {
                logOnce("wifi.scan.noPackage", "[WlanHook] getScanResults skipped: no package string args=${args.map { it?.javaClass?.simpleName }}")
                return@afterHook
            }

            if (FakeLoc.enableDebugLog)
                Logger.debug("In getScanResults with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

	            if(FakeLoc.enableMockWifi) {
	                if(result == null) {
	                    logOnce("wifi.scan.null.$packageName", "[WlanHook] getScanResults skipped: null result caller=$packageName")
	                    return@afterHook
	                }

	                val scanResults = buildMockScanResults()

	                if (replaceScanResult(scanResults, "getScanResults")) {
                        logOnce("wifi.scan.replaced.$packageName",
                            "[WlanHook] getScanResults replaced: caller=$packageName resultType=${result?.javaClass?.name}, fakeSize=${scanResults.size}")
                        return@afterHook
                    }

                    Logger.warn("[WlanHook] getScanResults unsupported return type: ${result?.javaClass?.name}")
	            } else {
                    logOnce("wifi.scan.disabled", "[WlanHook] getScanResults skipped: enableMockWifi=false caller=$packageName")
	            }
	        })
        Logger.info("[WlanHook] hooked getScanResults overloads=${scanHooks.size}")

        val matchingHooks = wifiClazz.hookAllMethods("getMatchingScanResults", afterHook {
            val packageName = args.firstOrNull { it is String } as? String ?: return@afterHook
            if (FakeLoc.enableDebugLog)
                Logger.debug("In getMatchingScanResults with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

            if (!FakeLoc.enableMockWifi || result == null) {
                logOnce("wifi.matching.skipped.$packageName", "[WlanHook] getMatchingScanResults skipped: enableMockWifi=${FakeLoc.enableMockWifi}, result=${result?.javaClass?.name ?: "null"}")
                return@afterHook
            }
            val scanResults = buildMockScanResults()
            if (replaceScanResult(scanResults, "getMatchingScanResults")) {
                logOnce("wifi.matching.replaced.$packageName", "[WlanHook] getMatchingScanResults replaced: caller=$packageName fakeSize=${scanResults.size}")
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
                Logger.warn("[WlanHook] WifiScannerService not found from $source loader=$classLoader")
                return
            }

        Logger.info("[WlanHook] Found WifiScannerService from $source: ${cWifiScannerService.name}, loader=${cWifiScannerService.classLoader}")

        val directHooks = cWifiScannerService.hookAllMethods("getScanResults", afterHook {
            if (result == null || !FakeLoc.enableMockWifi) {
                logOnce("wifi.scanner.direct.skipped.$source", "[WlanHook] WifiScannerService.getScanResults skipped: enableMockWifi=${FakeLoc.enableMockWifi}, result=${result?.javaClass?.name ?: "null"} source=$source")
                return@afterHook
            }
            val scanResults = buildMockScanResults()
            if (replaceScanResult(scanResults, "WifiScannerService.getScanResults")) {
                logOnce("wifi.scanner.direct.replaced.$source", "[WlanHook] WifiScannerService.getScanResults replaced: source=$source fakeSize=${scanResults.size}")
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
                                val scanResults = buildMockScanResults()
                                if (replaceScanResult(scanResults, "WifiScannerService.onScanResults")) {
                                    logOnce("wifi.scanner.callback.onScanResults.${listenerClass.name}", "[WlanHook] onScanResults callback replaced: listener=${listenerClass.name} fakeSize=${scanResults.size}")
                                    return@afterHook
                                }
                            }
                        })
                        listenerClass.onceHookAllMethod("onResults", afterHook {
                            if (FakeLoc.enableMockWifi && result != null) {
                                val scanResults = buildMockScanResults()
                                if (replaceScanResult(scanResults, "WifiScannerService.onResults")) {
                                    logOnce("wifi.scanner.callback.onResults.${listenerClass.name}", "[WlanHook] onResults callback replaced: listener=${listenerClass.name} fakeSize=${scanResults.size}")
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

    private fun buildMockScanResults(): List<ScanResult> {
        val wifiData = FakeLoc.wifiData
        val count = wifiData.mockWifiScanCount.coerceIn(0, MAX_MOCK_SCAN_RESULTS)
        return if (count > 0) {
            (1..count).map { randomScanResult() }
        } else {
            val wifiList = wifiData.mockWifiList
            if (wifiList.isNotEmpty()) {
                wifiList.take(MAX_MOCK_SCAN_RESULTS).map { wifi ->
                    XposedHelpers.newInstance(ScanResult::class.java).also { sr ->
                        XposedHelpers.setObjectField(sr, "SSID", wifi.ssid)
                        XposedHelpers.setObjectField(sr, "BSSID", wifi.bssid)
                        XposedHelpers.setIntField(sr, "level", wifi.rssi)
                        XposedHelpers.setIntField(sr, "frequency", wifi.frequency)
                    } as ScanResult
                }
            } else {
                (1..(3 + Random.nextInt(0, 3))).map { randomScanResult() }
            }
        }
    }

    private fun randomScanResult(): ScanResult {
        return XposedHelpers.newInstance(ScanResult::class.java).also { sr ->
            XposedHelpers.setObjectField(sr, "SSID", "WiFi_${Random.nextInt(1000, 9999)}")
            XposedHelpers.setObjectField(sr, "BSSID", randomMac())
            XposedHelpers.setIntField(sr, "level", Random.nextInt(-90, -29))
            XposedHelpers.setIntField(sr, "frequency", 2412 + Random.nextInt(0, 13) * 5)
        } as ScanResult
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

    private fun randomMac(): String {
        return String.format(
            "%02x:%02x:%02x:%02x:%02x:%02x",
            Random.nextInt(256),
            Random.nextInt(256),
            Random.nextInt(256),
            Random.nextInt(256),
            Random.nextInt(256),
            Random.nextInt(256)
        )
    }
}
