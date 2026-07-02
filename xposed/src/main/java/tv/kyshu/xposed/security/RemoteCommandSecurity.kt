package tv.kyshu.xposed.security

import android.os.Bundle
import android.os.IBinder
import org.json.JSONArray
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object RemoteCommandSecurity {
    const val FIELD_ENCRYPTED_SESSION_KEY = "_encrypted_key"
    const val FIELD_SESSION_KEY_IV = "_key_iv"
    const val FIELD_AUTH_NONCE = "_kys_auth_nonce"
    const val FIELD_AUTH_TIMESTAMP_MS = "_kys_auth_timestamp_ms"
    const val FIELD_AUTH_SEQUENCE = "_kys_auth_sequence"
    const val FIELD_AUTH_SIGNATURE = "_kys_auth_signature"
    const val DEFAULT_REPLAY_WINDOW_MS = 30_000L
    private const val DEFAULT_REPLAY_CACHE_ENTRIES = 2048
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val AES_ALGORITHM = "AES"
    private const val AES_TRANSFORMATION = "AES/CTR/NoPadding"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TRANSPORT_KEY_MATERIAL = "portal_transport_v1"
    private const val FIELD_ENCRYPTED_PREFIX = "_enc_"
    private const val FIELD_ENCRYPTED_IV_PREFIX = "_enc_iv_"
    private const val FIELD_ENCRYPTED_TYPE_PREFIX = "_enc_type_"
    private val authFields = setOf(
        FIELD_AUTH_NONCE,
        FIELD_AUTH_TIMESTAMP_MS,
        FIELD_AUTH_SEQUENCE,
        FIELD_AUTH_SIGNATURE,
    )
    private val sensitivePayloadKeys = setOf(
        "latitude",
        "longitude",
        "lat",
        "lon",
        "altitude",
        "speed",
        "accuracy",
        "bearing",
        "n",
        "speed_amplitude",
        "min_speed",
        "max_speed",
        "mock_ssid",
        "mock_bssid",
        "mock_mac",
        "mock_ip",
        "gait_freq",
        "gait_amp_scale",
        "gait_params_json",
        "ssids",
        "bssids",
        "rssis",
        "freqs",
        "caps",
        "standards",
        "widths",
    )
    private val sensitiveStringListKeys = setOf("ssids", "bssids", "caps")
    private val sensitiveIntListKeys = setOf("rssis", "freqs", "standards", "widths")
    private val secureRandom = SecureRandom()

    fun newSessionKey(): String {
        return "auth_" + randomHex(byteCount = 32)
    }

    fun putEncryptedSessionKey(bundle: Bundle, sessionKey: String): Boolean {
        if (sessionKey.isBlank()) return false
        return kotlin.runCatching {
            val iv = ByteArray(12)
            synchronized(secureRandom) {
                secureRandom.nextBytes(iv)
            }
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(transportKey(), AES_ALGORITHM), GCMParameterSpec(128, iv))
            bundle.putByteArray(FIELD_ENCRYPTED_SESSION_KEY, cipher.doFinal(sessionKey.toByteArray(Charsets.UTF_8)))
            bundle.putByteArray(FIELD_SESSION_KEY_IV, iv)
            true
        }.getOrDefault(false)
    }

    fun decryptSessionKey(bundle: Bundle): String? {
        val encrypted = bundle.getByteArray(FIELD_ENCRYPTED_SESSION_KEY) ?: return null
        val iv = bundle.getByteArray(FIELD_SESSION_KEY_IV) ?: return null
        return kotlin.runCatching {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(transportKey(), AES_ALGORITHM), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun attachEnvelope(
        bundle: Bundle,
        sessionKey: String,
        sequence: Long,
        nowMs: Long = System.currentTimeMillis(),
        nonce: String = newNonce(),
    ): Boolean {
        val commandId = bundle.getString("command_id") ?: return false
        if (sessionKey.isBlank() || sequence <= 0L || !isSafeNonce(nonce) || nowMs <= 0L) {
            return false
        }
        if (!encryptSensitivePayload(bundle, sessionKey)) {
            return false
        }
        bundle.putString(FIELD_AUTH_NONCE, nonce)
        bundle.putLong(FIELD_AUTH_TIMESTAMP_MS, nowMs)
        bundle.putLong(FIELD_AUTH_SEQUENCE, sequence)
        val payloadDigest = payloadDigest(bundle)
        bundle.putString(
            FIELD_AUTH_SIGNATURE,
            signCommand(
                sessionKey = sessionKey,
                commandId = commandId,
                nonce = nonce,
                timestampMs = nowMs,
                sequence = sequence,
                payloadDigest = payloadDigest,
            ),
        )
        return true
    }

    fun decryptSensitivePayload(bundle: Bundle, sessionKey: String): Boolean {
        if (sessionKey.isBlank()) return false
        return kotlin.runCatching {
            for (key in sensitivePayloadKeys) {
                val encryptedKey = encryptedField(key)
                if (!bundle.containsKey(encryptedKey)) continue
                if (bundle.containsKey(key)) return false

                val type = bundle.getString(encryptedTypeField(key)) ?: return false
                val iv = bundle.getByteArray(encryptedIvField(key)) ?: return false
                val encrypted = bundle.getByteArray(encryptedKey) ?: return false
                val plaintext = decryptValue(sessionKey, iv, encrypted)
                putTypedValue(bundle, key, type, plaintext)
                bundle.remove(encryptedKey)
                bundle.remove(encryptedIvField(key))
                bundle.remove(encryptedTypeField(key))
            }
            true
        }.getOrDefault(false)
    }

    fun newNonce(): String {
        return randomHex(byteCount = 16)
    }

    fun encryptSensitivePayload(bundle: Bundle, sessionKey: String): Boolean {
        return kotlin.runCatching {
            for (key in sensitivePayloadKeys) {
                if (!bundle.containsKey(key)) continue
                val value = bundle.get(key) ?: continue
                val typed = typedString(key, value) ?: continue
                val iv = ByteArray(16)
                synchronized(secureRandom) {
                    secureRandom.nextBytes(iv)
                }
                val encrypted = encryptValue(sessionKey, iv, typed.value)
                bundle.putString(encryptedTypeField(key), typed.type)
                bundle.putByteArray(encryptedIvField(key), iv)
                bundle.putByteArray(encryptedField(key), encrypted)
                bundle.remove(key)
            }
            true
        }.getOrDefault(false)
    }

    private data class TypedString(val type: String, val value: String)

    private fun typedString(key: String, value: Any): TypedString? {
        return when (value) {
            is String -> TypedString("string", value)
            is Double -> TypedString("double", value.toString())
            is Float -> TypedString("float", value.toString())
            is Int -> TypedString("int", value.toString())
            is Long -> TypedString("long", value.toString())
            is Boolean -> TypedString("boolean", value.toString())
            is ArrayList<*> -> typedArrayList(key, value)
            else -> null
        }
    }

    private fun typedArrayList(key: String, value: ArrayList<*>): TypedString? {
        return when {
            key in sensitiveStringListKeys && value.all { it is String } -> {
                val array = JSONArray()
                value.forEach { array.put(it as String) }
                TypedString("string_array_list", array.toString())
            }
            key in sensitiveIntListKeys && value.all { it is Int } -> {
                val array = JSONArray()
                value.forEach { array.put(it as Int) }
                TypedString("int_array_list", array.toString())
            }
            else -> null
        }
    }

    private fun putTypedValue(bundle: Bundle, key: String, type: String, value: String) {
        when (type) {
            "string" -> bundle.putString(key, value)
            "double" -> bundle.putDouble(key, value.toDouble())
            "float" -> bundle.putFloat(key, value.toFloat())
            "int" -> bundle.putInt(key, value.toInt())
            "long" -> bundle.putLong(key, value.toLong())
            "boolean" -> bundle.putBoolean(key, value.toBoolean())
            "string_array_list" -> bundle.putStringArrayList(key, parseStringArrayList(value))
            "int_array_list" -> bundle.putIntegerArrayList(key, parseIntArrayList(value))
            else -> throw IllegalArgumentException("Unsupported encrypted payload type: $type")
        }
    }

    private fun parseStringArrayList(value: String): ArrayList<String> {
        val array = JSONArray(value)
        return ArrayList<String>(array.length()).apply {
            for (index in 0 until array.length()) {
                add(array.getString(index))
            }
        }
    }

    private fun parseIntArrayList(value: String): ArrayList<Int> {
        val array = JSONArray(value)
        return ArrayList<Int>(array.length()).apply {
            for (index in 0 until array.length()) {
                add(array.getInt(index))
            }
        }
    }

    private fun encryptValue(sessionKey: String, iv: ByteArray, value: String): ByteArray {
        return cipher(sessionKey, iv, Cipher.ENCRYPT_MODE).doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun decryptValue(sessionKey: String, iv: ByteArray, encrypted: ByteArray): String {
        return cipher(sessionKey, iv, Cipher.DECRYPT_MODE).doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun cipher(sessionKey: String, iv: ByteArray, mode: Int): Cipher {
        val key = MessageDigest.getInstance("SHA-256").digest(sessionKey.toByteArray(Charsets.UTF_8))
        return Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, AES_ALGORITHM), IvParameterSpec(iv))
        }
    }

    private fun encryptedField(key: String): String = "$FIELD_ENCRYPTED_PREFIX$key"

    private fun encryptedIvField(key: String): String = "$FIELD_ENCRYPTED_IV_PREFIX$key"

    private fun encryptedTypeField(key: String): String = "$FIELD_ENCRYPTED_TYPE_PREFIX$key"

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        synchronized(secureRandom) {
            secureRandom.nextBytes(bytes)
        }
        return bytes.toHex()
    }

    private fun transportKey(): ByteArray {
        return MessageDigest.getInstance("SHA-256")
            .digest(TRANSPORT_KEY_MATERIAL.toByteArray(Charsets.UTF_8))
    }

    private fun signCommand(
        sessionKey: String,
        commandId: String,
        nonce: String,
        timestampMs: Long,
        sequence: Long,
        payloadDigest: String,
    ): String {
        val payload = buildString {
            append(commandId)
            append('\n')
            append(nonce)
            append('\n')
            append(timestampMs)
            append('\n')
            append(sequence)
            append('\n')
            append(payloadDigest)
        }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val derivedKey = MessageDigest.getInstance("SHA-256").digest(sessionKey.toByteArray(Charsets.UTF_8))
        mac.init(SecretKeySpec(derivedKey, HMAC_ALGORITHM))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun payloadDigest(bundle: Bundle): String {
        val canonicalPayload = bundle.keySet()
            .filterNot { it in authFields }
            .sorted()
            .joinToString(separator = "\n") { key ->
                "$key=${canonicalValue(bundle.get(key))}"
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonicalPayload.toByteArray(Charsets.UTF_8))
            .toHex()
    }

    private fun canonicalValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "String:${value.length}:$value"
            is Boolean -> "Boolean:$value"
            is Int -> "Int:$value"
            is Long -> "Long:$value"
            is Float -> "Float:$value"
            is Double -> "Double:$value"
            is IBinder -> "IBinder"
            is Bundle -> "Bundle:${payloadDigest(value)}"
            is IntArray -> value.joinToString(prefix = "IntArray:[", postfix = "]")
            is LongArray -> value.joinToString(prefix = "LongArray:[", postfix = "]")
            is FloatArray -> value.joinToString(prefix = "FloatArray:[", postfix = "]")
            is DoubleArray -> value.joinToString(prefix = "DoubleArray:[", postfix = "]")
            is BooleanArray -> value.joinToString(prefix = "BooleanArray:[", postfix = "]")
            is ByteArray -> "ByteArray:${value.toHex()}"
            is Array<*> -> value.joinToString(prefix = "Array:[", postfix = "]") { canonicalValue(it) }
            is Iterable<*> -> value.joinToString(prefix = "Iterable:[", postfix = "]") { canonicalValue(it) }
            else -> "${value.javaClass.name}:$value"
        }
    }

    private fun isSafeNonce(nonce: String): Boolean {
        return nonce.length in 16..128 && nonce.all {
            it in 'a'..'z' ||
                it in 'A'..'Z' ||
                it in '0'..'9' ||
                it == '-' ||
                it == '_'
        }
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun constantTimeEquals(left: String?, right: String): Boolean {
        if (left == null) return false
        val leftBytes = left.toByteArray(Charsets.UTF_8)
        val rightBytes = right.toByteArray(Charsets.UTF_8)
        var diff = leftBytes.size xor rightBytes.size
        val max = maxOf(leftBytes.size, rightBytes.size)
        for (index in 0 until max) {
            val a = if (index < leftBytes.size) leftBytes[index].toInt() else 0
            val b = if (index < rightBytes.size) rightBytes[index].toInt() else 0
            diff = diff or (a xor b)
        }
        return diff == 0
    }

    class ReplayGuard(
        private val replayWindowMs: Long = DEFAULT_REPLAY_WINDOW_MS,
        private val maxEntries: Int = DEFAULT_REPLAY_CACHE_ENTRIES,
        private val nowProvider: () -> Long = { System.currentTimeMillis() },
    ) {
        private val seenNonces = LinkedHashMap<String, Long>()
        private val knownSessions = LinkedHashMap<String, Long>()

        @Synchronized
        fun registerSession(sessionKey: String): Boolean {
            if (sessionKey.isBlank()) return false
            val now = nowProvider()
            prune(now)
            knownSessions[sessionKey] = now
            while (knownSessions.size > maxEntries) {
                val iterator = knownSessions.entries.iterator()
                if (iterator.hasNext()) {
                    val evictedSession = iterator.next().key
                    iterator.remove()
                    seenNonces.keys.removeIf { it.startsWith("$evictedSession:") }
                }
            }
            return true
        }

        @Synchronized
        fun accept(sessionKey: String, bundle: Bundle): Boolean {
            if (sessionKey.isBlank()) return false
            if (!knownSessions.containsKey(sessionKey)) return false
            val commandId = bundle.getString("command_id") ?: return false
            val nonce = bundle.getString(FIELD_AUTH_NONCE) ?: return false
            if (!isSafeNonce(nonce)) return false
            if (!bundle.containsKey(FIELD_AUTH_TIMESTAMP_MS) || !bundle.containsKey(FIELD_AUTH_SEQUENCE)) {
                return false
            }
            val timestampMs = bundle.getLong(FIELD_AUTH_TIMESTAMP_MS)
            val sequence = bundle.getLong(FIELD_AUTH_SEQUENCE)
            if (timestampMs <= 0L || sequence <= 0L) return false

            val now = nowProvider()
            prune(now)
            if (!knownSessions.containsKey(sessionKey)) return false
            val ageMs = now - timestampMs
            if (ageMs < 0L || ageMs > replayWindowMs) return false

            val nonceKey = "$sessionKey:$nonce"
            if (seenNonces.containsKey(nonceKey)) return false

            val expectedSignature = signCommand(
                sessionKey = sessionKey,
                commandId = commandId,
                nonce = nonce,
                timestampMs = timestampMs,
                sequence = sequence,
                payloadDigest = payloadDigest(bundle),
            )
            if (!constantTimeEquals(bundle.getString(FIELD_AUTH_SIGNATURE), expectedSignature)) {
                return false
            }

            seenNonces[nonceKey] = timestampMs
            while (seenNonces.size > maxEntries) {
                val iterator = seenNonces.entries.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            return true
        }

        @Synchronized
        fun reset() {
            seenNonces.clear()
            knownSessions.clear()
        }

        private fun prune(nowMs: Long) {
            val iterator = seenNonces.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (nowMs - entry.value > replayWindowMs) {
                    iterator.remove()
                }
            }
        }
    }
}
