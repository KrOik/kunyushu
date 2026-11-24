package tv.kyshu.ui.settings

object SettingsInputValidators {
    private val macAddressRegex = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    fun isOptionalMacAddress(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isEmpty() || macAddressRegex.matches(trimmed)
    }

    fun isOptionalIpv4Address(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return true
        val parts = trimmed.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all { it in '0'..'9' } &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
