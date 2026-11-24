package tv.kyshu.xposed.factory

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalConsistencyModelTest {
    @Test
    fun `correlated location samples remain bounded and evolve over time`() {
        val model = CorrelatedLocationModel(Random(7L))
        val base = input(nowMs = 1_000L)
        val initial = model.sample(base)
        var latest = initial

        repeat(1_000) { index ->
            latest = model.sample(base.copy(nowMs = 2_000L + index * 1_000L))
            assertTrue(distanceMeters(base.baseLatitude, base.baseLongitude, latest.latitude, latest.longitude) <= 2.01)
            assertTrue(latest.altitudeMeters in 79.3..80.7)
            assertTrue(latest.speedMetersPerSecond in 0.35..2.35)
            assertTrue(latest.horizontalAccuracyMeters in 0.5..45.0)
        }

        assertNotEquals(initial.latitude, latest.latitude, 0.0)
        assertNotEquals(initial.longitude, latest.longitude, 0.0)
    }

    @Test
    fun `large coordinate jump resets accumulated noise`() {
        val model = CorrelatedLocationModel(Random(11L))
        model.sample(input(nowMs = 1_000L))
        model.sample(input(nowMs = 2_000L))

        val jumped = model.sample(
            input(nowMs = 3_000L).copy(baseLatitude = 40.0, baseLongitude = 116.0)
        )

        assertEquals(40.0, jumped.latitude, 0.0)
        assertEquals(116.0, jumped.longitude, 0.0)
        assertEquals(80.0, jumped.altitudeMeters, 0.0)
    }

    @Test
    fun `radio fingerprint is stable valid and location dependent`() {
        val first = RadioFingerprintGenerator.generate(31.2304, 121.4737, 12)
        val repeated = RadioFingerprintGenerator.generate(31.2304, 121.4737, 12)
        val elsewhere = RadioFingerprintGenerator.generate(39.9042, 116.4074, 12)

        assertEquals(first, repeated)
        assertNotEquals(first, elsewhere)
        assertEquals(12, first.map { it.bssid }.toSet().size)
        first.forEach { profile ->
            assertTrue(profile.bssid.matches(Regex("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")))
            val firstOctet = profile.bssid.substring(0, 2).toInt(16)
            assertEquals(0, firstOctet and 1)
            assertTrue(profile.rssiDbm in -85..-35)
        }
    }

    private fun input(nowMs: Long) = LocationDynamicsInput(
        nowMs = nowMs,
        baseLatitude = 31.2304,
        baseLongitude = 121.4737,
        baseAltitudeMeters = 80.0,
        horizontalAccuracyMeters = 30.0,
        baseSpeedMetersPerSecond = 1.35,
        speedAmplitudeMetersPerSecond = 1.0,
        altitudeAmplitudeMeters = 0.7,
    )

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val north = Math.toRadians(lat2 - lat1) * 6_378_137.0
        val east = Math.toRadians(lon2 - lon1) * 6_378_137.0 * kotlin.math.cos(Math.toRadians(lat1))
        return kotlin.math.hypot(north, east)
    }
}
