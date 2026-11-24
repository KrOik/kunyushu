package tv.kyshu.ui.viewmodel

import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import android.app.Activity
import android.location.LocationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.kyshu.android.coro.CoroutineController
import tv.kyshu.android.coro.CoroutineRouteMock
import tv.kyshu.environment.EnvironmentReplayCoordinator
import tv.kyshu.ext.Loc4j
import tv.kyshu.ext.accuracy
import tv.kyshu.ext.altitude
import tv.kyshu.ext.reportDuration
import tv.kyshu.ext.speed
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.mock.HistoricalLocation
import tv.kyshu.ui.mock.HistoricalRoute
import tv.kyshu.ui.mock.RouteFinishPolicy
import tv.kyshu.ui.mock.RouteSmoother
import tv.kyshu.ui.mock.Rocker
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Log as XLog
import net.sf.geographiclib.Geodesic

@HiltViewModel
class MockServiceViewModel @Inject constructor() : ViewModel() {
    lateinit var rocker: Rocker
    private var rockerJob: Job? = null
    private var routeMockJob: Job? = null
    private var environmentReplay: EnvironmentReplayCoordinator? = null
    private var lastReplayCheckAt = 0L
    var isRockerLocked = false
    var routeStage = 0
    private var routeWaitUntilMillis = 0L
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

    var isRouteStart = false

    private var routeWalker: RouteSmoother.Walker? = null
    private var smoothRoute: RouteSmoother.SmoothRoute? = null

    /** Prepare (or rebuild) the smoothed route + walker for the selected route. */
    private fun ensureRouteEngine(route: HistoricalRoute) {
        val needsRebuild = smoothRoute == null ||
            routeWalker == null ||
            engineRouteName != route.name ||
            engineRoutePoints != route.route
        if (!needsRebuild) return
        val loop = route.finishPolicy == RouteFinishPolicy.LOOP_FOREVER
        val built = if (route.smoothCurve) {
            RouteSmoother.build(route.route, loop)
        } else {
            // Legacy straight-line behaviour approximated: sample each leg once
            val samples = ArrayList<RouteSmoother.Sample>(route.route.size)
            var cum = 0.0
            for (i in route.route.indices) {
                if (i > 0) cum += RouteSmoother.haversineM(
                    route.route[i - 1].first, route.route[i - 1].second,
                    route.route[i].first, route.route[i].second
                )
                samples.add(RouteSmoother.Sample(route.route[i].first, route.route[i].second, 0.0, cum, 1.0))
            }
            RouteSmoother.SmoothRoute(samples, cum, loop)
        }
        smoothRoute = built
        val maxDist = if (route.finishPolicy == RouteFinishPolicy.MILEAGE) {
            route.targetDistanceMeters.coerceAtLeast(0.0)
        } else 0.0
        val maxLaps = when (route.finishPolicy) {
            RouteFinishPolicy.LAPS -> route.targetLaps.coerceAtLeast(1)
            RouteFinishPolicy.SINGLE_PASS -> 1
            else -> 0
        }
        routeWalker = RouteSmoother.Walker(
            route = built,
            maxDistanceM = maxDist,
            maxLaps = maxLaps,
            paceVarianceRatio = route.paceVarianceRatio.coerceIn(0.0, 1.0),
        )
        engineRouteName = route.name
        engineRoutePoints = route.route
    }

    private var engineRouteName: String? = null
    private var engineRoutePoints: List<Pair<Double, Double>>? = null

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null)
                MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null

    private fun powerSafeIntervalMs(requestedMs: Int): Long = requestedMs.coerceIn(100, 5_000).toLong()
    private fun distancePerTick(speed: Double, delayTime: Long): Double {
        return speed / (1000.0 / delayTime) / 0.85
    }

    fun initRocker(activity: Activity): Rocker {
        if (environmentReplay == null) {
            environmentReplay = EnvironmentReplayCoordinator(activity.applicationContext)
        }
        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
        }

        if (rockerJob == null || rockerJob!!.isCancelled) {
            rockerCoroutineController.pause()
            val delayTime = powerSafeIntervalMs(activity.reportDuration)
            rockerJob = viewModelScope.launch {
                while (isActive) {
                    rockerCoroutineController.controlledCoroutine()
                    delay(delayTime)

                    locationManager?.let { mgr ->
                        if (!MockServiceHelper.move(mgr, distancePerTick(FakeLoc.speed, delayTime), FakeLoc.bearing)) {
                            XLog.e("MockServiceViewModel", "Failed to move")
                        } else {
                            replayCurrentLocation(mgr)
                        }
                    } ?: break
                }
            }
        }

        FakeLoc.speed = activity.speed
        FakeLoc.altitude = activity.altitude
        FakeLoc.accuracy = activity.accuracy

        if (routeMockJob == null || routeMockJob!!.isCancelled) {
            routeMockCoroutine.pause()
            val delayTime = powerSafeIntervalMs(activity.reportDuration)
            routeMockJob = viewModelScope.launch {
                while (isActive) {
                    routeMockCoroutine.routeMockCoroutine()
                    delay(delayTime)
                    if (System.currentTimeMillis() < routeWaitUntilMillis) continue
                    // 如果是第0阶段，定位到第一个点
                    if (routeStage == 0) {
                        val route0 = selectedRoute?.route?.get(0) ?: break
                        locationManager?.let { mgr ->
                            setLocationAndReplay(mgr, route0.first, route0.second, forceReplay = true)
                        } ?: break
                        routeWaitUntilMillis = System.currentTimeMillis() +
                            (selectedRoute?.waitSecondsAt(0) ?: 0) * 1_000L
                        routeStage++
                        if (System.currentTimeMillis() < routeWaitUntilMillis) continue
                    }
                    val route = selectedRoute?.route ?: break
                    var transitionsThisTick = 0
                    while (routeStage < route.size && transitionsThisTick < 32) {
                        val target = route[routeStage]
                        val loc = locationManager?.let { MockServiceHelper.getLocation(it) } ?: break
                        val currentLat = loc.first
                        val currentLon = loc.second

                        val inverse = Geodesic.WGS84.Inverse(
                            currentLat,
                            currentLon,
                            target.first,
                            target.second
                        )
                        // 判断距离是否小于1米（可根据需要调整阈值）
                        if (inverse.s12 < 1.0) {
                            // 精确设置位置到目标点并进入下一阶段
                            locationManager?.let { mgr ->
                                setLocationAndReplay(mgr, target.first, target.second)
                            } ?: break
                            routeStage++
                            routeWaitUntilMillis = System.currentTimeMillis() +
                                (selectedRoute?.waitSecondsAt(routeStage - 1) ?: 0) * 1_000L
                            transitionsThisTick++
                            if (System.currentTimeMillis() < routeWaitUntilMillis) break
                        } else if (inverse.s12 < distancePerTick(selectedRoute?.speedMetersPerSecond() ?: FakeLoc.speed, delayTime)) {
                            // 如果距离小于速度，直接移动到目标点
                            locationManager?.let { mgr ->
                                setLocationAndReplay(mgr, target.first, target.second)
                            } ?: break
                            routeStage++
                            routeWaitUntilMillis = System.currentTimeMillis() +
                                (selectedRoute?.waitSecondsAt(routeStage - 1) ?: 0) * 1_000L
                            transitionsThisTick++
                            if (System.currentTimeMillis() < routeWaitUntilMillis) break

                        } else {
                            break
                        }
                    }

                    // 检查是否已完成所有阶段
                    if (routeStage >= route.size) {
                        routeMockCoroutine.pause()
                        rocker.autoStatus = false
                        // 重设阶段
                        routeStage = 0
                        routeWaitUntilMillis = 0L
                        continue
                    }

                    // 处理当前目标点的移动
                    val target = route[routeStage]
                    val loc = locationManager?.let { MockServiceHelper.getLocation(it) } ?: break
                    val currentLat = loc.first
                    val currentLon = loc.second

                    val inverse = Geodesic.WGS84.Inverse(
                        currentLat,
                        currentLon,
                        target.first,
                        target.second
                    )
                    var azimuth = inverse.azi1
                    if (azimuth < 0) {
                        azimuth += 360
                    }

                    XLog.d("MockServiceViewModel", "route tick move bearing=$azimuth")
                    locationManager?.let { mgr ->
                        val routeSpeed = selectedRoute?.speedMetersPerSecond() ?: FakeLoc.speed
                        if (!MockServiceHelper.move(mgr, distancePerTick(routeSpeed, delayTime), azimuth)) {
                            XLog.e("MockServiceViewModel", "move failed")
                        } else {
                            replayCurrentLocation(mgr)
                        }
                    } ?: break
                }
            }
        }

        return rocker
    }

    suspend fun setLocationAndReplay(
        manager: LocationManager,
        latitude: Double,
        longitude: Double,
        forceReplay: Boolean = false,
    ): Boolean {
        if (!MockServiceHelper.setLocation(manager, latitude, longitude)) return false
        environmentReplay?.resolveAndApply(manager, latitude, longitude, forceReplay)
        return true
    }

    private suspend fun replayCurrentLocation(manager: LocationManager) {
        val now = System.currentTimeMillis()
        if (now - lastReplayCheckAt < 2_000) return
        lastReplayCheckAt = now
        val location = MockServiceHelper.getLocation(manager) ?: return
        environmentReplay?.resolveAndApply(manager, location.first, location.second)
    }

    fun isServiceStart(): Boolean {
        return locationManager?.let { mgr ->
            MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(mgr)
        } ?: false
    }

    override fun onCleared() {
        super.onCleared()
        rockerJob?.cancel()
        routeMockJob?.cancel()
        rockerCoroutineController.pause()
        routeMockCoroutine.pause()
        runCatching { rocker.hide() }
    }
}