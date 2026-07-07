package tv.kyshu.xposed.utils

import android.content.Context
import android.os.Build

object PortalIdentity {
    const val APPLICATION_ID = "tv.kyshu"
    const val LEGACY_PACKAGE_ID = "com.kunyushu.kunyushu"
    const val VERSION_NAME = "1.0.8"
    const val VERSION_CODE = 10008

    val CONTROL_PACKAGES: Set<String> = setOf(APPLICATION_ID, LEGACY_PACKAGE_ID)

    fun isControlPackage(packageName: String): Boolean {
        return packageName in CONTROL_PACKAGES
    }

    fun areAllControlPackages(packageNames: Array<String>): Boolean {
        return packageNames.isNotEmpty() && packageNames.all(::isControlPackage)
    }

    @Suppress("DEPRECATION")
    fun runtimeVersionSummary(context: Context? = null): String {
        val installed = context?.let { ctx ->
            runCatching {
                val info = ctx.packageManager.getPackageInfo(APPLICATION_ID, 0)
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    info.versionCode.toLong()
                }
                "${info.versionName ?: "unknown"}($code)"
            }.getOrNull()
        } ?: "unavailable"
        return "applicationId=$APPLICATION_ID identity=$VERSION_NAME($VERSION_CODE) installed=$installed"
    }
}
