package tv.kyshu.ui.mock

data class HistoricalRoute(
    val name: String,
    val route: List<Pair<Double, Double>>,
    val travelMode: RouteTravelMode = RouteTravelMode.WALKING,
    val customSpeedMetersPerSecond: Double = 1.4,
    val waypointWaitSeconds: List<Int> = emptyList(),
    val useRoadRoute: Boolean = false,
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
