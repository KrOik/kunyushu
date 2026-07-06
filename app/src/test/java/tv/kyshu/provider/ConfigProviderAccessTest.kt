package tv.kyshu.provider

import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import tv.kyshu.service.MockServiceHelper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConfigProviderAccessTest {
    private lateinit var context: Context
    private lateinit var provider: ConfigProvider

    private val configUri = Uri.parse("content://${ConfigProvider.AUTHORITY}/config")
    private val cmdUri = Uri.parse("content://${ConfigProvider.AUTHORITY}/cmd")
    private val rinexUri = Uri.parse("content://${ConfigProvider.AUTHORITY}/rinex")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        provider = ConfigProvider().also {
            it.attachInfo(
                context,
                ProviderInfo().apply {
                    authority = ConfigProvider.AUTHORITY
                    exported = true
                },
            )
        }
    }

    @Test
    fun `external hook process can read non sensitive config`() {
        val rinexFile = File(context.filesDir, "brdc.rnx")
        val gaitJson = """{"frequency":1.8,"humanizeIntensity":0.04}"""
        context.getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enable", true)
            .putString("rinexPath", rinexFile.absolutePath)
            .putString("gaitParamsJson", gaitJson)
            .commit()
        ShadowBinder.setCallingUid(10_338)

        val rows = queryConfigRows()

        assertEquals("true", rows["enable"])
        assertEquals(rinexFile.absolutePath, rows["rinex_path"])
        assertEquals(gaitJson, rows["gait_params_json"])
        assertFalse(rows.containsKey("latitude"))
        assertFalse(rows.containsKey("longitude"))
    }

    @Test
    fun `external hook process can read rinex stream`() {
        val rinexFile = File(context.filesDir, "brdc.rnx")
        rinexFile.writeText(validRinexContent(), Charsets.US_ASCII)
        context.getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("rinexPath", rinexFile.absolutePath)
            .commit()
        ShadowBinder.setCallingUid(10_441)

        val descriptor = provider.openFile(rinexUri, "r")

        assertNotNull(descriptor)
        descriptor?.close()
    }

    @Test
    fun `external hook process cannot write command queue`() {
        ShadowBinder.setCallingUid(10_338)

        assertThrows(SecurityException::class.java) {
            provider.insert(cmdUri, ContentValues().apply { put("command", "{}") })
        }
    }

    private fun queryConfigRows(): Map<String, String> {
        val cursor = provider.query(configUri, null, null, null, null)
        assertNotNull(cursor)
        val rows = linkedMapOf<String, String>()
        cursor!!.use {
            val keyIndex = it.getColumnIndex("key")
            val valueIndex = it.getColumnIndex("value")
            while (it.moveToNext()) {
                rows[it.getString(keyIndex)] = it.getString(valueIndex)
            }
        }
        return rows
    }

    private fun validRinexContent(): String = buildString {
        append("     3.05           N: GNSS NAV DATA    M                   RINEX VERSION / TYPE\n")
        append("Portal test fixture                                                  COMMENT\n")
        repeat(32) {
            append("G01 2026 07 05 00 00 00 0.0 0.0 0.0 0.0                       TEST\n")
        }
    }
}
