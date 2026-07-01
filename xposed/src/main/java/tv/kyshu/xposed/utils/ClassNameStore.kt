package tv.kyshu.xposed.utils

object ClassNameStore {
    private val store = mapOf(
        "ActivityThread" to decrypt(""),
        "ApplicationPackageManager" to decrypt(""),
        "LocationManagerService" to decrypt(""),
        "ClipboardManager" to decrypt(""),
        "TelephonyManager" to decrypt(""),
        "WifiManager" to decrypt(""),
        "ConnectivityManager" to decrypt(""),
        "SensorManager" to decrypt(""),
        "LocationManager" to decrypt(""),
        "PackageManager" to decrypt("")
    )

    fun get(key: String): String = store[key] ?: error("Unknown class key: $key")

    private fun decrypt(encrypted: String): String {
        val key = "\u0000\u0001\u0002"
        return encrypted.mapIndexed { i, c -> (c.code xor key[i % key.length].code).toChar() }.joinToString("")
    }
}
