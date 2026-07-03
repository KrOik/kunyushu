package tv.kyshu.xposed.utils

object PortalIdentity {
    const val APPLICATION_ID = "tv.kyshu"
    const val LEGACY_PACKAGE_ID = "com.kunyushu.kunyushu"
    const val VERSION_NAME = "1.0.5"
    const val VERSION_CODE = 10005

    val CONTROL_PACKAGES: Set<String> = setOf(APPLICATION_ID, LEGACY_PACKAGE_ID)

    fun isControlPackage(packageName: String): Boolean {
        return packageName in CONTROL_PACKAGES
    }

    fun areAllControlPackages(packageNames: Array<String>): Boolean {
        return packageNames.isNotEmpty() && packageNames.all(::isControlPackage)
    }
}
