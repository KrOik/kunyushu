package tv.kyshu.xposed.utils.config

import android.content.Context
import android.net.Uri
import tv.kyshu.xposed.utils.Logger

class ContentProviderLoader(private val context: Context?) : ConfigLoader {

    override val name: String = "ContentProvider"
    private var lastErrorAt = 0L

    companion object {
        const val AUTHORITY = "tv.kyshu.xposed.config"
        const val PATH_CONFIG = "config"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_CONFIG")
    }

    override fun load(): Map<String, Any?>? {
        if (context == null) return null
        
        val result = mutableMapOf<String, Any?>()
        try {
            val cursor = context.contentResolver.query(
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
                // Logger.debug("ContentProviderLoader: Loaded ${result.size} items.")
                return result
            }
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastErrorAt > 60_000L) {
                lastErrorAt = now
                Logger.error("ContentProviderLoader: Failed to load provider config; this source is ignored for this reload", e)
            }
        }
        return null
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
}
