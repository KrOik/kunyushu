package tv.kyshu.xposed.hooks.nmea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Log
import tv.kyshu.xposed.utils.LogConfig
import java.util.Locale

class LocationNMEAHookTest {
    @Test
    fun `generated GSV sentences keep callback line boundary`() {
        withFakeLocEnabled {
            val first = LocationNMEAHook.injectNMEA(nmea("GPGSV,3,1,12,01,10,010,20"))
            val second = LocationNMEAHook.injectNMEA(nmea("GPGSV,3,2,12,05,20,120,30"))

            assertNotNull(first)
            assertNotNull(second)
            assertTrue(first!!.endsWith("\r\n"))
            assertTrue(second!!.endsWith("\r\n"))
            assertTrue((first + second).contains("\r\n\$GPGSV"))
            assertValidSingleNmea(first)
            assertValidSingleNmea(second)
        }
    }

    @Test
    fun `GSV cache is isolated by constellation`() {
        withFakeLocEnabled {
            val gpFirst = LocationNMEAHook.injectNMEA(nmea("GPGSV,3,1,12,01,10,010,20"))
            val gaFirst = LocationNMEAHook.injectNMEA(nmea("GAGSV,3,1,12,01,10,010,20"))

            assertNotNull(gpFirst)
            assertNotNull(gaFirst)

            val gpSecond = LocationNMEAHook.injectNMEA(nmea("GPGSV,3,2,12,05,20,120,30"))
            val gaSecond = LocationNMEAHook.injectNMEA(nmea("GAGSV,3,2,12,05,20,120,30"))

            assertNotNull(gpSecond)
            assertNotNull(gaSecond)
            assertTrue(gpSecond!!.startsWith("\$GPGSV"))
            assertTrue(gaSecond!!.startsWith("\$GAGSV"))
        }
    }

    @Test
    fun `BeiDou GSV preserves incoming GB talker`() {
        withFakeLocEnabled {
            val injected = LocationNMEAHook.injectNMEA(nmea("GBGSV,2,1,08,01,10,010,20"))

            assertNotNull(injected)
            assertTrue(injected!!.startsWith("\$GBGSV"))
            assertValidSingleNmea(injected)
        }
    }

    @Test
    fun `invalid NMEA input is not injected`() {
        withFakeLocEnabled {
            assertNull(LocationNMEAHook.injectNMEA("\$GPGSV,3,1,12,01,10,010,20*00"))
            assertNull(LocationNMEAHook.injectNMEA("\$GPGSV,1,1,04,01,10,010,20*00\$GPGSV,1,1,04,02,20,020,30*00"))
        }
    }

    @Test
    fun `OPPO ambience NMEA callbacks are skipped`() {
        assertTrue(LocationNMEAHook.shouldSkipNmeaCallback(DeepThinkerDelegateCallback()))
        assertTrue(LocationNMEAHook.shouldSkipNmeaCallback(TransportWithDeepThinkerField()))
    }

    @Test
    fun `system NMEA proxy registration is skipped by registration identity`() {
        val callback = GenericNmeaProxy()

        assertTrue(
            LocationNMEAHook.shouldSkipNmeaRegistration(
                callback,
                arrayOf(callback, "android", null, "GnssNmeaListener"),
            ),
        )
    }

    @Test
    fun `app NMEA proxy registration is not skipped by registration identity`() {
        val callback = GenericNmeaProxy()

        assertFalse(
            LocationNMEAHook.shouldSkipNmeaRegistration(
                callback,
                arrayOf(callback, "com.x1y9.probe", null, "GnssNmeaListener"),
            ),
        )
    }

    private fun withFakeLocEnabled(block: () -> Unit) {
        val oldEnable = FakeLoc.enable
        val oldLat = FakeLoc.latitude
        val oldLon = FakeLoc.longitude
        try {
            LogConfig.addRoute("PTL", channels = 0, levels = Log.Level.ALL)
            FakeLoc.enable = true
            FakeLoc.latitude = 39.9042
            FakeLoc.longitude = 116.4074
            block()
        } finally {
            FakeLoc.enable = oldEnable
            FakeLoc.latitude = oldLat
            FakeLoc.longitude = oldLon
            LogConfig.removeRoute("PTL")
        }
    }

    private fun assertValidSingleNmea(sentence: String) {
        val trimmed = sentence.trimEnd('\r', '\n')
        assertEquals(1, trimmed.count { it == '$' })
        val star = trimmed.indexOf('*')
        assertTrue(star > 1)
        val content = trimmed.substring(1, star)
        assertEquals(checksum(content), trimmed.substring(star + 1).uppercase(Locale.US))
    }

    private fun nmea(content: String): String = "\$$content*${checksum(content)}"

    private fun checksum(content: String): String {
        var sum = 0
        for (char in content) {
            sum = sum xor char.code
        }
        return String.format(Locale.US, "%02X", sum)
    }

    private class DeepThinkerDelegateCallback
    private class GenericNmeaProxy
    private class TransportWithDeepThinkerField {
        @Suppress("unused")
        private val listener = DeepThinkerDelegateCallback()
    }
}
