package tv.kyshu.xposed.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigStrictParsingTest {
    @Test
    fun `critical location fields reject malformed numeric values`() {
        val data = LocationData().apply {
            latitude = 31.2
            longitude = 121.5
        }

        assertThrows(ConfigParseException::class.java) {
            data.updateFromMap(mapOf("latitude" to "bad", "longitude" to 120.0))
        }

        assertEquals(31.2, data.latitude, 0.0)
        assertEquals(121.5, data.longitude, 0.0)
    }

    @Test
    fun `critical location fields reject out of range coordinates`() {
        val data = LocationData()

        assertThrows(ConfigParseException::class.java) {
            data.updateFromMap(mapOf("latitude" to 91.0, "longitude" to 120.0))
        }
    }

    @Test
    fun `non critical getVal falls back on malformed values`() {
        val map = mapOf<String, Any?>("min_satellites" to "bad")

        assertEquals(12, map.getVal("min_satellites", 12))
    }
}
