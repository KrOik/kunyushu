package tv.kyshu.ui.mock

/**
 * Playback finish policy for a route (Kuroko-Teleport loop/max_dist/max_laps semantics).
 */
enum class RouteFinishPolicy {
    /** Legacy behaviour: replay from the start forever. */
    LOOP_FOREVER,
    /** Stop after covering [HistoricalRoute.targetDistanceMeters] total distance. */
    MILEAGE,
    /** Stop after completing [HistoricalRoute.targetLaps] full laps of the route. */
    LAPS,
    /** Stop after one lap / one pass of the route. */
    SINGLE_PASS,
}

data class HistoricalRoute(
    val name: String,
    val route: List<Pair<Double, Double>>,
    val travelMode: RouteTravelMode = RouteTravelMode.WALKING,
    val customSpeedMetersPerSecond: Double = 1.4,
    val waypointWaitSeconds: List<Int> = emptyList(),
    val useRoadRoute: Boolean = false,
    /** Curve smoothing via Catmull-Rom densification (Kuroko port). */
    val smoothCurve: Boolean = true,
    /** Finish policy; default keeps the legacy replay-forever behaviour. */
    val finishPolicy: RouteFinishPolicy = RouteFinishPolicy.LOOP_FOREVER,
    /** Total distance target in meters when [finishPolicy] == MILEAGE. */
    val targetDistanceMeters: Double = 0.0,
    /** Lap target when [finishPolicy] == LAPS. */
    val targetLaps: Int = 0,
    /** Pace fluctuation ratio r in [0,1] (OU modulation depth). */
    val paceVarianceRatio: Double = 0.15,
) {
    fun speedMetersPerSecond(): Double = when (travelMode) {
        RouteTravelMode.WALKING -> 1.4
        RouteTravelMode.RUNNING -> 3.0
        RouteTravelMode.CYCLING -> 5.5
        RouteTravelMode.DRIVING -> 15.0
        RouteTravelMode.CUSTOM -> customSpeedMetersPerSecond.coerceIn(0.1, 100.0)
    }

    fun waitSecondsAt(index: Int): Int = waypointWaitSeconds.getOrNull(index)?.coerceIn(0, 3_600) ?: 0
}

enum class RouteTravelMode {
    WALKING,
    RUNNING,
    CYCLING,
    DRIVING,
    CUSTOM,
}
