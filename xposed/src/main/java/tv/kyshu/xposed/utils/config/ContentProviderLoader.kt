package tv.kyshu.xposed.utils.config

import android.content.Context
import android.net.Uri
import tv.kyshu.xposed.utils.Logger

class ContentProviderLoader(private val context: Context?) : ConfigLoader {

    override val name: String = "ContentProvider"

    companion object {
        const val AUTHORITY = "tv.kyshu.xposed.config"
        const val PATH_CONFIG = "config"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_CONFIG")
        private const val LOG_INTERVAL_MS = 60_000L
        private val SHAPE_KEYS = listOf(
            "enable",
            "enable_mock_gnss",
            "rinex_path",
            "hook_sensor",
            "enable_step_count_mock",
            "enable_motion_mock",
            "step_count",
            "enable_shake_mock",
            "keep_walking_waveform",
            "gait_params_json",
        )
        @Volatile private var lastErrorAt = 0L
        @Volatile private var lastEmptyAt = 0L
        @Volatile private var lastNullContextAt = 0L
        @Volatile private var lastShapeAt = 0L
        @Volatile private var lastShape = ""
    }

    override fun load(): Map<String, Any?>? {
        val ctx = context ?: run {
            logThrottled("nullContext") {
                lastNullContextAt = it
                Logger.warn("ContentProviderLoader: skipped because context=null")
            }
            return null
        }
        
        val result = mutableMapOf<String, Any?>()
        try {
            val cursor = ctx.contentResolver.query(
                CONTENT_URI, 
                null, null, null, null
            )
            
            cursor?.use {
                val keyIndex = it.getColumnIndex("key")
                val valueIndex = it.getColumnIndex("value")
                val typeIndex = it.getColumnIndex("type")
                
                if (keyIndex != -1 && valueIndex != -1) {
                    while (it.moveToNext()) {
                        val key = it.getString(keyIndex)?.takeIf { key -> key.isNotBlank() } ?: continue
                        val valueStr = it.getString(valueIndex)
                        val type = if (typeIndex != -1) it.getString(typeIndex) else "string"
                        
                        val value = parseValue(key, valueStr, type)
                        result[key] = value
                    }
                }
            }
            
            if (result.isNotEmpty()) {
                logShape(ctx, result)
                return result
            }
            logThrottled("empty") {
                lastEmptyAt = it
                Logger.warn("ContentProviderLoader: provider returned no config rows; context=${ctx.javaClass.name} package=${safePackageName(ctx)}")
            }
        } catch (e: Exception) {
            logThrottled("error") {
                lastErrorAt = it
                Logger.error("ContentProviderLoader: Failed to load provider config; this source is ignored for this reload", e)
            }
        }
        return null
    }

    private fun logShape(context: Context, result: Map<String, Any?>) {
        val shape = SHAPE_KEYS
            .filter { result.containsKey(it) }
            .joinToString(prefix = "fields=[", postfix = "]") { key -> "$key=${formatShapeValue(key, result[key])}" }
            .ifEmpty { "fields=[]" }
        val status = "package=${safePackageName(context)} size=${result.size} $shape"
        val now = System.currentTimeMillis()
        if (status != lastShape || now - lastShapeAt > LOG_INTERVAL_MS) {
            lastShape = status
            lastShapeAt = now
            Logger.info("ContentProviderLoader: loaded config shape: $status")
        }
    }

    private fun logThrottled(kind: String, block: (Long) -> Unit) {
        val now = System.currentTimeMillis()
        val last = when (kind) {
            "nullContext" -> lastNullContextAt
            "empty" -> lastEmptyAt
            else -> lastErrorAt
        }
        if (now - last > LOG_INTERVAL_MS) {
            block(now)
        }
    }

    private fun safePackageName(context: Context): String {
        return runCatching { context.packageName }.getOrDefault("?")
    }

    private fun parseValue(key: String, value: String?, type: String?): Any? {
        val normalizedType = type?.trim()?.lowercase().orEmpty()
        val raw = value?.trim()
        return when (normalizedType) {
            "boolean" -> when (raw?.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw invalidValue(key, normalizedType, value)
            }
            "int" -> raw?.toIntOrNull() ?: throw invalidValue(key, normalizedType, value)
            "float" -> raw?.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw invalidValue(key, normalizedType, value)
            "double" -> raw?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw invalidValue(key, normalizedType, value)
            "long" -> raw?.toLongOrNull() ?: throw invalidValue(key, normalizedType, value)
            "string", "" -> value.orEmpty()
            else -> throw IllegalArgumentException("Unknown provider config type: key=$key type=$type value=${value?.take(128)}")
        }
    }

    private fun invalidValue(key: String, type: String, value: String?): IllegalArgumentException {
        return IllegalArgumentException("Invalid provider config value: key=$key type=$type value=${value?.take(128) ?: "null"}")
    }

    private fun formatShapeValue(key: String, value: Any?): String {
        return if (key == "gait_params_json") {
            "len=${value?.toString()?.length ?: 0}"
        } else {
            value.toString()
        }
    }
}
