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
import kotlin.random.Random

object WlanHook {
    private const val MAX_MOCK_SCAN_RESULTS = 32

    operator fun invoke(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cSystemServerClassLoaderFactory = XposedHelpers.findClassIfExists("com.android.internal.os.SystemServerClassLoaderFactory", classLoader)
            if (cSystemServerClassLoaderFactory == null) {
                Logger.warn("Failed to find SystemServerClassLoaderFactory")
                return
            }
            val sLoadedPaths = XposedHelpers.getStaticObjectField(cSystemServerClassLoaderFactory, "sLoadedPaths") as ArrayMap<String, PathClassLoader>
            val wifiClassLoader = sLoadedPaths.firstNotNullOfOrNull {
                if (it.key.contains("service-wifi.jar")) it.value else null
            }
            if (wifiClassLoader == null) {
                Logger.warn("Failed to find wifiClassLoader")
                return
            }
            val wifiClazz = "com.android.server.wifi.WifiServiceImpl".toClass(wifiClassLoader)
            if (wifiClazz == null) {
                Logger.warn("Failed to find WifiServiceImpl class")
                return
            }
            hookWifiServiceImpl(wifiClazz)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cSystemServiceManager = XposedHelpers.findClassIfExists("com.android.server.SystemServiceManager", classLoader)
            if (cSystemServiceManager == null) {
                Logger.warn("Failed to find SystemServiceManager")
                return
            }
            cSystemServiceManager.hookAllMethods("loadClassFromLoader", afterHook {
                if (args[0] == "com.android.server.wifi.WifiService") {
                    kotlin.runCatching {
                        val classloader = args[1] as PathClassLoader
                        val wifiClazz = classloader.loadClass("com.android.server.wifi.WifiServiceImpl")
                        hookWifiServiceImpl(wifiClazz)
                    }.onFailure {
                        Logger.error("Failed to hook WifiService", it)
                    }
                }
            })
        }
    }

	    private fun hookWifiServiceImpl(wifiClazz: Class<*>) {
	        if (!FakeLoc.hookWifi) return

        // 使用 afterHook 让原方法执行，保留真实连接状态（networkId/supplicantState/linkSpeed/frequency 等），
        // 仅在副本上覆盖身份字段（MAC/BSSID/SSID/IP/RSSI），避免裸 WifiInfo 导致系统判定未连接而断网。
        wifiClazz.hookAllMethods("getConnectionInfo", afterHook {
            val packageName = args[0] as? String ?: return@afterHook
            if (FakeLoc.enableDebugLog)
                Logger.debug("In getConnectionInfo with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

            if (!FakeLoc.enableMockWifi) return@afterHook
            if (BinderUtils.isSystemPackages(packageName)) return@afterHook

            val realInfo = result as? WifiInfo ?: return@afterHook
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
        })

	        wifiClazz.hookAllMethods("getScanResults", afterHook {
	            val packageName = args[0] as? String
            if (packageName.isNullOrEmpty()) {
                return@afterHook
            }

            if (FakeLoc.enableDebugLog)
                Logger.debug("In getScanResults with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

	            if(FakeLoc.enableMockWifi) {
	                if(result == null) {
	                    return@afterHook
	                }

	                val scanResults = buildMockScanResults()

	                if (replaceScanResult(scanResults, "getScanResults")) return@afterHook

	                if (FakeLoc.enableDebugLog) {
	                    Logger.error("getScanResults: Unknown return type: ${result?.javaClass?.name}")
	                }
	            }
	        })

        wifiClazz.hookAllMethods("getMatchingScanResults", afterHook {
            val packageName = args.firstOrNull { it is String } as? String ?: return@afterHook
            if (FakeLoc.enableDebugLog)
                Logger.debug("In getMatchingScanResults with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

            if (!FakeLoc.enableMockWifi || result == null) return@afterHook
            replaceScanResult(buildMockScanResults(), "getMatchingScanResults")
        })
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
