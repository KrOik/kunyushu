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
import tv.kyshu.xposed.utils.beforeHook
import tv.kyshu.xposed.utils.hookAllMethods
import tv.kyshu.xposed.utils.hookMethodAfter
import tv.kyshu.xposed.utils.toClass

object WlanHook {
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

        wifiClazz.hookAllMethods("getConnectionInfo", beforeHook {
            val packageName = args[0] as String
            if (FakeLoc.enableDebugLog)
                Logger.debug("In getConnectionInfo with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

            if (FakeLoc.enableMockWifi && !BinderUtils.isSystemPackages(packageName)) {
                val wifiInfo = WifiInfo::class.java.getConstructor().newInstance()
                val wifiData = FakeLoc.wifiData

                val mac = if (wifiData.mockMac.isNotBlank() && wifiData.mockMac != "02:00:00:00:00:00")
                    wifiData.mockMac
                else
                    String.format("02:%02x:%02x:%02x:%02x:%02x", Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
                XposedHelpers.callMethod(wifiInfo, "setMacAddress", mac)

                val bssid = if (wifiData.mockBSSID.isNotBlank() && wifiData.mockBSSID != "02:00:00:00:00:00")
                    wifiData.mockBSSID
                else
                    String.format("%02x:%02x:%02x:%02x:%02x:%02x", Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
                XposedHelpers.callMethod(wifiInfo, "setBSSID", bssid)

                val ssid = if (wifiData.mockSSID.isNotBlank()) wifiData.mockSSID else "WiFi_${Random.nextInt(1000, 9999)}"
                XposedHelpers.callMethod(wifiInfo, "setSSID", ssid)

                kotlin.runCatching {
                    val inetAddr = java.net.InetAddress.getByName(wifiData.mockIp)
                    XposedHelpers.callMethod(wifiInfo, "setInetAddress", inetAddr)
                }.onFailure { Logger.warn("[WlanHook] setInetAddress failed", it) }

                wifiData.updateSignalState()
                XposedHelpers.setIntField(wifiInfo, "mRssi", (-65 + wifiData.currentRssiOffset).coerceIn(-127, -1))

                result = wifiInfo
            }
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

                val wifiData = FakeLoc.wifiData
                val count = wifiData.mockWifiScanCount
                val scanResults = if (count > 0) {
                    (1..count).map {
                        val sr = XposedHelpers.newInstance(ScanResult::class.java)
                        XposedHelpers.setObjectField(sr, "SSID", "WiFi_${Random.nextInt(1000, 9999)}")
                        val bssid = String.format("%02x:%02x:%02x:%02x:%02x:%02x", Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
                        XposedHelpers.setObjectField(sr, "BSSID", bssid)
                        XposedHelpers.setIntField(sr, "level", Random.nextInt(-90, -29))
                        XposedHelpers.setIntField(sr, "frequency", 2412 + Random.nextInt(0, 13) * 5)
                        sr
                    }
                } else {
                    val wifiList = wifiData.mockWifiList
                    if (wifiList.isNotEmpty()) {
                        wifiList.map { wifi ->
                            val sr = XposedHelpers.newInstance(ScanResult::class.java)
                            XposedHelpers.setObjectField(sr, "SSID", wifi.ssid)
                            XposedHelpers.setObjectField(sr, "BSSID", wifi.bssid)
                            XposedHelpers.setIntField(sr, "level", wifi.rssi)
                            XposedHelpers.setIntField(sr, "frequency", wifi.frequency)
                            sr
                        }
                    } else {
                        (1..(3 + Random.nextInt(0, 3))).map {
                            val sr = XposedHelpers.newInstance(ScanResult::class.java)
                            XposedHelpers.setObjectField(sr, "SSID", "WiFi_${Random.nextInt(1000, 9999)}")
                            val bssid = String.format("%02x:%02x:%02x:%02x:%02x:%02x", Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
                            XposedHelpers.setObjectField(sr, "BSSID", bssid)
                            XposedHelpers.setIntField(sr, "level", Random.nextInt(-90, -29))
                            XposedHelpers.setIntField(sr, "frequency", 2412 + Random.nextInt(0, 13) * 5)
                            sr
                        }
                    }
                }

                if (result is List<*>) {
                    result = scanResults
                    return@afterHook
                }

                if (result is Array<*>) {
                    result = scanResults.toTypedArray()
                    return@afterHook
                }

                val resultClass = result.javaClass
                if (resultClass.name.contains("ParceledListSlice")) runCatching {
                    val constructor = resultClass.getConstructor(List::class.java)
                    if (!constructor.isAccessible) {
                        constructor.isAccessible = true
                    }
                    result = constructor.newInstance(scanResults)
                    return@afterHook
                }.onFailure {
                    Logger.error("getScanResults: ParceledListSlice failed", it)
                }

                if (FakeLoc.enableDebugLog) {
                    Logger.error("getScanResults: Unknown return type: ${result?.javaClass?.name}")
                }
            }
        })
    }
}
