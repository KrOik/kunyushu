@file:Suppress("LocalVariableName", "PrivateApi")
package tv.kyshu.xposed.utils

import android.content.Context
import android.os.Binder
import android.os.Build
import de.robv.android.xposed.XposedBridge


object BinderUtils {
    private fun getActivityContext(): Context? {
        // public static ActivityManagerService self()
        // frameworks/base/services/java/com/android/server/am/ActivityManagerService.java
        try {
            val cam = Class.forName("com.android.server.am.ActivityManagerService")
            val am = cam.getMethod("self").invoke(null) ?: return null
            val mContext = cam.getDeclaredField("mContext")
            mContext.isAccessible = true
            return mContext.get(am) as? Context
        } catch (e: Throwable) {
            return null
        }
    }

    fun getSystemContext(): Context? {
        try {
            val cActivityThread = Class.forName("android.app.ActivityThread")
            val activityThread = cActivityThread.getMethod("currentActivityThread")
                .invoke(null) ?: return null
            return (cActivityThread.getMethod("getSystemContext").invoke(activityThread) as? Context) ?: getActivityContext()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return null
    }

    fun getUidPackageNames(context: Context = getSystemContext()!!, uid: Int = getCallerUid()): Array<String>? {
        val packageManager = context.packageManager
        return packageManager.getPackagesForUid(uid)
    }

    fun getCallerUid(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            kotlin.runCatching { Binder.getCallingUidOrThrow() }.getOrNull() ?: -1
        } else {
            Binder.getCallingUid()
        }
    }

    /**
     * Check whether the `PortalService` is started properly
     */
    fun isLocationProviderEnabled(uid: Int): Boolean {
        when {
            uid == 1000 -> {
                // system_server (UID 1000): Xposed module runs here
                Logger.debug("isLocationProviderEnabled: system_server(1000) allowed")
                return true
            }
            uid < 10000 -> {
                // Other system UIDs — log but allow (for compatibility)
                Logger.warn("isLocationProviderEnabled: system uid=$uid, allowed (audit)")
                return true
            }
            else -> {
                // App UID: must exactly belong to tv.kyshu package
                val packageNames = getUidPackageNames(uid = uid) ?: run {
                    Logger.warn("isLocationProviderEnabled: uid=$uid no packages, denied")
                    return false
                }
                if (packageNames.any { it == "tv.kyshu" }) {
                    Logger.debug("isLocationProviderEnabled: uid=$uid tv.kyshu allowed")
                    return true
                }
                Logger.warn("isLocationProviderEnabled: uid=$uid $packageNames denied")
                return false
            }
        }
    }

    fun isSystemPackages(packageNames: String): Boolean {
        if (packageNames.contains("com.xiaomi.location.fused") ||
            packageNames.contains("com.xiaomi.metoknlp") ||
            packageNames.contains("com.android.location.fused")
        ) {
            return false
        }
        return packageNames.contains("com.android") ||
                packageNames.contains("com.miui") ||
                packageNames.contains("com.xiaomi") ||
                packageNames.contains("com.oplus") ||
                packageNames.contains("com.coloros") ||
                packageNames.contains("com.heytap") ||
                packageNames.contains("android.framework") ||
                packageNames.contains("com.qualcomm") ||
                packageNames.contains("com.google.android.permissioncontroller")
    }

    fun isSystemAppsCall(uid: Int = getCallerUid()): Boolean {
        if (uid > 10000) {
            val packageNames = kotlin.runCatching { getUidPackageNames(uid = uid)?.joinToString() }
                .getOrNull() ?: return false
            return isSystemPackages(packageNames)
        }
        return true
    }
}