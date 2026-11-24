/*
 * Copyright (C) 2025 The KunYuShu Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tv.kyshu.xposed.hooks.miui

import android.os.Binder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.config.UidPolicy
import tv.kyshu.xposed.utils.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * HyperOS GpsProxyManager freeze exemption, ported from Kuroko-Teleport HyperOsHook.
 *
 * On Xiaomi HyperOS, com.miui.server.greeze.power.GpsProxyManager would freeze
 * GPS delivery for apps that "look stationary" — which corrupts a stationary
 * indoor mock session. Forcing isGpsProxied/checkIfIgnoreGps to false for
 * target UIDs keeps mock delivery alive.
 *
 * Gracefully no-ops on non-HyperOS builds (Flyme on the target device included):
 * class-not-found is logged once and hooks are skipped.
 */
object MiuiGpsProxyHook {
    private const val TAG = "Kyshu-HyperOS"

    private const val CLASS_GPS_PROXY_MANAGER = "com.miui.server.greeze.power.GpsProxyManager"

    private val loggedOnceKeys = ConcurrentHashMap.newKeySet<String>()

    fun initSystemServer(cl: ClassLoader) {
        hookGpsProxyManager(cl)
    }

    private fun hookGpsProxyManager(cl: ClassLoader) {
        try {
            val cls = XposedHelpers.findClassIfExists(CLASS_GPS_PROXY_MANAGER, cl)
            if (cls == null) {
                logOnce("no_class", "$TAG: $CLASS_GPS_PROXY_MANAGER not present (non-HyperOS build, skipping)")
                return
            }

            val bypassHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val uid = extractUidArgOrCaller(param.args)
                        if (uid > 0 && UidPolicy.hasPolicy(uid, UidPolicy.POLICY_LOCATION)) {
                            param.result = false
                        }
                    } catch (t: Throwable) {
                        logOnce("gps_proxy_err", "GpsProxyManager hook error: $t")
                    }
                }
            }

            val h1 = XposedBridge.hookAllMethods(cls, "isGpsProxied", bypassHook)
            val h2 = XposedBridge.hookAllMethods(cls, "checkIfIgnoreGps", bypassHook)
            Logger.info("$TAG: Hooked GpsProxyManager (isGpsProxied=${h1.size}, checkIfIgnoreGps=${h2.size})")
        } catch (t: Throwable) {
            logOnce("hook_gps_proxy", "$TAG: Failed hooking GpsProxyManager: $t")
        }
    }

    private fun extractUidArgOrCaller(args: Array<Any?>?): Int {
        if (args != null) {
            for (arg in args) {
                if (arg is Int && arg > 0) {
                    return arg
                }
            }
        }
        return try {
            Binder.getCallingUid()
        } catch (_: Throwable) {
            -1
        }
    }

    private fun logOnce(key: String, msg: String) {
        if (loggedOnceKeys.add(key)) {
            Logger.info(msg)
        }
    }
}
