package tv.kyshu.xposed.utils.config

import android.content.Context
import android.net.Uri
import tv.kyshu.xposed.utils.FakeLoc
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
                        val key = it.getString(keyIndex)
                        val valueStr = it.getString(valueIndex)
                        val type = if (typeIndex != -1) it.getString(typeIndex) else "string"
                        
                        val value: Any? = when (type) {
                            "boolean" -> valueStr.toBoolean()
                            "int" -> valueStr.toIntOrNull()
                            "float" -> valueStr.toFloatOrNull()
                            "long" -> valueStr.toLongOrNull()
                            else -> valueStr
                        }
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
            if (now - lastErrorAt > 60_000L && FakeLoc.enableDebugLog) {
                lastErrorAt = now
                Logger.warn("ContentProviderLoader: Failed to query provider", e)
            }
        }
        return null
    }
}
