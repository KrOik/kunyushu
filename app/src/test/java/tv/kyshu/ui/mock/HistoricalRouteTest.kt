package tv.kyshu.ui.mock

import com.alibaba.fastjson2.JSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HistoricalRouteTest {
    @Test fun `legacy route json receives safe version two defaults`() {
        val legacy = """{"name":"old","route":[{"first":39.9,"second":116.4},{"first":39.8,"second":116.5}]}"""
        val route = JSON.parseObject(legacy, HistoricalRoute::class.java)
        assertEquals(RouteTravelMode.WALKING, route.travelMode)
        assertEquals(1.4, route.speedMetersPerSecond(), 0.0)
        assertEquals(0, route.waitSecondsAt(1))
        assertFalse(route.useRoadRoute)
    }

    @Test fun `custom speed and waits are bounded`() {
        val route = HistoricalRoute("x", listOf(0.0 to 0.0, 1.0 to 1.0), RouteTravelMode.CUSTOM, 500.0, listOf(-1, 9000))
        assertEquals(100.0, route.speedMetersPerSecond(), 0.0)
        assertEquals(0, route.waitSecondsAt(0))
        assertEquals(3600, route.waitSecondsAt(1))
    }
}
