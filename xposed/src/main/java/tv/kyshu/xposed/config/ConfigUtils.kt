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

/**
 * L1 FIX: Shared type-safe Map value getter.
 * Previously duplicated in 5 config files (WifiData, SensorData, LocationData,
 * MockState, GaitData). Extracted here to eliminate copy-paste maintenance risk.
 */
@PublishedApi
internal inline fun <reified T> Map<String, Any?>.getVal(key: String, default: T): T {
    val value = this[key] ?: return default
    return try {
        when (T::class) {
            Boolean::class -> {
                if (value is Boolean) value as T
                else (value.toString() == "true") as T
            }
            Int::class -> {
                if (value is Number) value.toInt() as T
                else value.toString().toIntOrNull() as? T ?: default
            }
            Long::class -> {
                if (value is Number) value.toLong() as T
                else value.toString().toLongOrNull() as? T ?: default
            }
            Float::class -> {
                if (value is Number) value.toFloat() as T
                else value.toString().toFloatOrNull() as? T ?: default
            }
            String::class -> value.toString() as T
            else -> default
        }
    } catch (_: Exception) {
        default
    }
}
