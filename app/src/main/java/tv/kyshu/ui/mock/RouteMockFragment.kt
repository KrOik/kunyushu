package tv.kyshu.ui.mock

import dagger.hilt.android.AndroidEntryPoint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.kyshu.MainActivity
import tv.kyshu.R
import tv.kyshu.android.widget.RockerView
import tv.kyshu.android.window.OverlayUtils
import tv.kyshu.ext.altitude
import tv.kyshu.ext.drawOverOtherAppsEnabled
import tv.kyshu.ext.jsonHistoricalRoutes
import tv.kyshu.ext.selectRoute
import tv.kyshu.ext.speed
import tv.kyshu.map.RoutePlanningClient
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.compose.portalComposeView
import tv.kyshu.ui.viewmodel.MockServiceViewModel
import tv.kyshu.xposed.utils.FakeLoc

@AndroidEntryPoint
class RouteMockFragment : Fragment() {
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()
    private var routes: List<HistoricalRoute> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        configureRocker()
        routes = loadRoutes()
        return portalComposeView { RouteMockScreen() }
    }

    private fun configureRocker() {
        with(mockServiceViewModel) {
            rocker.setRockerListener(object : RockerView.Companion.OnMoveListener {
                override fun onAngle(angle: Double) {
                    MockServiceHelper.setBearing(locationManager ?: return, angle)
                    FakeLoc.bearing = angle
                    FakeLoc.hasBearings = true
                }

                override fun onLockChanged(isLocked: Boolean) {
                    isRockerLocked = isLocked
                }

                override fun onFinished() {
                    if (!isRockerLocked) rockerCoroutineController.pause()
                }

                override fun onStarted() {
                    rockerCoroutineController.resume()
                }
            })
            rocker.setRockerAutoListener(object : Rocker.Companion.OnAutoListener {
                override fun onAutoPlay(isPlay: Boolean) {
                    if (isPlay) routeMockCoroutine.resume() else routeMockCoroutine.pause()
                }

                override fun onAutoLock(isLock: Boolean) = Unit
            })
        }
    }

    private fun loadRoutes(): List<HistoricalRoute> {
        var raw = requireContext().jsonHistoricalRoutes
        if (raw.isBlank()) {
            val defaultRoute = HistoricalRoute(
                name = "默认路线",
                route = listOf(39.908822 to 116.397465, 39.907951 to 116.397500),
            )
            requireContext().jsonHistoricalRoutes = JSON.toJSONString(listOf(defaultRoute))
            raw = requireContext().jsonHistoricalRoutes
        }
        return runCatching {
            JSON.parseArray(raw, HistoricalRoute::class.java).sortedBy { it.name }
        }.getOrDefault(emptyList())
    }

    @Composable
    private fun RouteMockScreen() {
        val context = requireContext()
        var query by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(mockServiceViewModel.selectedRoute ?: context.selectRoute) }
        var visibleRoutes by remember { mutableStateOf(routes) }
        var mocking by remember { mutableStateOf(mockServiceViewModel.isServiceStart()) }
        var rockerVisible by remember { mutableStateOf(mockServiceViewModel.rocker.isStart) }
        var busy by remember { mutableStateOf(false) }
        var deleteRoute by remember { mutableStateOf<HistoricalRoute?>(null) }
        val filteredRoutes = visibleRoutes.filter { it.name.contains(query, ignoreCase = true) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("路线模拟", style = MaterialTheme.typography.headlineSmall)
            Text(
                "选择路线后启动模拟；浮动摇杆中的自动按钮可控制沿路线移动。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("目标路线", style = MaterialTheme.typography.titleMedium)
                    Text(
                        selected?.name ?: "请选择一条路线",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        selected?.let {
                            "${routeSummary(it)}\n${it.route.size} 个途经点"
                        } ?: "从历史路线列表中选择目标",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                if (mocking) {
                                    tryCloseService { success ->
                                        if (success) {
                                            mocking = false
                                            rockerVisible = mockServiceViewModel.rocker.isStart
                                        }
                                        busy = false
                                    }
                                } else {
                                    tryOpenService { success ->
                                        if (success) mocking = true
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (mocking) "停止模拟" else "启动模拟")
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { (activity as? MainActivity)?.openRouteEditor() },
                            modifier = Modifier.weight(1f),
                        ) { Text("创建路线") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("移动摇杆", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "需要先启动模拟并授予悬浮窗权限",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = rockerVisible,
                            onCheckedChange = { visible ->
                                if (!mocking) {
                                    showToast("请先启动模拟")
                                } else if (!context.drawOverOtherAppsEnabled()) {
                                    showToast("请授权悬浮窗权限")
                                } else {
                                    setRockerVisible(visible)
                                    rockerVisible = visible
                                }
                            },
                        )
                    }
                }
            }

            Text("历史路线", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索路线") },
                singleLine = true,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    filteredRoutes.forEach { route ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(route.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${routeSummary(route)} · ${route.route.size} 个点",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                OutlinedButton(onClick = {
                                    selectRoute(route)
                                    selected = route
                                }) { Text("选择") }
                                TextButton(onClick = { deleteRoute = route }) { Text("删除") }
                            }
                        }
                    }
                    if (filteredRoutes.isEmpty()) {
                        Text("没有匹配的路线", modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }

        deleteRoute?.let { route ->
            AlertDialog(
                onDismissRequest = { deleteRoute = null },
                title = { Text("删除路线") },
                text = { Text("确定要删除路线“${route.name}”吗？") },
                dismissButton = {
                    TextButton(onClick = { deleteRoute = null }) { Text("取消") }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val remaining = visibleRoutes.filterNot { it.name == route.name }
                        context.jsonHistoricalRoutes = JSON.toJSONString(remaining)
                        visibleRoutes = remaining
                        if (selected?.name == route.name) {
                            selected = null
                            mockServiceViewModel.selectedRoute = null
                            context.selectRoute = null
                        }
                        deleteRoute = null
                        showToast("已删除路线")
                    }) { Text("删除") }
                },
            )
        }
    }

    private fun selectRoute(route: HistoricalRoute) {
        mockServiceViewModel.selectedRoute = route
        requireContext().selectRoute = route
        mockServiceViewModel.locationManager?.let { manager ->
            if (MockServiceHelper.isMockStart(manager) && route.route.isNotEmpty()) {
                val first = route.route.first()
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = mockServiceViewModel.setLocationAndReplay(
                        manager,
                        first.first,
                        first.second,
                        forceReplay = true,
                    )
                    showToast(if (success) "位置更新成功" else "更新位置失败")
                }
            }
        }
    }

    private data class RouteActionResult(val success: Boolean, val message: String)

    private fun tryOpenService(onResult: (Boolean) -> Unit) {
        if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
            showToast("请授权悬浮窗权限")
            onResult(false)
            return
        }
        val selectedRoute = mockServiceViewModel.selectedRoute ?: run {
            showToast("请选择一条路线")
            onResult(false)
            return
        }
        val manager = mockServiceViewModel.locationManager ?: run {
            showToast("定位服务加载异常")
            onResult(false)
            return
        }
        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            onResult(false)
            return
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var routeToRun = selectedRoute
                var routeNotice: String? = null
                if (selectedRoute.useRoadRoute) {
                    val planned = RoutePlanningClient(requireContext()).plan(selectedRoute)
                    if (planned.isSuccess) {
                        val value = planned.getOrThrow()
                        routeToRun = selectedRoute.copy(
                            route = value.points,
                            waypointWaitSeconds = value.waits,
                        )
                        mockServiceViewModel.selectedRoute = routeToRun
                        routeNotice = "已使用 ${value.engine.name} 规划真实道路"
                    } else {
                        routeNotice = "道路规划不可用，继续使用手绘路线：${planned.exceptionOrNull()?.message.orEmpty()}"
                    }
                }
                if (routeToRun.route.isEmpty()) {
                    return@withContext RouteActionResult(false, "路线没有可用的途经点")
                }
                if (!MockServiceHelper.tryOpenMock(
                        manager,
                        requireContext().speed,
                        requireContext().altitude,
                        FakeLoc.accuracy,
                    )
                ) {
                    return@withContext RouteActionResult(false, "模拟服务启动失败")
                }
                MockServiceHelper.setSpeed(manager, routeToRun.speedMetersPerSecond().toFloat())
                val first = routeToRun.route.first()
                val positioned = mockServiceViewModel.setLocationAndReplay(
                    manager,
                    first.first,
                    first.second,
                    forceReplay = true,
                )
                RouteActionResult(
                    positioned,
                    routeNotice ?: if (positioned) "路线起点位置已更新" else "更新位置失败",
                )
            }
            showToast(result.message)
            onResult(result.success)
        }
    }

    private fun tryCloseService(onResult: (Boolean) -> Unit) {
        val manager = mockServiceViewModel.locationManager ?: run {
            showToast("定位服务加载异常")
            onResult(false)
            return
        }
        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            onResult(false)
            return
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (!MockServiceHelper.isMockStart(manager)) {
                    RouteActionResult(false, "模拟服务未启动")
                } else if (MockServiceHelper.tryCloseMock(manager)) {
                    RouteActionResult(true, "路线模拟已停止")
                } else {
                    RouteActionResult(false, "模拟服务停止失败")
                }
            }
            if (result.success && mockServiceViewModel.rocker.isStart) {
                setRockerVisible(false)
                mockServiceViewModel.rockerCoroutineController.pause()
            }
            showToast(result.message)
            onResult(result.success)
        }
    }

    private fun setRockerVisible(visible: Boolean) {
        if (visible) {
            mockServiceViewModel.rocker.show()
            showToast("摇杆已显示")
        } else {
            runCatching { mockServiceViewModel.rocker.hide() }
            mockServiceViewModel.rockerCoroutineController.pause()
            showToast("摇杆已隐藏")
        }
    }

    private fun showToast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext().applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun routeSummary(route: HistoricalRoute): String =
        "${route.travelMode.name} · ${"%.1f".format(route.speedMetersPerSecond())} m/s" +
            if (route.useRoadRoute) " · 道路规划" else " · 手绘"
}

