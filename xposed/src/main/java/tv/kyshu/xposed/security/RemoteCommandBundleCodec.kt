package tv.kyshu.xposed.security

import android.os.Bundle
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object RemoteCommandBundleCodec {
    private const val VERSION = 1
    private const val KEY_VERSION = "v"
    private const val KEY_VALUES = "d"
    private const val KEY_TYPE = "t"
    private const val KEY_VALUE = "x"
    private const val LEGACY_KEY_VALUES = "values"
    private const val LEGACY_KEY_TYPE = "type"
    private const val LEGACY_KEY_VALUE = "value"

    fun encode(bundle: Bundle): String {
        val values = JSONObject()
        for (key in bundle.keySet().sorted()) {
            values.put(key, encodeValue(bundle.get(key)))
        }
        return JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_VALUES, values)
            .toString()
    }

    fun decode(json: String): Bundle {
        val root = JSONObject(json)
        val values = root.optJSONObject(KEY_VALUES) ?: root.getJSONObject(LEGACY_KEY_VALUES)
        return Bundle().apply {
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                putTypedValue(key, values.getJSONObject(key))
            }
        }
    }

    private fun encodeValue(value: Any?): JSONObject {
        val encoded = JSONObject()
        when (value) {
            null -> {
                encoded.put(KEY_TYPE, "n")
                encoded.put(KEY_VALUE, JSONObject.NULL)
            }
            is String -> {
                encoded.put(KEY_TYPE, "s")
                encoded.put(KEY_VALUE, value)
            }
            is Boolean -> {
                encoded.put(KEY_TYPE, "b")
                encoded.put(KEY_VALUE, value)
            }
            is Int -> {
                encoded.put(KEY_TYPE, "i")
                encoded.put(KEY_VALUE, value)
            }
            is Long -> {
                encoded.put(KEY_TYPE, "l")
                encoded.put(KEY_VALUE, value)
            }
            is Float -> {
                encoded.put(KEY_TYPE, "f")
                encoded.put(KEY_VALUE, value.toDouble())
            }
            is Double -> {
                encoded.put(KEY_TYPE, "d")
                encoded.put(KEY_VALUE, value)
            }
            is ByteArray -> {
                encoded.put(KEY_TYPE, "ba")
                encoded.put(KEY_VALUE, Base64.encodeToString(value, Base64.NO_WRAP))
            }
            is ArrayList<*> -> encodeArrayList(encoded, value)
            else -> throw IllegalArgumentException("Unsupported Bundle value type: ${value.javaClass.name}")
        }
        return encoded
    }

    private fun encodeArrayList(encoded: JSONObject, value: ArrayList<*>) {
        when {
            value.all { it is String } -> {
                encoded.put(KEY_TYPE, "sl")
                encoded.put(KEY_VALUE, JSONArray().apply {
                    value.forEach { put(it as String) }
                })
            }
            value.all { it is Int } -> {
                encoded.put(KEY_TYPE, "il")
                encoded.put(KEY_VALUE, JSONArray().apply {
                    value.forEach { put(it as Int) }
                })
            }
            else -> throw IllegalArgumentException("Unsupported Bundle ArrayList value")
        }
    }

    private fun Bundle.putTypedValue(key: String, encoded: JSONObject) {
        val typeKey = if (encoded.has(KEY_TYPE)) KEY_TYPE else LEGACY_KEY_TYPE
        val valueKey = if (encoded.has(KEY_VALUE)) KEY_VALUE else LEGACY_KEY_VALUE
        when (encoded.getString(typeKey)) {
            "n", "null" -> putString(key, null)
            "s", "string" -> putString(key, encoded.getString(valueKey))
            "b", "boolean" -> putBoolean(key, encoded.getBoolean(valueKey))
            "i", "int" -> putInt(key, encoded.getInt(valueKey))
            "l", "long" -> putLong(key, encoded.getLong(valueKey))
            "f", "float" -> putFloat(key, encoded.getDouble(valueKey).toFloat())
            "d", "double" -> putDouble(key, encoded.getDouble(valueKey))
            "ba", "byte_array" -> putByteArray(
                key,
                Base64.decode(encoded.getString(valueKey), Base64.NO_WRAP),
            )
            "sl", "string_array_list" -> putStringArrayList(key, encoded.getJSONArray(valueKey).toStringArrayList())
            "il", "int_array_list" -> putIntegerArrayList(key, encoded.getJSONArray(valueKey).toIntArrayList())
            else -> throw IllegalArgumentException("Unsupported encoded Bundle type: ${encoded.getString(typeKey)}")
        }
    }

    private fun JSONArray.toStringArrayList(): ArrayList<String> {
        return ArrayList<String>(length()).apply {
            for (index in 0 until length()) {
                add(getString(index))
            }
        }
    }

    private fun JSONArray.toIntArrayList(): ArrayList<Int> {
        return ArrayList<Int>(length()).apply {
            for (index in 0 until length()) {
                add(getInt(index))
            }
        }
    }
}
