package tv.kyshu.ui.settings

import dagger.hilt.android.AndroidEntryPoint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.kyshu.R
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.compose.components.PortalSection
import tv.kyshu.ui.compose.components.PortalSwitchRow
import tv.kyshu.ui.compose.portalComposeView
import tv.kyshu.ui.viewmodel.GaitAnalysisResult
import tv.kyshu.ui.viewmodel.MockServiceViewModel
import tv.kyshu.ui.viewmodel.SensorUiData
import tv.kyshu.ui.viewmodel.SensorViewModel
import java.util.Locale

@AndroidEntryPoint
class SensorSettingsFragment : Fragment() {
    private val viewModel: SensorViewModel by viewModels()
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var linearSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var orientationSensor: Sensor? = null
    private var isSensorDetailsExpanded = false

    @Suppress("DEPRECATION")
    private val orientationSensorType = Sensor.TYPE_ORIENTATION

    private val realListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            viewModel.onRealSensorChanged(event.sensor.type, event.values.copyOf(), event.timestamp)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val mockListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            viewModel.onMockSensorChanged(event.sensor.type, event.values.copyOf())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) updateSensorSampling()
        else showToast(getString(R.string.sensor_permission_needed_toast))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        linearSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        orientationSensor = sensorManager?.getDefaultSensor(orientationSensorType)
        return portalComposeView { SensorSettingsScreen() }
    }

    @Composable
    private fun SensorSettingsScreen() {
        val stepMock by viewModel.stepCountMockEnabled.observeAsState(false)
        val shakeMock by viewModel.shakeMockEnabled.observeAsState(false)
        val keepWaveform by viewModel.keepWalkingWaveformEnabled.observeAsState(false)
        val stepCount by viewModel.currentStepCount.observeAsState(0)
        val recordSeconds by viewModel.recordSeconds.observeAsState(10)
        val dozeInterval by viewModel.dozeSensorIntervalMs.observeAsState(200)
        val importPath by viewModel.gaitImportPath.observeAsState("")
        val recording by viewModel.isRecording.observeAsState(false)
        val analyzing by viewModel.isAnalyzing.observeAsState(false)
        val recordingMessage by viewModel.recordingMessage.observeAsState("")
        val analysis by viewModel.analysisResult.observeAsState()
        val realData by viewModel.realSensorData.observeAsState()
        val mockData by viewModel.mockSensorData.observeAsState()
        var detailsExpanded by remember { mutableStateOf(false) }
        var recordInput by remember(recordSeconds) { mutableStateOf(recordSeconds.toString()) }
        var intervalInput by remember(dozeInterval) { mutableStateOf(dozeInterval.toString()) }
        var pathInput by remember(importPath) { mutableStateOf(importPath) }
        var stepDialog by remember { mutableStateOf(false) }
        var analysisDialog by remember { mutableStateOf<GaitAnalysisResult?>(null) }

        LaunchedEffect(analysis) { if (analysis != null) analysisDialog = analysis }
        isSensorDetailsExpanded = detailsExpanded
        if (recording || analyzing) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("正在处理步态数据") },
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(recordingMessage.ifBlank { "请稍候…" })
                    }
                },
                confirmButton = {},
            )
        }
        analysisDialog?.let { result ->
            AlertDialog(
                onDismissRequest = { analysisDialog = null },
                title = { Text(if (result.error == null) "步态拟合完成" else "步态拟合失败") },
                text = {
                    Text(
                        result.error ?: buildString {
                            append("频率 %.2f Hz\n".format(Locale.US, result.frequency))
                            append(result.message)
                            if (result.exportPath.isNotBlank()) append("\n导出：${result.exportPath}")
                        },
                    )
                },
                confirmButton = { TextButton(onClick = { analysisDialog = null }) { Text("关闭") } },
            )
        }
        if (stepDialog) {
            EditNumberDialog(
                title = "设置目标步数",
                initial = stepCount.toString(),
                onDismiss = { stepDialog = false },
                onSave = { value ->
                    val count = value.toIntOrNull()
                    if (count == null || count !in 0..100_000) showToast("步数范围为 0–100000")
                    else { viewModel.setStepCount(count); syncRemoteConfig(); stepDialog = false }
                },
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("传感器模拟", style = MaterialTheme.typography.headlineSmall)
            Text(
                "配置步数、摇晃和步态参数，并实时查看真实传感器与注入数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PortalSection("传感器开关") {
                PortalSwitchRow("计步模拟", "向系统注入目标步数", stepMock, onCheckedChange = {
                    viewModel.setStepCountMock(it); syncRemoteConfig()
                })
                PortalSwitchRow("摇晃模拟", "模拟加速度变化", shakeMock, onCheckedChange = {
                    viewModel.setShakeMock(it); syncRemoteConfig()
                })
                PortalSwitchRow("保持步行波形", "让步态模拟保持连续的运动波形", keepWaveform, onCheckedChange = {
                    viewModel.setKeepWalkingWaveform(it); syncRemoteConfig()
                })
                SettingValueRow("目标步数", "注入的目标运动步数", "$stepCount 步", Modifier.clickable { stepDialog = true })
            }

            PortalSection("步态采集与拟合") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        recordInput,
                        { recordInput = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("录制时长（3–120 秒）") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        intervalInput,
                        { intervalInput = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("传感器间隔（40–2000 毫秒）") },
                        singleLine = true,
                    )
                    Button(
                        enabled = !recording && !analyzing,
                        onClick = {
                            val seconds = recordInput.toIntOrNull()
                            val interval = intervalInput.toIntOrNull()
                            if (seconds == null || seconds !in 3..120) showToast("录制时长范围为 3–120 秒")
                            else if (interval == null || interval !in 40..2000) showToast("传感器间隔范围为 40–2000 毫秒")
                            else {
                                viewModel.setRecordSeconds(seconds)
                                viewModel.setDozeSensorIntervalMs(interval)
                                syncRemoteConfig()
                                viewModel.startRecordingProcess()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("录制并拟合 ${recordSeconds}s") }
                    OutlinedTextField(
                        pathInput,
                        { pathInput = it; viewModel.setGaitImportPath(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("IMU 数据目录") },
                        singleLine = true,
                    )
                    OutlinedButton(
                        enabled = !recording && !analyzing,
                        onClick = {
                            if (pathInput.isBlank()) showToast(getString(R.string.gait_import_path_empty))
                            else viewModel.importAndAnalyzeGaitFromPath(pathInput)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("导入并拟合") }
                    Text(
                        analysis?.error ?: analysis?.message ?: recordingMessage.ifBlank { "尚未生成分析结果" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SensorStatusCard(realData, mockData, detailsExpanded) {
                detailsExpanded = !detailsExpanded
                isSensorDetailsExpanded = detailsExpanded
                updateSensorSampling()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
            ) {
                OutlinedButton(
                    onClick = { requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("授予运动识别权限") }
            }
        }
    }

    @Composable
    private fun SettingValueRow(title: String, description: String, value: String, modifier: Modifier = Modifier) {
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable
    private fun SensorStatusCard(real: SensorUiData?, mock: SensorUiData?, expanded: Boolean, onToggle: () -> Unit) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("传感器状态", style = MaterialTheme.typography.titleMedium)
                Text(sensorText("真实", real), style = MaterialTheme.typography.bodyMedium)
                Text(sensorText("模拟", mock), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onToggle, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (expanded) "收起详细数据" else "展开详细数据")
                }
                if (expanded) {
                    Text(sensorText("真实加速度", real, Sensor.TYPE_ACCELEROMETER), style = MaterialTheme.typography.bodyMedium)
                    Text(sensorText("模拟加速度", mock, Sensor.TYPE_ACCELEROMETER), style = MaterialTheme.typography.bodyMedium)
                    Text(sensorText("真实线性加速度", real, Sensor.TYPE_LINEAR_ACCELERATION), style = MaterialTheme.typography.bodyMedium)
                    Text(sensorText("模拟线性加速度", mock, Sensor.TYPE_LINEAR_ACCELERATION), style = MaterialTheme.typography.bodyMedium)
                    Text(sensorText("真实陀螺仪", real, Sensor.TYPE_GYROSCOPE), style = MaterialTheme.typography.bodyMedium)
                    Text(sensorText("模拟陀螺仪", mock, Sensor.TYPE_GYROSCOPE), style = MaterialTheme.typography.bodyMedium)
                    Text(sensorText("真实方向", real, orientationSensorType), style = MaterialTheme.typography.bodyMedium)
                    Text(sensorText("模拟方向", mock, orientationSensorType), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    private fun sensorText(prefix: String, data: SensorUiData?, expectedType: Int? = null): String {
        if (data == null || (expectedType != null && data.type != expectedType)) return "$prefix：暂无数据"
        return if (data.type == Sensor.TYPE_STEP_COUNTER) "$prefix：${data.stepCount} 步"
        else "$prefix：%.2f, %.2f, %.2f".format(Locale.US, data.x, data.y, data.z)
    }

    @Composable
    private fun EditNumberDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
        var value by remember(title, initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { OutlinedTextField(value, { value = it }, label = { Text("数值") }, singleLine = true) },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存") } },
        )
    }

    override fun onResume() {
        super.onResume()
        updateSensorSampling()
    }

    override fun onPause() {
        stopSensorSampling()
        super.onPause()
    }

    private fun safeRegisterListener(listener: SensorEventListener, sensor: Sensor, delay: Int) {
        runCatching { sensorManager?.registerListener(listener, sensor, delay) }
            .onFailure { runCatching { sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI) } }
    }

    private fun updateSensorSampling() {
        stopSensorSampling()
        val recording = viewModel.isRecording.value == true
        val shouldSampleMotion = isSensorDetailsExpanded || recording
        val baseDelay = if (recording) SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_UI
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        ) {
            stepSensor?.let {
                safeRegisterListener(realListener, it, SensorManager.SENSOR_DELAY_NORMAL)
                safeRegisterListener(mockListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        if (!shouldSampleMotion) return
        listOfNotNull(accelSensor, linearSensor, gyroSensor, gravitySensor).forEach {
            safeRegisterListener(realListener, it, baseDelay)
            safeRegisterListener(mockListener, it, baseDelay)
        }
        if (isSensorDetailsExpanded) orientationSensor?.let {
            safeRegisterListener(realListener, it, baseDelay)
            safeRegisterListener(mockListener, it, baseDelay)
        }
    }

    private fun stopSensorSampling() {
        sensorManager?.unregisterListener(realListener)
        sensorManager?.unregisterListener(mockListener)
    }

    private fun syncRemoteConfig(showSuccess: Boolean = false) {
        val appContext = context?.applicationContext ?: return
        val manager = mockServiceViewModel.locationManager ?: run {
            if (showSuccess) showToast(getString(R.string.sensor_remote_not_ready))
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { MockServiceHelper.putConfig(manager, appContext) }
            if (!ok) showToast(getString(R.string.sensor_remote_sync_failed))
            else if (showSuccess) showToast(getString(R.string.gait_analysis_loaded_toast))
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext().applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        stopSensorSampling()
        super.onDestroyView()
    }
}

