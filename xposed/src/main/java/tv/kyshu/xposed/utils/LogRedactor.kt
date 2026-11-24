package tv.kyshu.xposed.utils

import java.security.MessageDigest
import java.util.Locale

/**
 * Central log redaction policy for retained logs and user-authorized exports.
 *
 * The storage pass keeps diagnostics useful while removing high-risk raw values.
 * The export pass is stricter because SukiSU/KernelSU bugreports are normally
 * shared outside the app-private trust boundary.
 */
object LogRedactor {
    private val keyValuePattern = Regex(
        """(?i)"?\b([a-z0-9_.-]*(?:session[_-]?key|auth[_-]?(?:nonce|signature|token)|nonce|signature|token|secret|password|challenge|fingerprint|boot[_-]?id|serial|signer|certificate|rinex[_-]?path|export[_-]?path|path|uid|pid|tid|mock[_-]?(?:ssid|bssid|mac|ip)|ssid|bssid|mac|ip|nmea|trajectory|track|route|locations?|payload(?:[_-]?(?:json|hex))?|ndef(?:[_-]?payload)?(?:[_-]?hex)?|uid[_-]?hex|ble[_-]?(?:mac|address)|address|manufacturer[_-]?data(?:[_-]?hex)?|service[_-]?data(?:[_-]?hex)?|cell[_-]?(?:id|cid|nci)|cid|nci|tac|lac|pci|psc|arfcn|nrarfcn)[a-z0-9_.-]*)\b"?\s*[:=]\s*("[^"]*"|[^,\s;}]+)"""
    )
    private val coordinatePattern = Regex(
        """(?i)"?\b(lat|lon|latitude|longitude)\b"?\s*[:=]\s*(-?\d{1,3}(?:\.\d+)?)"""
    )
    private val coordinateTuplePattern = Regex(
        """(?i)\b(mock|coord|coordinate|location)\s*([:=])\s*\(\s*(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*\)"""
    )
    private val trajectoryPattern = Regex(
        """(?i)"?\b(trajectory|track|route|locations?)\b"?\s*[:=]\s*("[^"]*"|[^\s}\]]+)"""
    )
    private val processIdWordPattern = Regex(
        """(?i)\b(uid|pid|tid)\s+(\d{1,10})\b"""
    )
    private val packageKeyPattern = Regex(
        """(?i)\b((?:from[_-]?)?package|pkg|process|proc|caller)\s*[:=]\s*([A-Za-z][A-Za-z0-9_]*(?:[.:][A-Za-z0-9_]+)+)"""
    )
    private val packageNamePattern = Regex(
        """\b[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+){2,}(?::[A-Za-z0-9_]+)?\b"""
    )
    private val absolutePathPattern = Regex(
        """(?<![A-Za-z0-9_])/(?:data|proc|sys|system|vendor|apex|mnt|storage|sdcard|dev)(?:/[^\s,;)\]}"']+)+"""
    )
    private val uuidPattern = Regex(
        """\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""
    )
    private val macPattern = Regex("""(?i)\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\b""")
    private val ipPattern = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val nmeaSentencePattern = Regex("""\$(?:GP|GN|GL|GA|GB|BD|GQ)[A-Z]{3}(?:,[^\s$]*)?(?:\*[0-9A-Fa-f]{2})?""")
    private val longHexPattern = Regex("""\b[0-9a-fA-F]{32,}\b""")
    private val longBlobPattern = Regex("""\b[A-Za-z0-9+/]{40,}={0,2}\b""")
    private val addressPattern = Regex("""(?i)\b0x[0-9a-f]{6,}\b|@[0-9a-f]{6,}\b""")

    private val sensitiveKeyParts = listOf(
        "session", "auth", "nonce", "signature", "token", "secret", "password",
        "challenge", "fingerprint", "boot_id", "boot-id", "serial", "signer",
        "certificate", "rinex_path", "rinex-path", "export_path", "export-path",
        "path", "uid", "pid", "tid", "ssid", "bssid", "mac", "ip",
        "latitude", "longitude", "lat", "lon", "nmea", "payload", "ndef",
        "trajectory", "track", "route", "location", "locations",
        "uid_hex", "uid-hex", "ble_mac", "ble-mac", "ble_address", "ble-address",
        "manufacturer_data", "manufacturer-data", "service_data", "service-data",
        "cell_id", "cell-id", "cid", "nci", "tac", "lac", "pci", "psc",
        "arfcn", "nrarfcn",
    )

    fun redactForLog(input: String?): String {
        if (input.isNullOrEmpty()) return input.orEmpty()
        var out = input
        out = coordinateTuplePattern.replace(out) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}(<coord>,<coord>)"
        }
        out = trajectoryPattern.replace(out) { match ->
            "${match.groupValues[1]}=<route#${hashShort(match.groupValues[2])}>"
        }
        out = nmeaSentencePattern.replace(out) { match -> "<nmea#${hashShort(match.value)}>" }
        out = coordinatePattern.replace(out) { match ->
            "${match.groupValues[1]}=<coord>"
        }
        out = processIdWordPattern.replace(out) { match ->
            "${match.groupValues[1]} <redacted#${hashShort(match.groupValues[2])}>"
        }
        out = packageKeyPattern.replace(out) { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            "$key=${classifyPackageOrProcess(value)}"
        }
        out = keyValuePattern.replace(out) { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            val normalizedKey = key.lowercase(Locale.US)
            val token = if (normalizedKey.contains("path")) pathToken(value) else redactedToken(value)
            "$key=$token"
        }
        out = absolutePathPattern.replace(out) { match -> pathToken(match.value) }
        out = uuidPattern.replace(out) { match -> "<uuid#${hashShort(match.value)}>" }
        out = macPattern.replace(out) { match -> "<mac#${hashShort(match.value)}>" }
        out = ipPattern.replace(out) { match -> "<ip#${hashShort(match.value)}>" }
        out = addressPattern.replace(out, "<addr>")
        return out
    }

    /** Stable non-reversible identity for telemetry; raw package names must never be retained. */
    fun stableIdentity(value: String?): String = if (value.isNullOrBlank()) "unknown" else "id#${hashShort(value)}"

    fun redactForExport(input: String?): String {
        if (input.isNullOrEmpty()) return input.orEmpty()
        var out = redactForLog(input)
        out = packageNamePattern.replace(out) { match -> classifyPackageOrProcess(match.value) }
        out = longHexPattern.replace(out) { match -> "<hex#${hashShort(match.value)}>" }
        out = longBlobPattern.replace(out) { match -> "<blob#${hashShort(match.value)}>" }
        return out
    }

    fun redactMapForLog(extra: Map<String, Any?>): Map<String, Any?> {
        if (extra.isEmpty()) return extra
        return extra.mapValues { (key, value) -> redactValue(key, value, export = false) }
    }

    fun redactValueForExport(key: String, value: Any?): Any? = redactValue(key, value, export = true)

    fun throwableSummary(error: Throwable): String {
        val message = error.message
            ?.let(::redactForLog)
            ?.take(160)
            .orEmpty()
        val stack = error.stackTrace
        val signature = hashShort(
            buildString {
                append(error.javaClass.name)
                stack.take(12).forEach {
                    append('|').append(it.className).append('#').append(it.methodName).append(':').append(it.lineNumber)
                }
            }
        )
        val topFrame = stack.firstOrNull { it.className.startsWith("tv.kyshu.") } ?: stack.firstOrNull()
        val top = topFrame?.let {
            val className = it.className.substringAfterLast('.')
            " top=$className#${it.methodName}:${it.lineNumber}"
        }.orEmpty()
        val msg = if (message.isBlank()) "" else ": $message"
        return "${error.javaClass.simpleName}$msg stack=$signature$top"
    }

    private fun redactValue(key: String, value: Any?, export: Boolean): Any? {
        if (value == null) return null
        val normalizedKey = key.lowercase(Locale.US)
        if (isSensitiveKey(normalizedKey)) {
            return when (value) {
                is Boolean -> value
                is Number -> if (isCoordinateKey(normalizedKey)) "<coord>" else "<redacted#${hashShort(value.toString())}>"
                else -> {
                    val raw = value.toString()
                    when {
                        isPackageKey(normalizedKey) -> classifyPackageOrProcess(raw)
                        normalizedKey.contains("path") -> pathToken(raw)
                        isCoordinateKey(normalizedKey) -> "<coord>"
                        else -> redactedToken(raw)
                    }
                }
            }
        }
        return when (value) {
            is String -> if (export) redactForExport(value) else redactForLog(value)
            is Iterable<*> -> "count=${value.count()}"
            is Array<*> -> "count=${value.size}"
            is ByteArray -> "bytes=${value.size}#${hashShort(value.joinToString(","))}"
            else -> value
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        return sensitiveKeyParts.any { key.contains(it) }
    }

    private fun isCoordinateKey(key: String): Boolean {
        return key == "lat" || key == "lon" || key.contains("latitude") || key.contains("longitude")
    }

    private fun isPackageKey(key: String): Boolean {
        return key.contains("package") || key == "pkg" || key.contains("process") || key == "proc"
    }

    private fun redactedToken(value: String): String = "<redacted#${hashShort(value)}>"

    private fun pathToken(path: String): String {
        val category = when {
            path.startsWith("/data/user/") || path.startsWith("/data/data/") -> "app_private"
            path.startsWith("/data/app/") -> "apk"
            path.startsWith("/data/adb/modules/") -> "module"
            path.startsWith("/proc/") -> "proc"
            path.startsWith("/sys/") -> "sys"
            path.startsWith("/system/") -> "system"
            path.startsWith("/vendor/") -> "vendor"
            path.startsWith("/storage/") || path.startsWith("/sdcard/") -> "shared_storage"
            else -> "path"
        }
        return "<$category#${hashShort(path)}>"
    }

    private fun classifyPackageOrProcess(value: String): String {
        val base = value.substringBefore(':')
        return when {
            value == "system_server" || base == "android" -> "system_server"
            base == PortalIdentity.APPLICATION_ID || base == PortalIdentity.LEGACY_PACKAGE_ID -> "own_app"
            base.startsWith("android.") || base.startsWith("com.android.") -> "system_pkg#${hashShort(base)}"
            base.startsWith("com.google.android.") -> "system_pkg#${hashShort(base)}"
            else -> "target_app#${hashShort(value)}"
        }
    }

    private fun hashShort(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }
    }
}
