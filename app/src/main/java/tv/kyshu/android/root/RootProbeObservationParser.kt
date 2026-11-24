package tv.kyshu.android.root

/**
 * Converts probe text into field-shape observations.
 *
 * The parser only keeps normalized field paths and coarse value types. It
 * drops raw values and skips fields rejected by [RootProbeEvidence].
 */
object RootProbeObservationParser {
    private const val MAX_LINES = 1000
    private const val MAX_FIELDS = 300

    private val keyValuePattern = Regex(
        """(?:^|[\s,{])([A-Za-z_][A-Za-z0-9_.\-\[\]]{0,96})\s*[:=]\s*("[^"]*"|'[^']*'|\[[^\]]*]|\{[^}]*}|[^,\s;}]+)"""
    )
    private val booleanPattern = Regex("""(?i)^(true|false|yes|no|on|off)$""")
    private val integerPattern = Regex("""^-?\d{1,10}$""")
    private val longPattern = Regex("""^-?\d{11,19}$""")
    private val floatPattern = Regex("""^-?\d+\.\d+$""")
    private val macPattern = Regex("""(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$""")
    private val ipv4Pattern = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
    private val hexPattern = Regex("""(?i)^(?:0x)?[0-9a-f]{6,}$""")
    private val vendorPattern = Regex("""(?i)(oplus|miui|samsung|vendor|qti|qualcomm|mediatek|mtk)""")
    private val mockablePattern = Regex(
        """(?i)(wifi|ssid|bssid|rssi|frequency|scan|cell|lac|tac|cid|nci|pci|arfcn|bluetooth|ble|gatt|nfc|ndef|tag|location|gnss|nmea)"""
    )

    data class Result(
        val observations: List<RootProbeEvidence.FieldObservation>,
        val skippedSensitive: Int,
        val skippedMalformed: Int,
    )

    fun parse(
        source: String,
        output: String,
        pathPrefix: String = source,
    ): Result {
        val observations = linkedMapOf<String, RootProbeEvidence.FieldObservation>()
        var skippedSensitive = 0
        var skippedMalformed = 0
        val prefix = normalizePathToken(pathPrefix).ifBlank { "probe" }

        output.lineSequence()
            .take(MAX_LINES)
            .forEach { line ->
                keyValuePattern.findAll(line).forEach { match ->
                    if (observations.size >= MAX_FIELDS) return@forEach
                    val key = normalizePathToken(match.groupValues[1])
                    if (key.isBlank()) {
                        skippedMalformed += 1
                        return@forEach
                    }
                    val valueType = inferType(match.groupValues[2])
                    val path = "$prefix.$key"
                    val observation = runCatching {
                        RootProbeEvidence.FieldObservation(
                            path = path,
                            type = valueType,
                            source = source,
                            mockable = mockablePattern.containsMatchIn(path),
                            vendorExtension = vendorPattern.containsMatchIn(path),
                        )
                    }.getOrElse { error ->
                        if (error is IllegalArgumentException && error.message == "sensitive_field_path") {
                            skippedSensitive += 1
                        } else {
                            skippedMalformed += 1
                        }
                        return@forEach
                    }
                    observations.putIfAbsent(observation.path, observation)
                }
            }

        return Result(
            observations = observations.values.toList(),
            skippedSensitive = skippedSensitive,
            skippedMalformed = skippedMalformed,
        )
    }

    private fun normalizePathToken(input: String): String {
        return input.trim()
            .replace(Regex("""[^A-Za-z0-9_.\-\[\]]+"""), ".")
            .trim('.')
            .take(120)
    }

    private fun inferType(raw: String): String {
        val value = raw.trim().trim('"', '\'')
        return when {
            value.startsWith("<redacted") || value.startsWith("<identifier") -> "redacted"
            booleanPattern.matches(value) -> "boolean"
            integerPattern.matches(value) -> "int"
            longPattern.matches(value) -> "long"
            floatPattern.matches(value) -> "float"
            macPattern.matches(value) -> "mac"
            ipv4Pattern.matches(value) -> "ipv4"
            value.startsWith("[") && value.endsWith("]") -> "list"
            value.startsWith("{") && value.endsWith("}") -> "object"
            hexPattern.matches(value) -> "hex"
            else -> "string"
        }
    }
}
