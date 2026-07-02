package tv.kyshu.xposed.security

import android.os.Bundle
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object RemoteCommandBundleCodec {
    private const val VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_VALUES = "values"
    private const val KEY_TYPE = "type"
    private const val KEY_VALUE = "value"

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
        val values = root.getJSONObject(KEY_VALUES)
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
                encoded.put(KEY_TYPE, "null")
                encoded.put(KEY_VALUE, JSONObject.NULL)
            }
            is String -> {
                encoded.put(KEY_TYPE, "string")
                encoded.put(KEY_VALUE, value)
            }
            is Boolean -> {
                encoded.put(KEY_TYPE, "boolean")
                encoded.put(KEY_VALUE, value)
            }
            is Int -> {
                encoded.put(KEY_TYPE, "int")
                encoded.put(KEY_VALUE, value)
            }
            is Long -> {
                encoded.put(KEY_TYPE, "long")
                encoded.put(KEY_VALUE, value)
            }
            is Float -> {
                encoded.put(KEY_TYPE, "float")
                encoded.put(KEY_VALUE, value.toDouble())
            }
            is Double -> {
                encoded.put(KEY_TYPE, "double")
                encoded.put(KEY_VALUE, value)
            }
            is ByteArray -> {
                encoded.put(KEY_TYPE, "byte_array")
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
                encoded.put(KEY_TYPE, "string_array_list")
                encoded.put(KEY_VALUE, JSONArray().apply {
                    value.forEach { put(it as String) }
                })
            }
            value.all { it is Int } -> {
                encoded.put(KEY_TYPE, "int_array_list")
                encoded.put(KEY_VALUE, JSONArray().apply {
                    value.forEach { put(it as Int) }
                })
            }
            else -> throw IllegalArgumentException("Unsupported Bundle ArrayList value")
        }
    }

    private fun Bundle.putTypedValue(key: String, encoded: JSONObject) {
        when (encoded.getString(KEY_TYPE)) {
            "null" -> putString(key, null)
            "string" -> putString(key, encoded.getString(KEY_VALUE))
            "boolean" -> putBoolean(key, encoded.getBoolean(KEY_VALUE))
            "int" -> putInt(key, encoded.getInt(KEY_VALUE))
            "long" -> putLong(key, encoded.getLong(KEY_VALUE))
            "float" -> putFloat(key, encoded.getDouble(KEY_VALUE).toFloat())
            "double" -> putDouble(key, encoded.getDouble(KEY_VALUE))
            "byte_array" -> putByteArray(
                key,
                Base64.decode(encoded.getString(KEY_VALUE), Base64.NO_WRAP),
            )
            "string_array_list" -> putStringArrayList(key, encoded.getJSONArray(KEY_VALUE).toStringArrayList())
            "int_array_list" -> putIntegerArrayList(key, encoded.getJSONArray(KEY_VALUE).toIntArrayList())
            else -> throw IllegalArgumentException("Unsupported encoded Bundle type: ${encoded.getString(KEY_TYPE)}")
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
