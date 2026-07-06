package tv.kyshu.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.kyshu.android.coro.CoroutineController
import tv.kyshu.android.coro.CoroutineRouteMock
import tv.kyshu.ext.Loc4j
import tv.kyshu.ext.accuracy
import tv.kyshu.ext.altitude
import tv.kyshu.ext.reportDuration
import tv.kyshu.ext.speed
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.mock.HistoricalLocation
import tv.kyshu.ui.mock.HistoricalRoute
import tv.kyshu.ui.mock.Rocker
import tv.kyshu.xposed.utils.FakeLoc
import net.sf.geographiclib.Geodesic

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private var rockerJob: Job? = null
    private var routeMockJob: Job? = null
    var isRockerLocked = false
    var routeStage = 0
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

    var isRouteStart = false

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

                    if (!isServiceStart()) continue
                    locationManager?.let { mgr ->
                        if (!MockServiceHelper.move(mgr, distancePerTick(FakeLoc.speed, delayTime), FakeLoc.bearing)) {
                            Log.e("MockServiceViewModel", "Failed to move")
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
                    if (!isServiceStart()) continue
                    // 如果是第0阶段，定位到第一个点
                    if (routeStage == 0) {
                        val route0 = selectedRoute?.route?.get(0) ?: break
                        locationManager?.let { mgr ->
                            MockServiceHelper.setLocation(mgr, route0.first, route0.second)
                        } ?: break
                        routeStage++
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
                                MockServiceHelper.setLocation(mgr, target.first, target.second)
                            } ?: break
                            routeStage++
                            transitionsThisTick++
                        } else if (inverse.s12 < distancePerTick(FakeLoc.speed, delayTime)) {
                            // 如果距离小于速度，直接移动到目标点
                            locationManager?.let { mgr ->
                                MockServiceHelper.setLocation(mgr, target.first, target.second)
                            } ?: break
                            routeStage++
                            transitionsThisTick++

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
                        break // 退出循环
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

                    Log.d("MockServiceViewModel", "从 $currentLat, $currentLon 移动到 ${target.first}, ${target.second}, 方位角: $azimuth")
                    locationManager?.let { mgr ->
                        if (!MockServiceHelper.move(mgr, distancePerTick(FakeLoc.speed, delayTime), azimuth)) {
                            Log.e("MockServiceViewModel", "移动失败")
                        }
                    } ?: break
                }
            }
        }

        return rocker
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
