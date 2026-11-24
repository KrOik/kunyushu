package tv.kyshu.ui.mock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RouteSmootherTest {

    /** 4 waypoints forming a right-angle corner (~100 m legs). */
    private val lShapeRoute = listOf(
        39.9042 to 116.4074,
        39.9051 to 116.4074,
        39.9051 to 116.4086,
        39.9060 to 116.4086,
    )

    @Test
    fun buildProducesMonotonicCumulativeSamples() {
        val route = RouteSmoother.build(lShapeRoute, loop = false)
        assertTrue("expected dense samples, got ${route.samples.size}", route.samples.size > 40)
        assertTrue("route length should be ~300m, got ${route.lengthM}", route.lengthM in 250.0..350.0)
        for (i in 1 until route.samples.size) {
            assertTrue(route.samples[i].cumDistM > route.samples[i - 1].cumDistM)
        }
        assertEquals(route.samples.last().cumDistM, route.lengthM, 1e-6)
    }

    @Test
    fun cornerFactorDropsAtNinetyDegreeTurn() {
        val route = RouteSmoother.build(lShapeRoute, loop = false)
        val minCf = route.samples.minOf { it.cornerFactor }
        assertTrue("corner slowdown expected, min cf=$minCf", minCf < 0.75)
        val straightMax = route.samples.take(20).maxOf { it.cornerFactor }
        assertTrue("straight cf should stay 1.0, got $straightMax", abs(straightMax - 1.0) < 1e-9)
    }

    @Test
    fun sampleAtInterpolatesInsideBounds() {
        val route = RouteSmoother.build(lShapeRoute, loop = false)
        val s0 = route.sampleAt(0.0)
        val sEnd = route.sampleAt(route.lengthM)
        assertEquals(lShapeRoute.first().first, s0.lat, 1e-6)
        assertEquals(lShapeRoute.last().first, sEnd.lat, 1e-6)
        val sMid = route.sampleAt(route.lengthM / 2)
        assertTrue(sMid.lat in 39.904..39.9061)
    }

    @Test
    fun walkerAccumulatesDistanceAndRespectsMaxMileage() {
        val route = RouteSmoother.build(lShapeRoute, loop = false)
        val walker = RouteSmoother.Walker(
            route = route,
            maxDistanceM = 50.0,
            maxLaps = 0,
            paceVarianceRatio = 0.0, // deterministic for the test
            random = kotlin.random.Random(42),
        )
        var steps = 0
        while (!walker.finished && steps < 1000) {
            walker.tick(0.5, 2.0) // 1 m per tick nominal
            steps++
        }
        assertTrue("walker should finish, steps=$steps dist=${walker.totalDistanceM}", walker.finished)
        assertTrue("mileage ~50m expected, got ${walker.totalDistanceM}", walker.totalDistanceM in 49.0..52.0)
        assertEquals("speed should park at 0", 0.0, walker.currentSpeedMps, 1e-9)
    }

    @Test
    fun loopRouteWrapsAndCountsLaps() {
        val route = RouteSmoother.build(lShapeRoute, loop = true)
        val walker = RouteSmoother.Walker(
            route = route,
            maxDistanceM = 0.0,
            maxLaps = 2,
            paceVarianceRatio = 0.0,
            random = kotlin.random.Random(7),
        )
        var steps = 0
        while (!walker.finished && steps < 5000) {
            walker.tick(0.25, 4.0) // 1 m per tick
            steps++
        }
        assertTrue("2 laps should finish, laps=${walker.lapCount} steps=$steps", walker.finished)
        assertEquals(2, walker.lapCount)
    }

    @Test
    fun paceVarianceKeepsSpeedWithinClamp() {
        val route = RouteSmoother.build(lShapeRoute, loop = false)
        val walker = RouteSmoother.Walker(
            route = route,
            paceVarianceRatio = 0.3,
            random = kotlin.random.Random(1),
        )
        // straight stretch: corner factor 1.0, so speed stays within [0.65*2, 1.48*2]
        var minSpeed = Double.MAX_VALUE
        var maxSpeed = 0.0
        for (i in 0 until 200) {
            walker.tick(0.5, 2.0)
            if (walker.distOnRouteM < 60.0) { // still on the straight first leg
                minSpeed = minOf(minSpeed, walker.currentSpeedMps)
                maxSpeed = maxOf(maxSpeed, walker.currentSpeedMps)
            }
        }
        assertTrue("speed floor breached: $minSpeed", minSpeed >= 2.0 * 0.65 - 0.01)
        assertTrue("speed ceiling breached: $maxSpeed", maxSpeed <= 2.0 * 1.48 + 0.01)
    }

    @Test
    fun haversineMatchesKnownDistance() {
        // 1 degree of latitude ~ 111.19 km
        val d = RouteSmoother.haversineM(39.0, 116.0, 40.0, 116.0)
        assertTrue("1° lat = $d", d in 110_500.0..112_000.0)
    }
}
