package tv.kyshu.xposed.config

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationDataTest {
    @Test
    fun `blank rinex path does not clear existing value`() {
        val data = LocationData()
        data.rinexPath = "/data/user/0/tv.kyshu/files/brdc.rnx"

        data.updateFromMap(mapOf("rinex_path" to ""))

        assertEquals("/data/user/0/tv.kyshu/files/brdc.rnx", data.rinexPath)
    }

    @Test
    fun `non blank rinex path updates value`() {
        val data = LocationData()
        data.rinexPath = "/old/brdc.rnx"

        data.updateFromMap(mapOf("rinex_path" to " /data/user/0/tv.kyshu/files/brdc.rnx "))

        assertEquals("/data/user/0/tv.kyshu/files/brdc.rnx", data.rinexPath)
    }
}
