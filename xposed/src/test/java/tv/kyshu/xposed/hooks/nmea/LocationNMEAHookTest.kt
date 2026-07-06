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
    fun `generated NMEA uses protocol decimal separator under comma locale`() {
        withDefaultLocale(Locale.FRANCE) {
            withFakeLocEnabled {
                val cases = listOf(
                    nmea("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"),
                    nmea("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,,,"),
                    nmea("GPGSA,A,3,04,05,09,12,,,,,,,,,1.8,1.0,1.2"),
                    nmea("GNGNS,123519,4807.038,N,01131.000,E,AN,08,0.9,545.4,46.9,,"),
                    nmea("GPVTG,054.7,T,034.4,M,005.5,N,010.2,K"),
                    nmea("GPGST,024603.00,1.2,0.8,0.7,45.0,0.5,0.5,1.0"),
                )

                cases.forEach { input ->
                    val injected = LocationNMEAHook.injectNMEA(input)

                    assertNotNull(input, injected)
                    assertValidSingleNmea(injected!!)
                    assertProtocolDecimalFields(injected)
                }
            }
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
        val oldAlt = FakeLoc.altitude
        val oldSpeed = FakeLoc.speed
        val oldAccuracy = FakeLoc.accuracy
        val oldBearing = FakeLoc.bearing
        val oldMinSatellites = FakeLoc.minSatellites
        try {
            LogConfig.addRoute("PTL", channels = 0, levels = Log.Level.ALL)
            FakeLoc.enable = true
            FakeLoc.latitude = 39.9042
            FakeLoc.longitude = 116.4074
            FakeLoc.altitude = 45.6
            FakeLoc.speed = 2.5
            FakeLoc.accuracy = 4.5f
            FakeLoc.bearing = 123.4
            FakeLoc.minSatellites = 12
            block()
        } finally {
            FakeLoc.enable = oldEnable
            FakeLoc.latitude = oldLat
            FakeLoc.longitude = oldLon
            FakeLoc.altitude = oldAlt
            FakeLoc.speed = oldSpeed
            FakeLoc.accuracy = oldAccuracy
            FakeLoc.bearing = oldBearing
            FakeLoc.minSatellites = oldMinSatellites
            LogConfig.removeRoute("PTL")
        }
    }

    private fun withDefaultLocale(locale: Locale, block: () -> Unit) {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(oldLocale)
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

    private fun assertProtocolDecimalFields(sentence: String) {
        val content = sentence.trimEnd('\r', '\n').substringAfter("$").substringBefore("*")
        val fields = content.split(",")
        when (fields.first()) {
            "GPGGA" -> assertDotDecimal(fields, 2, 4, 8, 9)
            "GPRMC" -> assertDotDecimal(fields, 3, 5, 7, 8)
            "GPGSA" -> assertDotDecimal(fields, 15, 16, 17)
            "GNGNS" -> assertDotDecimal(fields, 2, 4, 8, 9)
            "GPVTG" -> assertDotDecimal(fields, 1, 5, 7)
            "GPGST" -> assertDotDecimal(fields, 1, 2, 3, 4, 5, 6, 7)
        }
    }

    private fun assertDotDecimal(fields: List<String>, vararg indices: Int) {
        indices.forEach { index ->
            val value = fields[index]
            assertTrue("Expected dot decimal at field $index in $fields", value.contains('.'))
            assertFalse("Expected no comma decimal at field $index in $fields", value.contains(','))
        }
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
