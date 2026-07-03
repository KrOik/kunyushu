package tv.kyshu.xposed.security

import android.os.Build
import android.os.Bundle
import android.os.IBinder
import org.json.JSONArray
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object RemoteCommandSecurity {
    const val FIELD_ENCRYPTED_SESSION_KEY = "ek"
    const val FIELD_SESSION_KEY_IV = "ei"
    const val FIELD_AUTH_NONCE = "a0"
    const val FIELD_AUTH_TIMESTAMP_MS = "a1"
    const val FIELD_AUTH_SEQUENCE = "a2"
    const val FIELD_AUTH_SIGNATURE = "a3"
    const val DEFAULT_REPLAY_WINDOW_MS = 30_000L
    private const val DEFAULT_REPLAY_CACHE_ENTRIES = 2048
    private val LEGACY_FIELD_ENCRYPTED_SESSION_KEY = ascii(0x5f, 0x65, 0x6e, 0x63, 0x72, 0x79, 0x70, 0x74, 0x65, 0x64, 0x5f, 0x6b, 0x65, 0x79)
    private val LEGACY_FIELD_SESSION_KEY_IV = ascii(0x5f, 0x6b, 0x65, 0x79, 0x5f, 0x69, 0x76)
    private val LEGACY_FIELD_AUTH_NONCE = ascii(0x5f, 0x6b, 0x79, 0x73, 0x5f, 0x61, 0x75, 0x74, 0x68, 0x5f, 0x6e, 0x6f, 0x6e, 0x63, 0x65)
    private val LEGACY_FIELD_AUTH_TIMESTAMP_MS = ascii(0x5f, 0x6b, 0x79, 0x73, 0x5f, 0x61, 0x75, 0x74, 0x68, 0x5f, 0x74, 0x69, 0x6d, 0x65, 0x73, 0x74, 0x61, 0x6d, 0x70, 0x5f, 0x6d, 0x73)
    private val LEGACY_FIELD_AUTH_SEQUENCE = ascii(0x5f, 0x6b, 0x79, 0x73, 0x5f, 0x61, 0x75, 0x74, 0x68, 0x5f, 0x73, 0x65, 0x71, 0x75, 0x65, 0x6e, 0x63, 0x65)
    private val LEGACY_FIELD_AUTH_SIGNATURE = ascii(0x5f, 0x6b, 0x79, 0x73, 0x5f, 0x61, 0x75, 0x74, 0x68, 0x5f, 0x73, 0x69, 0x67, 0x6e, 0x61, 0x74, 0x75, 0x72, 0x65)
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val AES_ALGORITHM = "AES"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AES_GCM_IV_BYTES = 12
    private const val AES_GCM_TAG_BITS = 128
    private val TRANSPORT_KEY_MATERIAL = byteArrayOf(0x73, 0x76, 0x63, 0x2d, 0x74, 0x78, 0x2d, 0x32)
    private val TRANSPORT_FALLBACK_SALT = byteArrayOf(0x66, 0x70, 0x2d, 0x66, 0x61, 0x6c, 0x6c, 0x62, 0x61, 0x63, 0x6b, 0x2d, 0x32)
    private const val FIELD_ENCRYPTED_PREFIX = "p_"
    private const val FIELD_ENCRYPTED_IV_PREFIX = "piv_"
    private const val FIELD_ENCRYPTED_TYPE_PREFIX = "pt_"
    private val LEGACY_FIELD_ENCRYPTED_PREFIX = ascii(0x5f, 0x65, 0x6e, 0x63, 0x5f)
    private val LEGACY_FIELD_ENCRYPTED_IV_PREFIX = ascii(0x5f, 0x65, 0x6e, 0x63, 0x5f, 0x69, 0x76, 0x5f)
    private val LEGACY_FIELD_ENCRYPTED_TYPE_PREFIX = ascii(0x5f, 0x65, 0x6e, 0x63, 0x5f, 0x74, 0x79, 0x70, 0x65, 0x5f)
    private val authFields = setOf(
        FIELD_AUTH_NONCE,
        FIELD_AUTH_TIMESTAMP_MS,
        FIELD_AUTH_SEQUENCE,
        FIELD_AUTH_SIGNATURE,
        LEGACY_FIELD_AUTH_NONCE,
        LEGACY_FIELD_AUTH_TIMESTAMP_MS,
        LEGACY_FIELD_AUTH_SEQUENCE,
        LEGACY_FIELD_AUTH_SIGNATURE,
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

    private fun ascii(vararg bytes: Int): String {
        return ByteArray(bytes.size) { index -> bytes[index].toByte() }.toString(Charsets.UTF_8)
    }

    fun newSessionKey(): String {
        return "auth_" + randomHex(byteCount = 32)
    }

    fun putEncryptedSessionKey(bundle: Bundle, sessionKey: String): Boolean {
        if (sessionKey.isBlank()) return false
        return kotlin.runCatching {
            val iv = ByteArray(AES_GCM_IV_BYTES)
            synchronized(secureRandom) {
                secureRandom.nextBytes(iv)
            }
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(transportKey(), AES_ALGORITHM), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
            bundle.putByteArray(FIELD_ENCRYPTED_SESSION_KEY, cipher.doFinal(sessionKey.toByteArray(Charsets.UTF_8)))
            bundle.putByteArray(FIELD_SESSION_KEY_IV, iv)
            true
        }.getOrDefault(false)
    }

    fun decryptSessionKey(bundle: Bundle): String? {
        val encrypted = bundle.getByteArrayAny(FIELD_ENCRYPTED_SESSION_KEY, LEGACY_FIELD_ENCRYPTED_SESSION_KEY) ?: return null
        val iv = bundle.getByteArrayAny(FIELD_SESSION_KEY_IV, LEGACY_FIELD_SESSION_KEY_IV) ?: return null
        fun decryptWith(key: ByteArray): String? = kotlin.runCatching {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            require(iv.size == AES_GCM_IV_BYTES) { "Invalid AES-GCM IV length" }
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrNull()
        return decryptWith(transportKey()) ?: decryptWith(transportKey(legacy = true))
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
            val decryptedFields = ArrayList<DecryptedField>()
            for (key in sensitivePayloadKeys) {
                val encryptedKey = encryptedFields(key).firstOrNull { bundle.containsKey(it) } ?: continue
                if (!bundle.containsKey(encryptedKey)) continue
                if (bundle.containsKey(key)) return false

                val typeKey = encryptedTypeFields(key).firstOrNull { bundle.containsKey(it) } ?: return false
                val ivKey = encryptedIvFields(key).firstOrNull { bundle.containsKey(it) } ?: return false
                val type = bundle.getString(typeKey) ?: return false
                val iv = bundle.getByteArray(ivKey) ?: return false
                val encrypted = bundle.getByteArray(encryptedKey) ?: return false
                val plaintext = decryptValue(sessionKey, iv, encrypted)
                decryptedFields += DecryptedField(
                    key = key,
                    encryptedKey = encryptedKey,
                    ivKey = ivKey,
                    typeKey = typeKey,
                    value = parseTypedValue(type, plaintext),
                )
            }
            for (field in decryptedFields) {
                putTypedValue(bundle, field.key, field.value)
                encryptedFields(field.key).forEach { bundle.remove(it) }
                encryptedIvFields(field.key).forEach { bundle.remove(it) }
                encryptedTypeFields(field.key).forEach { bundle.remove(it) }
            }
            true
        }.getOrDefault(false)
    }

    fun newNonce(): String {
        return randomHex(byteCount = 16)
    }

    fun encryptSensitivePayload(bundle: Bundle, sessionKey: String): Boolean {
        if (sessionKey.isBlank()) return false
        return kotlin.runCatching {
            for (key in sensitivePayloadKeys) {
                if (!bundle.containsKey(key)) continue
                val value = bundle.get(key) ?: continue
                val typed = typedString(key, value) ?: continue
                val iv = ByteArray(AES_GCM_IV_BYTES)
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
    private data class TypedBundleValue(val type: String, val value: Any)
    private data class DecryptedField(
        val key: String,
        val encryptedKey: String,
        val ivKey: String,
        val typeKey: String,
        val value: TypedBundleValue,
    )

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

    private fun parseTypedValue(type: String, value: String): TypedBundleValue {
        return when (type) {
            "string" -> TypedBundleValue(type, value)
            "double" -> TypedBundleValue(type, value.toDouble())
            "float" -> TypedBundleValue(type, value.toFloat())
            "int" -> TypedBundleValue(type, value.toInt())
            "long" -> TypedBundleValue(type, value.toLong())
            "boolean" -> TypedBundleValue(type, value.toBoolean())
            "string_array_list" -> TypedBundleValue(type, parseStringArrayList(value))
            "int_array_list" -> TypedBundleValue(type, parseIntArrayList(value))
            else -> throw IllegalArgumentException("Unsupported encrypted payload type: $type")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun putTypedValue(bundle: Bundle, key: String, typed: TypedBundleValue) {
        when (typed.type) {
            "string" -> bundle.putString(key, typed.value as String)
            "double" -> bundle.putDouble(key, typed.value as Double)
            "float" -> bundle.putFloat(key, typed.value as Float)
            "int" -> bundle.putInt(key, typed.value as Int)
            "long" -> bundle.putLong(key, typed.value as Long)
            "boolean" -> bundle.putBoolean(key, typed.value as Boolean)
            "string_array_list" -> bundle.putStringArrayList(key, typed.value as ArrayList<String>)
            "int_array_list" -> bundle.putIntegerArrayList(key, typed.value as ArrayList<Int>)
            else -> throw IllegalArgumentException("Unsupported encrypted payload type: ${typed.type}")
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
        require(iv.size == AES_GCM_IV_BYTES) { "Invalid AES-GCM IV length" }
        val key = MessageDigest.getInstance("SHA-256").digest(sessionKey.toByteArray(Charsets.UTF_8))
        return Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        }
    }

    private fun encryptedField(key: String): String = "$FIELD_ENCRYPTED_PREFIX$key"

    private fun legacyEncryptedField(key: String): String = "$LEGACY_FIELD_ENCRYPTED_PREFIX$key"

    private fun encryptedFields(key: String): Array<String> = arrayOf(encryptedField(key), legacyEncryptedField(key))

    private fun encryptedIvField(key: String): String = "$FIELD_ENCRYPTED_IV_PREFIX$key"

    private fun legacyEncryptedIvField(key: String): String = "$LEGACY_FIELD_ENCRYPTED_IV_PREFIX$key"

    private fun encryptedIvFields(key: String): Array<String> = arrayOf(encryptedIvField(key), legacyEncryptedIvField(key))

    private fun encryptedTypeField(key: String): String = "$FIELD_ENCRYPTED_TYPE_PREFIX$key"

    private fun legacyEncryptedTypeField(key: String): String = "$LEGACY_FIELD_ENCRYPTED_TYPE_PREFIX$key"

    private fun encryptedTypeFields(key: String): Array<String> = arrayOf(encryptedTypeField(key), legacyEncryptedTypeField(key))

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        synchronized(secureRandom) {
            secureRandom.nextBytes(bytes)
        }
        return bytes.toHex()
    }

    private fun transportKey(legacy: Boolean = false): ByteArray {
        val salt = deviceFingerprintSalt(legacy)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(if (legacy) legacyTransportKeyMaterial() else TRANSPORT_KEY_MATERIAL)
        digest.update(0)
        digest.update(salt)
        return digest.digest()
    }

    private fun legacyTransportKeyMaterial(): ByteArray = byteArrayOf(
        0x70, 0x6f, 0x72, 0x74, 0x61, 0x6c, 0x5f, 0x74, 0x72, 0x61,
        0x6e, 0x73, 0x70, 0x6f, 0x72, 0x74, 0x5f, 0x76, 0x31,
    )

    private fun legacyFallbackSalt(): ByteArray = byteArrayOf(
        0x70, 0x6f, 0x72, 0x74, 0x61, 0x6c, 0x5f, 0x74, 0x72, 0x61,
        0x6e, 0x73, 0x70, 0x6f, 0x72, 0x74, 0x5f, 0x66, 0x61, 0x6c,
        0x6c, 0x62, 0x61, 0x63, 0x6b, 0x5f, 0x73, 0x61, 0x6c, 0x74,
        0x5f, 0x76, 0x31,
    )

    private fun deviceFingerprintSalt(legacy: Boolean = false): ByteArray {
        val fingerprint = Build.FINGERPRINT.orEmpty().ifBlank {
            runCatching {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                    .invoke(null, "ro.build.fingerprint", "") as String
            }.getOrDefault("")
        }
        val saltSource = if (fingerprint.isNotBlank()) {
            fingerprint
        } else {
            // Fallback for environments where fingerprint is unavailable
            // (e.g., very early boot in zygote, constrained ROMs)
            (if (legacy) legacyFallbackSalt() else TRANSPORT_FALLBACK_SALT).toString(Charsets.UTF_8)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(saltSource.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 16)
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
            val nonce = bundle.getStringAny(FIELD_AUTH_NONCE, LEGACY_FIELD_AUTH_NONCE) ?: return false
            if (!isSafeNonce(nonce)) return false
            if (!bundle.containsAny(FIELD_AUTH_TIMESTAMP_MS, LEGACY_FIELD_AUTH_TIMESTAMP_MS) ||
                !bundle.containsAny(FIELD_AUTH_SEQUENCE, LEGACY_FIELD_AUTH_SEQUENCE)) {
                return false
            }
            val timestampMs = bundle.getLongAny(FIELD_AUTH_TIMESTAMP_MS, LEGACY_FIELD_AUTH_TIMESTAMP_MS)
            val sequence = bundle.getLongAny(FIELD_AUTH_SEQUENCE, LEGACY_FIELD_AUTH_SEQUENCE)
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
            if (!constantTimeEquals(bundle.getStringAny(FIELD_AUTH_SIGNATURE, LEGACY_FIELD_AUTH_SIGNATURE), expectedSignature)) {
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

    private fun Bundle.getByteArrayAny(vararg keys: String): ByteArray? {
        for (key in keys) {
            getByteArray(key)?.let { return it }
        }
        return null
    }

    private fun Bundle.getStringAny(vararg keys: String): String? {
        for (key in keys) {
            getString(key)?.let { return it }
        }
        return null
    }

    private fun Bundle.getLongAny(vararg keys: String): Long {
        for (key in keys) {
            if (containsKey(key)) return getLong(key)
        }
        return 0L
    }

    private fun Bundle.containsAny(vararg keys: String): Boolean {
        return keys.any { containsKey(it) }
    }
}
