@file:Suppress("LocalVariableName", "PrivateApi")
package tv.kyshu.xposed.utils

import android.content.Context
import android.os.Binder
import android.os.Build
object BinderUtils {
    private val TRUSTED_SYSTEM_LOCATION_PACKAGES = setOf(
        "com.android.location.fused",
        "com.android.phone",
        "com.oplus.location",
        "com.xiaomi.location.fused",
        "com.xiaomi.metoknlp",
        "com.vivo.location.fused",
        "com.huawei.location.fused",
        "com.samsung.location.fused",
    )

    internal fun isAllowedControlPackage(packageNames: Array<String>): Boolean {
        return PortalIdentity.areAllControlPackages(packageNames)
    }

    internal fun isTrustedSystemLocationPackage(packageNames: Array<String>): Boolean {
        return packageNames.isNotEmpty() && packageNames.all { it in TRUSTED_SYSTEM_LOCATION_PACKAGES }
    }

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
            Logger.warn("getSystemContext failed", e)
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
            uid in 0 until 10000 -> {
                // System services such as system_server(1000) and com.android.phone(1001)
                // host in-process hooks and need the control provider to sync config.
                Logger.debug("isLocationProviderEnabled: system uid=$uid allowed")
                return true
            }
            uid >= 10000 -> {
                val packageNames = getUidPackageNames(uid = uid) ?: run {
                    Logger.warn("isLocationProviderEnabled: uid=$uid no packages, denied")
                    return false
                }
                if (isAllowedControlPackage(packageNames)) {
                    Logger.debug("isLocationProviderEnabled: uid=$uid package_count=${packageNames.size} allowed")
                    return true
                }
                if (isTrustedSystemLocationPackage(packageNames)) {
                    Logger.debug("isLocationProviderEnabled: uid=$uid package_count=${packageNames.size} trusted location service allowed")
                    return true
                }
                Logger.warn("isLocationProviderEnabled: uid=$uid package_count=${packageNames.size} denied")
                return false
            }
            else -> {
                Logger.warn("isLocationProviderEnabled: uid=$uid non-system non-app uid, denied")
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
