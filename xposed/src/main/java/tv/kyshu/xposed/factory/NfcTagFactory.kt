package tv.kyshu.xposed.factory

import android.nfc.NdefMessage
import android.nfc.Tag
import android.os.Bundle
import tv.kyshu.xposed.config.NfcReplayProfile

object NfcTagFactory {
    const val TECH_NDEF = 6
    const val NDEF_EXTRA_MESSAGE_KEY = "ndefmsg"
    const val NDEF_EXTRA_MAX_LENGTH_KEY = "ndefmaxlength"
    const val NDEF_EXTRA_CARD_STATE_KEY = "ndefcardstate"
    const val NDEF_EXTRA_TYPE_KEY = "ndeftype"

    private val defaultUid = byteArrayOf(0x02, 0x00, 0x00, 0x00)

    fun createTag(profile: NfcReplayProfile): Tag? {
        val uid = profile.uidHex.hexToBytesOrNull() ?: return null
        val message = createNdefMessage(profile) ?: return null
        val techCodes = techCodesFor(profile.techList.ifEmpty { listOf("android.nfc.tech.Ndef") })
        if (techCodes.isEmpty()) return null
        val extras = techCodes.map { techCode ->
            if (techCode == TECH_NDEF) createNdefExtras(message, profile.maxPayloadBytes) else Bundle()
        }.toTypedArray()
        val tagUid = if (uid.isEmpty()) defaultUid else uid
        return createMockTag(tagUid, techCodes.toIntArray(), extras)
    }

    fun createNdefMessage(profile: NfcReplayProfile): NdefMessage? {
        if (profile.ndefPayloadHex.isBlank()) {
            return runCatching { NdefMessage(arrayOf(android.nfc.NdefRecord.createTextRecord("en", ""))) }.getOrNull()
        }
        if (profile.ndefPayloadHex.hexByteCount() > profile.maxPayloadBytes) return null
        val bytes = profile.ndefPayloadHex.hexToBytesOrNull() ?: return null
        return runCatching { NdefMessage(bytes) }.getOrNull()
    }

    fun createNdefExtras(message: NdefMessage, maxPayloadBytes: Int): Bundle =
        Bundle().apply {
            putParcelable(NDEF_EXTRA_MESSAGE_KEY, message)
            putInt(NDEF_EXTRA_MAX_LENGTH_KEY, maxPayloadBytes)
            putInt(NDEF_EXTRA_CARD_STATE_KEY, 2)
            putInt(NDEF_EXTRA_TYPE_KEY, 2)
        }

    fun getTechExtras(tag: Tag, techCode: Int): Bundle? =
        runCatching {
            val method = Tag::class.java.getDeclaredMethod("getTechExtras", Int::class.javaPrimitiveType!!)
            if (!method.isAccessible) method.isAccessible = true
            method.invoke(tag, techCode) as? Bundle
        }.getOrNull()

    fun techCodesFor(techList: List<String>): List<Int> =
        techList.mapNotNull { techCodeFor(it) }.distinct()

    private fun techCodeFor(name: String): Int? =
        when (name.trim()) {
            "android.nfc.tech.NfcA", "NfcA" -> 1
            "android.nfc.tech.NfcB", "NfcB" -> 2
            "android.nfc.tech.IsoDep", "IsoDep" -> 3
            "android.nfc.tech.NfcF", "NfcF" -> 4
            "android.nfc.tech.NfcV", "NfcV" -> 5
            "android.nfc.tech.Ndef", "Ndef" -> TECH_NDEF
            "android.nfc.tech.NdefFormatable", "NdefFormatable" -> 7
            "android.nfc.tech.MifareClassic", "MifareClassic" -> 8
            "android.nfc.tech.MifareUltralight", "MifareUltralight" -> 9
            "android.nfc.tech.NfcBarcode", "NfcBarcode" -> 10
            else -> null
        }

    private fun createMockTag(uid: ByteArray, techCodes: IntArray, extras: Array<Bundle>): Tag? =
        runCatching {
            val method = Tag::class.java.getDeclaredMethod(
                "createMockTag",
                ByteArray::class.java,
                IntArray::class.java,
                Array<Bundle>::class.java,
            )
            if (!method.isAccessible) method.isAccessible = true
            method.invoke(null, uid, techCodes, extras) as? Tag
        }.getOrElse {
            runCatching {
                val constructor = Tag::class.java.getDeclaredConstructor(
                    ByteArray::class.java,
                    IntArray::class.java,
                    Array<Bundle>::class.java,
                    Int::class.javaPrimitiveType!!,
                    Class.forName("android.nfc.INfcTag"),
                )
                if (!constructor.isAccessible) constructor.isAccessible = true
                constructor.newInstance(uid, techCodes, extras, 0, null) as? Tag
            }.getOrNull()
        }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (isEmpty()) return byteArrayOf()
        if (length % 2 != 0) return null
        val result = ByteArray(length / 2)
        for (index in result.indices) {
            val value = substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
            result[index] = value.toByte()
        }
        return result
    }

    private fun String.hexByteCount(): Int = length / 2
}
