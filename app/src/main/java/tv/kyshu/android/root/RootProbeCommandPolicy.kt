package tv.kyshu.android.root

import tv.kyshu.xposed.utils.LogRedactor
import java.util.Locale

/**
 * Safety gate for future rooted read-only probe commands.
 *
 * This class deliberately plans structured argv commands only. It does not
 * execute `su`, accept raw shell text, or build a shell pipeline.
 */
object RootProbeCommandPolicy {
    private const val MAX_ARG_COUNT = 12
    private const val MAX_ARG_LENGTH = 160
    private const val MAX_PURPOSE_LENGTH = 160

    private val blockedShellChars = setOf(';', '|', '`', '$', '(', ')', '{', '}', '<', '>', '&')
    private val destructiveCommands = setOf(
        "setprop",
        "svc",
        "ifconfig",
        "iw",
        "iwpriv",
        "stop",
        "start",
        "kill",
        "reboot",
        "mount",
        "umount",
        "dd",
        "fastboot",
        "flash_image",
        "flash_erase",
        "flashcp",
        "magisk",
    )
    private val blockedPathFragments = listOf(
        "/dev/block",
        "/efs",
        "/persist",
        "/modem",
        "/nvram",
        "/nvdata",
    )
    private val safeGetpropKeys = setOf(
        "ro.build.version.sdk",
        "ro.build.version.release",
        "ro.build.version.codename",
        "ro.build.version.incremental",
        "ro.build.version.security_patch",
        "ro.build.type",
        "ro.build.tags",
        "ro.product.brand",
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.product.name",
        "ro.product.device",
        "ro.hardware",
        "ro.board.platform",
    )
    private val sensitiveGetpropKeyParts = listOf(
        "serial",
        "imei",
        "meid",
        "imsi",
        "iccid",
        "android_id",
        "attestation",
        "unique",
    )
    private val safeSettingsKeys = mapOf(
        "secure" to setOf("location_mode"),
        "global" to setOf("airplane_mode_on", "wifi_on", "bluetooth_on"),
        "system" to setOf("screen_brightness_mode"),
    )
    private val safeDumpsysServices = setOf(
        "wifi",
        "location",
        "telephony.registry",
        "phone",
        "bluetooth_manager",
        "nfc",
    )
    private val safeCmdTuples = setOf(
        listOf("cmd", "wifi", "status"),
        listOf("cmd", "location", "providers"),
    )

    data class ProbeCommandRequest(
        val argv: List<String>,
        val purpose: String,
        val expectedFields: List<String> = emptyList(),
    )

    data class PlannedProbeCommand(
        val argv: List<String>,
        val purpose: String,
        val expectedFields: List<String>,
        val dryRun: Boolean,
        val preview: String,
    )

    data class Decision(
        val allowed: Boolean,
        val reason: String,
        val plan: PlannedProbeCommand? = null,
    )

    fun dryRun(request: ProbeCommandRequest): Decision = evaluate(request, dryRun = true)

    fun approveForExecution(request: ProbeCommandRequest): Decision = evaluate(request, dryRun = false)

    fun redactProbeOutput(output: String): String {
        var redacted = LogRedactor.redactForLog(output)
        redacted = identifierKeyValuePattern.replace(redacted) { match ->
            "identifier=<identifier>"
        }
        redacted = longIdentifierPattern.replace(redacted, "<identifier>")
        redacted = sensitiveIdentifierKeyPattern.replace(redacted, "identifier")
        return redacted
    }

    private fun evaluate(request: ProbeCommandRequest, dryRun: Boolean): Decision {
        val normalizedPurpose = request.purpose.trim()
        if (normalizedPurpose.isBlank()) return denied("missing_purpose")
        if (normalizedPurpose.length > MAX_PURPOSE_LENGTH) return denied("purpose_too_long")

        val argv = request.argv.map { it.trim() }
        val structuralIssue = validateStructure(argv)
        if (structuralIssue != null) return denied(structuralIssue)

        val denyReason = denylistReason(argv)
        if (denyReason != null) return denied(denyReason)

        val allowReason = allowlistReason(argv)
        if (allowReason == null) return denied("not_allowlisted")

        return Decision(
            allowed = true,
            reason = allowReason,
            plan = PlannedProbeCommand(
                argv = argv,
                purpose = normalizedPurpose,
                expectedFields = request.expectedFields.map { it.trim() }.filter { it.isNotBlank() },
                dryRun = dryRun,
                preview = argv.joinToString(" ") { displayToken(it) },
            ),
        )
    }

    private fun validateStructure(argv: List<String>): String? {
        if (argv.isEmpty()) return "empty_command"
        if (argv.size > MAX_ARG_COUNT) return "too_many_arguments"
        for (arg in argv) {
            if (arg.isEmpty()) return "empty_argument"
            if (arg.length > MAX_ARG_LENGTH) return "argument_too_long"
            if (arg.any { it == '\u0000' || it == '\n' || it == '\r' }) return "control_character"
            if (arg.any { it in blockedShellChars }) return "shell_metacharacter"
        }
        return null
    }

    private fun denylistReason(argv: List<String>): String? {
        val lower = argv.map { it.lowercase(Locale.US) }
        val command = lower.first()
        if (command in destructiveCommands) return "denylisted_command"
        if (command == "settings" && lower.getOrNull(1) in setOf("put", "delete", "reset")) {
            return "settings_write"
        }
        if (command == "service" &&
            lower.getOrNull(1) == "call" &&
            !RootProbeServiceCallCatalog.isAllowlisted(lower)
        ) {
            return "service_call_not_allowlisted"
        }
        if (command == "ip" && lower.getOrNull(1) == "link" && lower.getOrNull(2) == "set") {
            return "network_state_write"
        }
        if (command == "getprop" && lower.drop(1).any { key ->
                sensitiveGetpropKeyParts.any { part -> key.contains(part) }
            }
        ) {
            return "sensitive_property"
        }
        if (lower.any { arg -> blockedPathFragments.any { fragment -> arg.contains(fragment) } }) {
            return "blocked_partition_path"
        }
        return null
    }

    private fun allowlistReason(argv: List<String>): String? {
        val lower = argv.map { it.lowercase(Locale.US) }
        return when (lower.first()) {
            "getprop" -> allowGetprop(lower)
            "settings" -> allowSettingsGet(lower)
            "dumpsys" -> allowDumpsys(lower)
            "pm" -> allowPmPath(argv, lower)
            "cmd" -> if (lower in safeCmdTuples) "allowlisted_cmd_query" else null
            "service" -> allowServiceCall(lower)
            "logcat" -> allowLogcat(argv, lower)
            else -> null
        }
    }

    private fun allowGetprop(lower: List<String>): String? {
        if (lower.size != 2) return null
        return if (lower[1] in safeGetpropKeys) "allowlisted_getprop" else null
    }

    private fun allowSettingsGet(lower: List<String>): String? {
        if (lower.size != 4 || lower[1] != "get") return null
        val namespace = lower[2]
        val key = lower[3]
        return if (safeSettingsKeys[namespace]?.contains(key) == true) {
            "allowlisted_settings_get"
        } else {
            null
        }
    }

    private fun allowDumpsys(lower: List<String>): String? {
        if (lower.size !in 2..3) return null
        if (lower[1] !in safeDumpsysServices) return null
        if (lower.size == 3 && lower[2] != "--proto") return null
        return "allowlisted_dumpsys"
    }

    private fun allowPmPath(argv: List<String>, lower: List<String>): String? {
        if (lower.size != 3 || lower[1] != "path") return null
        return if (packageNamePattern.matches(argv[2])) "allowlisted_pm_path" else null
    }

    private fun allowServiceCall(lower: List<String>): String? {
        return if (RootProbeServiceCallCatalog.isAllowlisted(lower)) {
            "allowlisted_service_call"
        } else {
            null
        }
    }

    private fun allowLogcat(argv: List<String>, lower: List<String>): String? {
        if ("-d" !in lower) return null
        var index = 1
        while (index < argv.size) {
            when (lower[index]) {
                "-d" -> index += 1
                "-t" -> {
                    val count = lower.getOrNull(index + 1)?.toIntOrNull() ?: return null
                    if (count !in 1..1000) return null
                    index += 2
                }
                "-v" -> {
                    if (lower.getOrNull(index + 1) !in setOf("brief", "time", "threadtime", "tag")) return null
                    index += 2
                }
                "-s" -> {
                    index += 1
                    var sawTag = false
                    while (index < argv.size && !argv[index].startsWith("-")) {
                        if (!logcatTagPattern.matches(argv[index])) return null
                        sawTag = true
                        index += 1
                    }
                    if (!sawTag) return null
                }
                else -> return null
            }
        }
        return "allowlisted_logcat_dump"
    }

    private fun displayToken(token: String): String {
        return if (token.any { it.isWhitespace() }) {
            "'" + token.replace("'", "'\\''") + "'"
        } else {
            token
        }
    }

    private fun denied(reason: String): Decision = Decision(allowed = false, reason = reason)

    private val packageNamePattern = Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+""")
    private val logcatTagPattern = Regex("""[A-Za-z][A-Za-z0-9_.-]{0,60}(?::[VDIWEFS*])?|\*:[VDIWEFS*]""")
    private val identifierKeyValuePattern = Regex(
        """(?i)\b(imei|meid|imsi|iccid|android[_-]?id|serial(?:no)?|attestation[_-]?id)\s*[:=]\s*([A-Za-z0-9_-]{6,})"""
    )
    private val longIdentifierPattern = Regex("""\b\d{14,22}\b""")
    private val sensitiveIdentifierKeyPattern = Regex(
        """(?i)\b(?:imei|meid|imsi|iccid|android[_-]?id|serial(?:no)?|attestation[_-]?id|subscriber[_-]?id|device[_-]?id|line1[_-]?number|sim[_-]?serial)\b"""
    )
}
