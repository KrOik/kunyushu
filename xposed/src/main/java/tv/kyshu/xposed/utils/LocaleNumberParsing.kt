package tv.kyshu.xposed.utils

internal object LocaleNumberParsing {
    fun parseFiniteDouble(value: String): Double? {
        return value.trim().normalizeDecimalSeparator()?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    fun parseFiniteFloat(value: String): Float? {
        return value.trim().normalizeDecimalSeparator()?.toFloatOrNull()?.takeIf { it.isFinite() }
    }

    private fun String.normalizeDecimalSeparator(): String? {
        if (isEmpty()) return null
        if (contains('.')) return this
        return if (count { it == ',' } == 1) replace(',', '.') else this
    }
}
