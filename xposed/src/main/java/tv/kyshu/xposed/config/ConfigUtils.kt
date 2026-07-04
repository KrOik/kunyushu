/*
 * Copyright (C) 2025 The KunYuShu Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tv.kyshu.xposed.config

@PublishedApi
internal class ConfigParseException(message: String) : IllegalArgumentException(message)

/**
 * L1 FIX: Shared type-safe Map value getter.
 * Previously duplicated in 5 config files (WifiData, SensorData, LocationData,
 * MockState, GaitData). Extracted here to eliminate copy-paste maintenance risk.
 */
@PublishedApi
internal inline fun <reified T> Map<String, Any?>.getVal(key: String, default: T): T {
    if (!containsKey(key)) return default
    return try {
        coerceConfigValue<T>(key, this[key])
    } catch (_: Exception) {
        default
    }
}

internal inline fun <reified T> Map<String, Any?>.getAnyVal(default: T, vararg keys: String): T {
    for (key in keys) {
        if (containsKey(key)) return getVal(key, default)
    }
    return default
}

internal inline fun <reified T> Map<String, Any?>.getStrictAnyVal(fieldName: String, vararg keys: String): T? {
    val key = keys.firstOrNull { containsKey(it) } ?: return null
    return coerceConfigValue(key, this[key], fieldName)
}

@PublishedApi
internal inline fun <reified T> coerceConfigValue(key: String, value: Any?, fieldName: String = key): T {
    if (value == null) {
        throw ConfigParseException("Missing value for config field '$fieldName' (key=$key)")
    }
    return when (T::class) {
        Boolean::class -> coerceBoolean(key, value, fieldName) as T
        Int::class -> coerceInt(key, value, fieldName) as T
        Long::class -> coerceLong(key, value, fieldName) as T
        Float::class -> coerceFloat(key, value, fieldName) as T
        Double::class -> coerceDouble(key, value, fieldName) as T
        String::class -> value.toString() as T
        else -> throw ConfigParseException("Unsupported config field '$fieldName' type=${T::class.simpleName} key=$key")
    }
}

@PublishedApi
internal fun coerceBoolean(key: String, value: Any, fieldName: String): Boolean {
    if (value is Boolean) return value
    return value.toString().trim().lowercase().let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> throw ConfigParseException(invalidConfigMessage(fieldName, key, "Boolean", value))
        }
    }
}

@PublishedApi
internal fun coerceInt(key: String, value: Any, fieldName: String): Int {
    if (value is Number) return value.toInt()
    return value.toString().trim().toIntOrNull()
        ?: throw ConfigParseException(invalidConfigMessage(fieldName, key, "Int", value))
}

@PublishedApi
internal fun coerceLong(key: String, value: Any, fieldName: String): Long {
    if (value is Number) return value.toLong()
    return value.toString().trim().toLongOrNull()
        ?: throw ConfigParseException(invalidConfigMessage(fieldName, key, "Long", value))
}

@PublishedApi
internal fun coerceFloat(key: String, value: Any, fieldName: String): Float {
    val parsed = if (value is Number) value.toFloat() else value.toString().trim().toFloatOrNull()
    if (parsed == null || !parsed.isFinite()) {
        throw ConfigParseException(invalidConfigMessage(fieldName, key, "finite Float", value))
    }
    return parsed
}

@PublishedApi
internal fun coerceDouble(key: String, value: Any, fieldName: String): Double {
    val parsed = if (value is Number) value.toDouble() else value.toString().trim().toDoubleOrNull()
    if (parsed == null || !parsed.isFinite()) {
        throw ConfigParseException(invalidConfigMessage(fieldName, key, "finite Double", value))
    }
    return parsed
}

@PublishedApi
internal fun invalidConfigMessage(fieldName: String, key: String, expected: String, value: Any?): String {
    val raw = value?.toString()?.take(128) ?: "null"
    val type = value?.javaClass?.name ?: "null"
    return "Invalid config field '$fieldName' (key=$key, expected=$expected, raw='$raw', type=$type)"
}
