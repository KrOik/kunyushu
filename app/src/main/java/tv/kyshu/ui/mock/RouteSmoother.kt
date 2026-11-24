package tv.kyshu.ui.mock

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Smooth route playback engine, ported from Kuroko-Teleport `native/daemon/sim_engine.cpp`.
 *
 * Replaces raw waypoint geodesic marching with:
 *  - Centripetal-style Catmull-Rom densification (~3 m samples, clamped 4..40 per segment)
 *  - Corner slowdown factor  cf = 1 - clamp(|Δheading| / 120°, 0, 0.30)
 *  - Two-timescale Ornstein-Uhlenbeck pace fluctuation (τ=18 s σ=0.75r, τ=4 s σ=0.35r)
 *  - Inertial acceleration smoothing (τ=1.2 s), deceleration to park on stop (τ=0.4 s)
 *  - Loop / target-mileage / lap auto-completion with graceful finish
 *
 * Pure math, no Android dependencies — unit-testable on the JVM.
 */
object RouteSmoother {

    private const val EARTH_RADIUS_M = 6371008.8
    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI

    /** One densified sample along the smoothed route. */
    data class Sample(
        val lat: Double,
        val lon: Double,
        val alt: Double,
        /** Cumulative distance along the route in meters. */
        val cumDistM: Double,
        /** Slowdown factor at this sample (1.0 = straight, ~0.70 = tight corner). */
        val cornerFactor: Double,
    )

    /** A fully built route: samples + aggregate length. */
    data class SmoothRoute(
        val samples: List<Sample>,
        val lengthM: Double,
        val loop: Boolean,
    ) {
        fun sampleAt(sM: Double): Sample {
            if (samples.size < 2 || lengthM <= 1e-3) {
                val s = samples.firstOrNull() ?: return Sample(0.0, 0.0, 0.0, 0.0, 1.0)
                return s
            }
            val s = sM.coerceIn(0.0, lengthM)
            // binary search for the segment containing s
            var lo = 0
            var hi = samples.size - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (samples[mid].cumDistM < s) lo = mid + 1 else hi = mid
            }
            val idx = max(1, lo)
            val a = samples[idx - 1]
            val b = samples[min(idx, samples.size - 1)]
            val seg = max(1e-6, b.cumDistM - a.cumDistM)
            val frac = ((s - a.cumDistM) / seg).coerceIn(0.0, 1.0)
            return Sample(
                lat = a.lat + (b.lat - a.lat) * frac,
                lon = a.lon + (b.lon - a.lon) * frac,
                alt = a.alt + (b.alt - a.alt) * frac,
                cumDistM = s,
                cornerFactor = a.cornerFactor + (b.cornerFactor - a.cornerFactor) * frac,
            )
        }
    }

    /** Playback state machine advancing along a [SmoothRoute]. */
    class Walker(
        private val route: SmoothRoute,
        /** 0 = no mileage/lap limit; >0 meters before auto-finish. */
        private val maxDistanceM: Double = 0.0,
        /** 0 = unlimited laps; >0 laps before auto-finish. */
        private val maxLaps: Int = 0,
        /** Pace fluctuation ratio r in [0,1]; 0 disables OU modulation. */
        paceVarianceRatio: Double = 0.15,
        random: Random = Random.Default,
    ) {
        var distOnRouteM = 0.0
            private set
        var totalDistanceM = 0.0
            private set
        var lapCount = 0
            private set
        var currentSpeedMps = 0.0
            private set
        /** True once a mileage/lap target finished (engine parks at finish). */
        var finished = false
            private set

        private var ouSlow = 0.0
        private var ouFast = 0.0
        private var configuredSpeed = 0.0
        private val slowSigma = 0.75 * paceVarianceRatio
        private val fastSigma = 0.35 * paceVarianceRatio
        private val loFactor = max(0.65, 1.0 - 1.6 * paceVarianceRatio)
        private val hiFactor = 1.0 + 1.6 * paceVarianceRatio

        private val rng = random

        /**
         * Advance the walker by [dtSeconds]. [speedMps] is the configured cruise speed;
         * it may change between ticks (settings live-adjust).
         *
         * @return effective position sample after advancing; speed in the sample's
         *  companion fields is reported via [currentSpeedMps].
         */
        fun tick(dtSeconds: Double, speedMps: Double): Sample {
            configuredSpeed = speedMps.coerceIn(0.0, 100.0)
            if (!moving || finished) {
                // Decelerate smoothly to 0 when paused (τ = 0.4 s)
                currentSpeedMps *= exp(-dtSeconds / 0.4)
                if (currentSpeedMps < 0.05) currentSpeedMps = 0.0
                return route.sampleAt(distOnRouteM)
            }

            // Two-timescale OU pace fluctuation (Kuroko sim_engine.cpp:477-493)
            val slowDecay = exp(-dtSeconds / 18.0)
            val fastDecay = exp(-dtSeconds / 4.0)
            ouSlow = ouSlow * slowDecay +
                slowSigma * sqrt(max(0.0, 1.0 - slowDecay * slowDecay)) * randNormal()
            ouFast = ouFast * fastDecay +
                fastSigma * sqrt(max(0.0, 1.0 - fastDecay * fastDecay)) * randNormal()
            val speedMod = (1.0 + ouSlow + ouFast).coerceIn(loFactor, hiFactor)

            val here = route.sampleAt(distOnRouteM)
            val desiredV = configuredSpeed * here.cornerFactor * speedMod
            // Inertial accel smoothing (τ = 1.2 s)
            val alpha = 1.0 - exp(-dtSeconds / 1.2)
            currentSpeedMps += (desiredV - currentSpeedMps) * alpha
            val effSpeed = max(0.0, currentSpeedMps)

            val stepM = effSpeed * dtSeconds
            distOnRouteM += stepM
            totalDistanceM += stepM

            if (maxDistanceM > 0.0 && totalDistanceM >= maxDistanceM) {
                finished = true
                moving = false
                currentSpeedMps = 0.0
            } else if (distOnRouteM >= route.lengthM) {
                lapCount++
                if (maxLaps > 0 && lapCount >= maxLaps) {
                    distOnRouteM = if (route.loop) 0.0 else route.lengthM
                    finished = true
                    moving = false
                    currentSpeedMps = 0.0
                } else {
                    distOnRouteM %= route.lengthM
                }
            }

            return route.sampleAt(distOnRouteM)
        }

        var moving: Boolean = true
            private set

        fun pause() {
            moving = false
        }

        fun resume() {
            if (!finished) moving = true
        }

        private fun randNormal(): Double {
            // Box–Muller
            var u1 = rng.nextDouble()
            if (u1 < 1e-12) u1 = 1e-12
            val u2 = rng.nextDouble()
            return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
        }
    }

    // ---------------------------------------------------------------------------
    // Route building
    // ---------------------------------------------------------------------------

    /**
     * Build a smoothed route from raw waypoints. [loop] closes the spline back to the
     * first point; non-loop routes clamp control points at the ends (Kuroko get_pt).
     */
    fun build(
        waypoints: List<Pair<Double, Double>>,
        loop: Boolean,
        altitude: Double = 0.0,
    ): SmoothRoute {
        if (waypoints.size < 2) {
            val only = waypoints.firstOrNull() ?: return SmoothRoute(emptyList(), 0.0, loop)
            return SmoothRoute(
                listOf(Sample(only.first, only.second, altitude, 0.0, 1.0)),
                0.0,
                loop,
            )
        }
        val pts = waypoints
        val n = pts.size
        val segs = if (loop) n else n - 1

        fun ptAt(idx: Int): Pair<Double, Double> {
            return if (loop) {
                val m = ((idx % n) + n) % n
                pts[m]
            } else {
                pts[idx.coerceIn(0, n - 1)]
            }
        }

        val dense = ArrayList<Sample>(segs * 12)
        for (i in 0 until segs) {
            val p0 = ptAt(i - 1)
            val p1 = ptAt(i)
            val p2 = ptAt(i + 1)
            val p3 = ptAt(i + 2)
            val segLen = haversineM(p1.first, p1.second, p2.first, p2.second)
            val sub = (ceil(segLen / 3.0).toInt()).coerceIn(4, 40)
            for (k in 0 until sub) {
                val t = k.toDouble() / sub
                val lat = catmullRom(p0.first, p1.first, p2.first, p3.first, t)
                val lon = catmullRom(p0.second, p1.second, p2.second, p3.second, t)
                dense.add(Sample(lat, lon, altitude, 0.0, 1.0))
            }
        }
        // Close the dense list
        if (loop) {
            dense.add(dense.first().copy(cumDistM = 0.0, cornerFactor = 1.0))
        } else {
            dense.add(Sample(pts.last().first, pts.last().second, altitude, 0.0, 1.0))
        }

        // Cumulative distances
        var cum = 0.0
        val samples = ArrayList<Sample>(dense.size)
        for (i in dense.indices) {
            if (i > 0) {
                cum += haversineM(dense[i - 1].lat, dense[i - 1].lon, dense[i].lat, dense[i].lon)
            }
            samples.add(dense[i].copy(cumDistM = cum))
        }

        // Corner slowdown factors from local heading change (Kuroko sim_engine.cpp:271-277)
        for (i in 1 until samples.size - 1) {
            val b1 = initialBearingDeg(samples[i - 1].lat, samples[i - 1].lon, samples[i].lat, samples[i].lon)
            val b2 = initialBearingDeg(samples[i].lat, samples[i].lon, samples[i + 1].lat, samples[i + 1].lon)
            var diff = abs(b2 - b1)
            if (diff > 180.0) diff = 360.0 - diff
            val cf = 1.0 - (diff / 120.0).coerceIn(0.0, 0.30)
            samples[i] = samples[i].copy(cornerFactor = cf)
        }

        return SmoothRoute(samples, cum, loop)
    }

    /** Centripetal-style Catmull-Rom interpolation (Kuroko catmull_rom). */
    private fun catmullRom(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * ((2.0 * p1) +
            (-p0 + p2) * t +
            (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
            (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3)
    }

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1 * DEG_TO_RAD
        val phi2 = lat2 * DEG_TO_RAD
        val dphi = (lat2 - lat1) * DEG_TO_RAD
        val dlam = (lon2 - lon1) * DEG_TO_RAD
        val a = sin(dphi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dlam / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1 * DEG_TO_RAD
        val phi2 = lat2 * DEG_TO_RAD
        val dlam = (lon2 - lon1) * DEG_TO_RAD
        val y = sin(dlam) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dlam)
        var b = atan2(y, x) * RAD_TO_DEG
        if (b < 0.0) b += 360.0
        return b
    }

    private fun ln(x: Double): Double = kotlin.math.ln(x)
}
