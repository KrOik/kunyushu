package tv.kyshu.ui.environment

import dagger.hilt.android.AndroidEntryPoint
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.model.LatLng
import tv.kyshu.environment.EnvironmentCaptureService
import tv.kyshu.environment.EnvironmentDatabase
import tv.kyshu.environment.EnvironmentRepository
import tv.kyshu.environment.EnvironmentSettings
import tv.kyshu.ext.gcj02
import tv.kyshu.ui.compose.components.PortalSection
import tv.kyshu.ui.compose.components.PortalSwitchRow
import tv.kyshu.ui.compose.portalComposeView

@AndroidEntryPoint
class EnvironmentMapFragment : Fragment() {
    private lateinit var settings: EnvironmentSettings
    private lateinit var repository: EnvironmentRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.filterKeys { it in requiredPermissions() }.values.all { it }) {
            EnvironmentCaptureService.start(requireContext())
        } else {
            Toast.makeText(requireContext(), "环境采集需要位置及所选无线域权限", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = EnvironmentSettings(requireContext())
        repository = EnvironmentRepository(EnvironmentDatabase.get(requireContext()))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        portalComposeView { EnvironmentMapScreen() }

    @Composable
    private fun EnvironmentMapScreen() {
        val serviceState by EnvironmentCaptureService.serviceState.collectAsStateWithLifecycle()
        val samples by repository.observeAll().collectAsStateWithLifecycle(emptyList())
        var wifiReplay by remember { mutableStateOf(settings.wifiReplayEnabled) }
        var cellReplay by remember { mutableStateOf(settings.cellReplayEnabled) }
        var bleReplay by remember { mutableStateOf(settings.bleReplayEnabled) }
        var naturalDrift by remember { mutableStateOf(settings.naturalDriftEnabled) }
        var liveMapUpdate by remember { mutableStateOf(settings.liveMapUpdateEnabled) }
        var scanInterval by remember { mutableStateOf(settings.scanIntervalSeconds.toString()) }
        var replayRadius by remember { mutableStateOf(settings.replayRadiusMeters.toString()) }
        var mapView by remember { mutableStateOf<MapView?>(null) }

        LaunchedEffect(samples, mapView) {
            val map = mapView?.map ?: return@LaunchedEffect
            map.clear()
            samples.take(500).forEach { item ->
                val point = (item.sample.latitude to item.sample.longitude).gcj02
                map.addOverlay(
                    MarkerOptions()
                        .position(point)
                        .title(item.sample.label.ifBlank { item.sample.source }),
                )
            }
            samples.firstOrNull()?.let { item ->
                val point = (item.sample.latitude to item.sample.longitude).gcj02
                map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(point, 16f))
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    MapView(context).also {
                        it.onCreate(context, null)
                        mapView = it
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            DisposableEffect(mapView) {
                onDispose {
                    mapView?.onPause()
                    mapView?.onDestroy()
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().height(420.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("环境图谱", style = MaterialTheme.typography.headlineSmall)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            serviceState.message.ifBlank { if (serviceState.running) "采集中" else "采集未启动" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "本次 ${serviceState.savedSamples} 个样本 · Wi-Fi ${serviceState.wifiCount} · 基站 ${serviceState.cellCount} · BLE ${serviceState.bleCount}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = {
                        if (serviceState.running) EnvironmentCaptureService.stop(requireContext())
                        else permissionLauncher.launch(requiredPermissions())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (serviceState.running) "停止环境采集" else "开始环境采集") }

                PortalSection("环境回放") {
                    PortalSwitchRow("回放 Wi-Fi 环境", "使用附近样本恢复 Wi-Fi 连接与扫描结果", wifiReplay, onCheckedChange = {
                        wifiReplay = it; settings.wifiReplayEnabled = it
                    })
                    PortalSwitchRow("回放蜂窝环境", "使用附近样本恢复基站信息", cellReplay, onCheckedChange = {
                        cellReplay = it; settings.cellReplayEnabled = it
                    })
                    PortalSwitchRow("回放 BLE 环境", "使用附近样本恢复蓝牙设备信息", bleReplay, onCheckedChange = {
                        bleReplay = it; settings.bleReplayEnabled = it
                    })
                    PortalSwitchRow("自然位置漂移", "让回放位置产生轻微、连续的自然变化", naturalDrift, onCheckedChange = {
                        naturalDrift = it; settings.naturalDriftEnabled = it
                    })
                    PortalSwitchRow("地图拖动实时更新", "模拟运行时拖动地图即可更新目标位置", liveMapUpdate, onCheckedChange = {
                        liveMapUpdate = it; settings.liveMapUpdateEnabled = it
                    })
                }

                PortalSection("采集参数") {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.OutlinedTextField(
                            value = scanInterval,
                            onValueChange = { scanInterval = it.filter(Char::isDigit) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("扫描间隔（5–60 秒）") },
                            singleLine = true,
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = replayRadius,
                            onValueChange = { replayRadius = it.filter { c -> c.isDigit() || c == '.' } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("回放半径（5–500 米）") },
                            singleLine = true,
                        )
                        OutlinedButton(
                            onClick = {
                                settings.scanIntervalSeconds = scanInterval.toIntOrNull() ?: 10
                                settings.replayRadiusMeters = replayRadius.toFloatOrNull() ?: 50f
                                scanInterval = settings.scanIntervalSeconds.toString()
                                replayRadius = settings.replayRadiusMeters.toString()
                                Toast.makeText(requireContext(), "环境设置已保存", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("保存环境设置") }
                    }
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (settings.scanCell) add(Manifest.permission.READ_PHONE_STATE)
        if (settings.scanBle && Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (settings.scanWifi && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}

