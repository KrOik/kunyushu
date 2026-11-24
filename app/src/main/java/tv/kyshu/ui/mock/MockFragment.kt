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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.baidu.mapapi.map.BaiduMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.kyshu.ext.historicalLocations
import tv.kyshu.ext.rawHistoricalLocations
import tv.kyshu.ext.selectLocation
import tv.kyshu.ext.speed
import tv.kyshu.ext.altitude
import tv.kyshu.ext.drawOverOtherAppsEnabled
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.compose.portalComposeView
import tv.kyshu.ui.viewmodel.MockServiceViewModel
import tv.kyshu.xposed.utils.FakeLoc

@AndroidEntryPoint
class MockFragment : Fragment() {
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        configureRocker()
        return portalComposeView { MockScreen() }
    }

    private fun configureRocker() {
        with(mockServiceViewModel) {
            rocker.setRockerListener(object : tv.kyshu.android.widget.RockerView.Companion.OnMoveListener {
                override fun onAngle(angle: Double) {
                    MockServiceHelper.setBearing(locationManager ?: return, angle)
                    FakeLoc.bearing = angle
                    FakeLoc.hasBearings = true
                }
                override fun onLockChanged(isLocked: Boolean) { isRockerLocked = isLocked }
                override fun onFinished() { if (!isRockerLocked) rockerCoroutineController.pause() }
                override fun onStarted() { rockerCoroutineController.resume() }
            })
        }
    }

    @Composable
    private fun MockScreen() {
        val context = requireContext()
        val scope = rememberCoroutineScope()
        var locations by remember { mutableStateOf(context.historicalLocations.sortedBy { it.name }) }
        var query by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(mockServiceViewModel.selectedLocation ?: context.selectLocation) }
        var mocking by remember { mutableStateOf(mockServiceViewModel.isServiceStart()) }
        var rockerVisible by remember { mutableStateOf(mockServiceViewModel.rocker.isStart) }
        var busy by remember { mutableStateOf(false) }
        var deleteLocation by remember { mutableStateOf<HistoricalLocation?>(null) }
        val visibleLocations = locations.filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("位置模拟", style = MaterialTheme.typography.headlineSmall)
            Text("选择目标位置后启动模拟。摇杆会在获得悬浮窗权限后显示。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("目标位置", style = MaterialTheme.typography.titleMedium)
                    Text(selected?.name ?: "请选择一个位置", style = MaterialTheme.typography.titleLarge)
                    Text(selected?.let { "${it.address}\n%.6f, %.6f".format(it.lat, it.lon) } ?: "从历史位置列表中选择目标", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(enabled = !busy, onClick = {
                            busy = true
                            if (mocking) stopMock { if (it) mocking = false; busy = false } else startMock { mocking = it; busy = false }
                        }, modifier = Modifier.weight(1f)) { Text(if (mocking) "停止模拟" else "启动模拟") }
                        OutlinedButton(onClick = {
                            val manager = mockServiceViewModel.locationManager
                            if (manager == null) showToast("定位服务未就绪")
                            else showToast("Location${MockServiceHelper.getLocation(manager)}，监听器 ${MockServiceHelper.getLocationListenerSize(manager)} 个")
                        }, modifier = Modifier.weight(1f)) { Text("查看状态") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("移动摇杆", style = MaterialTheme.typography.bodyLarge)
                            Text("需要先启动模拟并授予悬浮窗权限", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(rockerVisible, onCheckedChange = { visible ->
                            if (!mocking) showToast("请先启动模拟")
                            else if (!requireContext().drawOverOtherAppsEnabled()) showToast("请授权悬浮窗权限")
                            else {
                                if (visible) mockServiceViewModel.rocker.show() else { mockServiceViewModel.rocker.hide(); mockServiceViewModel.rockerCoroutineController.pause() }
                                rockerVisible = visible
                            }
                        })
                    }
                }
            }
            Text("历史位置", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("搜索位置") }, singleLine = true)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    visibleLocations.forEach { location ->
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(location.name, style = MaterialTheme.typography.bodyLarge)
                                Text(location.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("%.6f, %.6f".format(location.lat, location.lon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                OutlinedButton(onClick = { selectLocation(location); selected = location }) { Text("选择") }
                                TextButton(onClick = { deleteLocation = location }) { Text("删除") }
                            }
                        }
                    }
                    if (visibleLocations.isEmpty()) Text("没有匹配的位置", modifier = Modifier.padding(16.dp))
                }
            }
        }
        deleteLocation?.let { location ->
            AlertDialog(
                onDismissRequest = { deleteLocation = null },
                title = { Text("删除位置") },
                text = { Text("确定要删除位置“${location.name}”吗？") },
                dismissButton = { TextButton(onClick = { deleteLocation = null }) { Text("取消") } },
                confirmButton = {
                    TextButton(onClick = {
                        context.rawHistoricalLocations = context.rawHistoricalLocations.filterNot { it.substringBefore(',') == location.name }.toSet()
                        locations = context.historicalLocations.sortedBy { it.name }
                        if (selected?.name == location.name) { selected = null; context.selectLocation = null; mockServiceViewModel.selectedLocation = null }
                        deleteLocation = null
                        showToast("已删除位置")
                    }) { Text("删除") }
                },
            )
        }
    }

    private fun selectLocation(location: HistoricalLocation) {
        mockServiceViewModel.selectedLocation = location
        requireContext().selectLocation = location
        mockServiceViewModel.locationManager?.let { manager ->
            if (MockServiceHelper.isMockStart(manager)) lifecycleScope.launch(Dispatchers.IO) {
                val success = mockServiceViewModel.setLocationAndReplay(manager, location.lat, location.lon, true)
                showToast(if (success) "位置更新成功" else "更新位置失败")
            }
        }
    }

    private fun startMock(onResult: (Boolean) -> Unit) {
        val location = mockServiceViewModel.selectedLocation ?: run { showToast("请选择一个位置"); onResult(false); return }
        val manager = mockServiceViewModel.locationManager ?: run { showToast("定位服务未就绪"); onResult(false); return }
        if (!MockServiceHelper.isServiceInit()) { showToast("系统服务注入失败"); onResult(false); return }
        val speed = requireContext().speed
        val altitude = requireContext().altitude
        lifecycleScope.launch {
            var active = true // Keep stop available when the remote outcome is unknown.
            try {
                val (ok, state) = withContext(Dispatchers.IO) {
                    val ok = mockServiceViewModel.setLocationAndReplay(manager, location.lat, location.lon, true) &&
                        MockServiceHelper.tryOpenMock(manager, speed, altitude, FakeLoc.accuracy) &&
                        MockServiceHelper.broadcastLocation(manager)
                    ok to MockServiceHelper.queryMockState(manager)
                }
                active = state != false
                showToast(when {
                    state == null -> "模拟状态未确认，请尝试停止或重新查看状态"
                    !state -> "模拟服务启动失败"
                    !ok -> "模拟已开启，但位置更新未确认"
                    else -> "位置模拟已启动"
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                tv.kyshu.xposed.utils.Log.e("MockFragment", "start mock failed", error)
                showToast("模拟状态未确认，请尝试停止")
            } finally {
                onResult(active)
            }
        }
    }

    private fun stopMock(onResult: (Boolean) -> Unit) {
        val manager = mockServiceViewModel.locationManager ?: run { showToast("定位服务未就绪"); onResult(false); return }
        lifecycleScope.launch {
            var ok = false
            try {
                ok = withContext(Dispatchers.IO) { MockServiceHelper.tryCloseMock(manager) }
                if (ok && mockServiceViewModel.rocker.isStart) { mockServiceViewModel.rocker.hide(); mockServiceViewModel.rockerCoroutineController.pause() }
                showToast(if (ok) "模拟服务已停止" else "停止未确认，请重试")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                tv.kyshu.xposed.utils.Log.e("MockFragment", "stop mock failed", error)
                showToast("停止未确认，请重试")
            } finally {
                onResult(ok)
            }
        }
    }

    private fun showToast(message: String) = lifecycleScope.launch(Dispatchers.Main) { Toast.makeText(requireContext().applicationContext, message, Toast.LENGTH_SHORT).show() }
}

