package tv.kyshu.ui.settings

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.kyshu.R
import tv.kyshu.databinding.FragmentSettingsBinding
import tv.kyshu.ext.accuracy
import tv.kyshu.ext.altitude
import tv.kyshu.ext.altitudeJitter
import tv.kyshu.ext.debug
import tv.kyshu.ext.disableFusedProvider
import tv.kyshu.ext.disableGetCurrentLocation
import tv.kyshu.ext.disableRegisterLocationListener
import tv.kyshu.ext.disableWifiScan
import tv.kyshu.ext.enableMockWifi
import tv.kyshu.ext.hookSensor
import tv.kyshu.ext.minSatelliteCount
import tv.kyshu.ext.needDowngradeToCdma
import tv.kyshu.ext.reportDuration
import tv.kyshu.ext.speed
import tv.kyshu.ext.hookWifi
import tv.kyshu.ext.mockSSID
import tv.kyshu.ext.mockBSSID
import tv.kyshu.ext.mockMac
import tv.kyshu.ext.mockIp
import tv.kyshu.ext.mockWifiScanCount
import tv.kyshu.ext.enableStepCountMock
import tv.kyshu.ext.stepCount
import tv.kyshu.ext.enableShakeMock
import tv.kyshu.ext.keepWalkingWaveform
import tv.kyshu.ext.dozeSensorIntervalMs
import tv.kyshu.ext.gaitFreq
import tv.kyshu.ext.gaitAmpScale
import tv.kyshu.ext.hideBaseStation
import tv.kyshu.ext.loopBroadcastLocation
import tv.kyshu.ext.hideMock
import tv.kyshu.ext.disableNetworkLocation
import tv.kyshu.ext.speedAmplitude
import tv.kyshu.ext.minSpeed
import tv.kyshu.ext.maxSpeed
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.service.BlackBoxProbe
import tv.kyshu.xposed.utils.Log
import tv.kyshu.ui.viewmodel.MockServiceViewModel
import tv.kyshu.ui.viewmodel.SensorViewModel
import tv.kyshu.ui.viewmodel.SettingsViewModel
import java.util.Locale

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()
    private val sensorViewModel by viewModels<SensorViewModel>()
    private var sensorManager: SensorManager? = null

    private val imuRecordingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            sensorViewModel.onRealSensorChanged(event.sensor.type, event.values.copyOf(), event.timestamp)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val settingsViewModel =
            ViewModelProvider(this)[SettingsViewModel::class.java]

        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val context = requireContext()
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        binding.altitudeValue.text = formatUs("%.2f米", context.altitude)
        binding.speedValue.text = formatUs("%.2f米/秒", context.speed)
        binding.accuracyValue.text = formatUs("%.2f米", context.accuracy)
        binding.reportDurationValue.text = formatUs("%dms", context.reportDuration)
        binding.satelliteCountValue.text = formatUs("%d颗", context.minSatelliteCount)

        binding.altitudeLayout.setOnClickListener {
            showDialog("设置海拔高度", binding.altitudeValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toInputDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast("海拔高度不合法")
                    return@showDialog
                } else if (value > 10000) {
                    showToast("海拔高度不能超过10000米")
                    return@showDialog
                }
                context.altitude = value
                Log.dialogOp("Settings", "海拔高度", formatUs("%.2f米", value))
                binding.altitudeValue.text = formatUs("%.2f米", value)
            }
        }

        binding.altitudeJitterValue.text = formatUs("%.1f米", context.altitudeJitter)
        binding.altitudeJitterLayout.setOnClickListener {
            showDialog("设置海拔抖动", binding.altitudeJitterValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toInputFloatOrNull()
                if (value == null || value < 0.0f) {
                    showToast("海拔抖动必须 >= 0")
                    return@showDialog
                } else if (value > 10.0f) {
                    showToast("海拔抖动不能超过10米")
                    return@showDialog
                }
                context.altitudeJitter = value
                Log.dialogOp("Settings", "海拔抖动", formatUs("%.1f米", value))
                binding.altitudeJitterValue.text = formatUs("%.1f米", value)
            }
        }

        binding.speedLayout.setOnClickListener {
            showDialog("设置速度", binding.speedValue.text.toString().let { it.substring(0, it.length - 3) }) {
                val value = it.toInputDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast("速度不合法")
                    return@showDialog
                } else if (value > 1000) {
                    showToast("速度不能超过1000米/秒")
                    return@showDialog
                }
                context.speed = value
                Log.dialogOp("Settings", "速度", formatUs("%.2f米/秒", value))
                binding.speedValue.text = formatUs("%.2f米/秒", value)
            }
        }

        binding.accuracyLayout.setOnClickListener {
            showDialog("设置精度", binding.accuracyValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toInputFloatOrNull()
                if (value == null || value < 0.0) {
                    Toast.makeText(context, "精度不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "精度不能超过1000米", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.accuracy = value
                Log.dialogOp("Settings", "精度", formatUs("%.2f米", value))
                binding.accuracyValue.text = formatUs("%.2f米", value)
            }
        }

        binding.debugSwitch.isChecked = context.debug
        binding.debugSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.debug = isChecked
                Log.switchOp("Settings", "调试模式", isChecked)
                showToast(if (isChecked) "已开启调试模式" else "已关闭调试模式")
                updateRemoteConfig()
            }
        })

        binding.dgcSwitch.isChecked = !context.disableGetCurrentLocation
        binding.dgcSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.disableGetCurrentLocation = !isChecked
                Log.switchOp("Settings", "getCurrentLocation", !isChecked)
                showToast(if (!isChecked) "禁止应用使用该方法" else "已允许应用使用该方法")
                updateRemoteConfig()
            }
        })
        binding.rllSwitch.isChecked = !context.disableRegisterLocationListener
        binding.rllSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.disableRegisterLocationListener = !isChecked
                Log.switchOp("Settings", "registerLocationListener", !isChecked)
                showToast(if (!isChecked) "禁止应用使用该方法" else "已允许应用使用该方法")
                updateRemoteConfig()
            }
        })

        binding.dfusedSwitch.isChecked = context.disableFusedProvider
        binding.dfusedSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.disableFusedProvider = isChecked
                Log.switchOp("Settings", "FusedProvider", isChecked)
                showToast(if (isChecked) "已禁用FusedProvider" else "已启用FusedProvider")
                updateRemoteConfig()
            }
        })

        binding.cdmaSwitch.isChecked = context.needDowngradeToCdma
        binding.cdmaSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.needDowngradeToCdma = isChecked
                Log.switchOp("Settings", "CDMA降级", isChecked)
                showToast(if (isChecked) "已降级为CDMA" else "已取消降级为CDMA")
                updateRemoteConfig()
            }
        })

        binding.sensorHookSwitch.isChecked = context.hookSensor
        binding.sensorHookSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.hookSensor = isChecked
                Log.switchOp("Settings", "传感器Hook", isChecked)
                showToast("重新启动生效")
                updateRemoteConfig()
            }
        })

        binding.reportDurationLayout.setOnClickListener {
            showDialog("设置上报间隔", binding.reportDurationValue.text.toString().let {
                it.substring(0, it.length - 2)
            }) {
                val value = it.toIntOrNull()
                if (value == null || value < 0) {
                    Toast.makeText(context, "上报间隔不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "上报间隔不能大于1s", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.reportDuration = value
                Log.dialogOp("Settings", "上报间隔", formatUs("%dms", value))
                binding.reportDurationValue.text = formatUs("%dms", value)
            }
        }

        binding.satelliteCountLayout.setOnClickListener {
            showDialog("设置最小模拟卫星数量", binding.satelliteCountValue.text.toString().let {
                it.substring(0, it.length - 1)
            }) {
                val value = it.toIntOrNull()
                if (value == null || value < 0) {
                    Toast.makeText(context, "数量不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 35) {
                    Toast.makeText(context, "卫星数量不能超过35", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.minSatelliteCount = value
                Log.dialogOp("Settings", "卫星数量", formatUs("%d颗", value))
                binding.satelliteCountValue.text = formatUs("%d颗", value)
            }
        }

        binding.disableWlanScanSwitch.isChecked = requireContext().disableWifiScan
        binding.disableWlanScanSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().disableWifiScan = isChecked
            requireContext().enableMockWifi = isChecked
            Log.switchOp("Settings", "WLAN扫描", isChecked)
            with(mockServiceViewModel) {
                if (isChecked) {
                    locationManager?.let { mgr ->
                        if(!MockServiceHelper.startWifiMock(mgr)) {
                            showToast("禁用WLAN扫描失败: 无法连接到系统服务")
                        }
                    } ?: showToast("定位服务未就绪")
                } else {
                    locationManager?.let { mgr ->
                        if(!MockServiceHelper.stopWifiMock(mgr)) {
                            showToast("启用WLAN扫描失败: 无法连接到系统服务")
                        }
                    } ?: showToast("定位服务未就绪")
                }
            }
        }

        // ===================== WiFi模拟参数绑定 =====================

        binding.hookWifiSwitch.isChecked = context.hookWifi
        binding.hookWifiSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.hookWifi = isChecked
            Log.switchOp("Settings", "WiFi劫持", isChecked)
            showToast(if (isChecked) "已开启WiFi劫持" else "已关闭WiFi劫持")
        }

        binding.mockSsidValue.text = context.mockSSID
        binding.mockSsidLayout.setOnClickListener {
            showDialog("伪造SSID", binding.mockSsidValue.text.toString()) {
                context.mockSSID = it
                Log.dialogOp("Settings", "伪造SSID", it)
                binding.mockSsidValue.text = it
                updateRemoteConfig()
            }
        }

        binding.mockBssidValue.text = context.mockBSSID
        binding.mockBssidLayout.setOnClickListener {
            showDialog("伪造BSSID", binding.mockBssidValue.text.toString()) {
                context.mockBSSID = it
                Log.dialogOp("Settings", "伪造BSSID", it)
                binding.mockBssidValue.text = it
                updateRemoteConfig()
            }
        }

        binding.mockMacValue.text = context.mockMac
        binding.mockMacLayout.setOnClickListener {
            showDialog("伪造MAC地址", binding.mockMacValue.text.toString()) {
                context.mockMac = it
                Log.dialogOp("Settings", "伪造MAC", it)
                binding.mockMacValue.text = it
                updateRemoteConfig()
            }
        }

        binding.mockIpValue.text = context.mockIp
        binding.mockIpLayout.setOnClickListener {
            showDialog("伪造IP地址", binding.mockIpValue.text.toString()) {
                context.mockIp = it
                Log.dialogOp("Settings", "伪造IP", it)
                binding.mockIpValue.text = it
                updateRemoteConfig()
            }
        }

        binding.mockWifiScanCountValue.text = context.mockWifiScanCount.toString()
        binding.mockWifiScanCountLayout.setOnClickListener {
            showDialog("伪造WiFi扫描数量", binding.mockWifiScanCountValue.text.toString()) {
                val v = it.toIntOrNull()
                if (v == null || v < 1) { showToast("数量必须 >= 1"); return@showDialog }
                Log.dialogOp("Settings", "WiFi扫描数量", v.toString())
                context.mockWifiScanCount = v
                binding.mockWifiScanCountValue.text = v.toString()
                updateRemoteConfig()
            }
        }

        // ===================== 传感器模拟绑定 =====================

        binding.stepCountMockSwitch.isChecked = context.enableStepCountMock
        binding.stepCountMockSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.enableStepCountMock = isChecked
            Log.switchOp("Settings", "计步模拟", isChecked)
            showToast(if (isChecked) "已开启计步模拟" else "已关闭计步模拟")
            updateRemoteConfig()
        }

        binding.stepCountValue.text = context.stepCount.toString()
        binding.stepCountLayout.setOnClickListener {
            showDialog("目标步数", binding.stepCountValue.text.toString()) {
                val v = it.toIntOrNull()
                if (v == null || v < 0) { showToast("步数必须 >= 0"); return@showDialog }
                Log.dialogOp("Settings", "目标步数", v.toString())
                context.stepCount = v
                binding.stepCountValue.text = v.toString()
                updateRemoteConfig()
            }
        }

        // 当前注入步数（通过 syncConfig 获取）
        binding.currentStepCountValue.text = "查询中..."
        mockServiceViewModel.locationManager?.let { mgr ->
            kotlin.runCatching {
                val rely = MockServiceHelper.syncConfig(mgr)
                if (rely != null) {
                    val currentSteps = rely.getLong("current_step_count", 0L)
                    binding.currentStepCountValue.text = "${currentSteps}步"
                }
            }
        }


        binding.keepWalkingWaveformSwitch.isChecked = context.keepWalkingWaveform
        binding.keepWalkingWaveformSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.keepWalkingWaveform = isChecked
            Log.switchOp("Settings", "持续步行波形", isChecked)
            showToast(if (isChecked) "已开启持续步行波形" else "已关闭持续步行波形")
            updateRemoteConfig()
        }

        // ===================== 步态参数绑定 =====================

        binding.gaitFreqValue.text = formatUs("%.1f", context.gaitFreq)
        binding.gaitFreqLayout.setOnClickListener {
            showDialog("步态频率(Hz)", binding.gaitFreqValue.text.toString()) {
                val v = it.toInputFloatOrNull()
                if (v == null || v <= 0f) { showToast("频率必须 > 0"); return@showDialog }
                Log.dialogOp("Settings", "步态频率", formatUs("%.1fHz", v))
                context.gaitFreq = v
                binding.gaitFreqValue.text = formatUs("%.1f", v)
                updateRemoteConfig()
            }
        }

        binding.gaitAmpScaleValue.text = formatUs("%.1f", context.gaitAmpScale)
        binding.gaitAmpScaleLayout.setOnClickListener {
            showDialog("振幅缩放", binding.gaitAmpScaleValue.text.toString()) {
                val v = it.toInputFloatOrNull()
                if (v == null || v <= 0f) { showToast("缩放必须 > 0"); return@showDialog }
                Log.dialogOp("Settings", "振幅缩放", formatUs("%.1f", v))
                context.gaitAmpScale = v
                binding.gaitAmpScaleValue.text = formatUs("%.1f", v)
                updateRemoteConfig()
            }
        }
        setupGaitCaptureControls()

        // ===================== 其他绑定 =====================

        binding.hideBaseStationSwitch.isChecked = context.hideBaseStation
        binding.hideBaseStationSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.hideBaseStation = isChecked
            Log.switchOp("Settings", "屏蔽基站", isChecked)
            showToast(if (isChecked) "已屏蔽基站信息" else "已允许基站信息")
            updateRemoteConfig()
        }

        binding.loopBroadcastLocationSwitch.isChecked = context.loopBroadcastLocation
        binding.loopBroadcastLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.loopBroadcastLocation = isChecked
            Log.switchOp("Settings", "反定位复原", isChecked)
            showToast(if (isChecked) "已开启反定位复原" else "已关闭反定位复原")
            updateRemoteConfig()
        }

        binding.hideMockSwitch.isChecked = context.hideMock
        binding.hideMockSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.hideMock = isChecked
            Log.switchOp("Settings", "隐藏模拟标记", isChecked)
            showToast(if (isChecked) "已隐藏模拟标记" else "已显示模拟标记")
            updateRemoteConfig()
        }
        binding.disableNetworkLocationSwitch.isChecked = context.disableNetworkLocation
        binding.disableNetworkLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.disableNetworkLocation = isChecked
            Log.switchOp("Settings", "禁用网络定位", isChecked)
            showToast(if (isChecked) "已禁用网络定位" else "已启用网络定位")
            updateRemoteConfig()
        }

        binding.speedAmplitudeValue.text = formatUs("%.1f", context.speedAmplitude)
        binding.speedAmplitudeLayout.setOnClickListener {
            showDialog("速度振幅", binding.speedAmplitudeValue.text.toString()) {
                val v = it.toInputDoubleOrNull()
                if (v == null || v < 0.0) { showToast("振幅必须 >= 0"); return@showDialog }
                Log.dialogOp("Settings", "速度振幅", formatUs("%.1f", v))
                context.speedAmplitude = v
                binding.speedAmplitudeValue.text = formatUs("%.1f", v)
                updateRemoteConfig()
            }
        }
        // ===================== 系统诊断 =====================

        binding.runDiagnosticsBtn.setOnClickListener {
            Log.buttonOp("Settings", "运行系统诊断")
            val ctx = requireContext()
            val lm = mockServiceViewModel.locationManager

            lifecycleScope.launch(Dispatchers.IO) {
                val report = BlackBoxProbe.runAll(ctx, lm)

                launch(Dispatchers.Main) {
                    showDiagnosticsResult(report)
                }
            }
        }

        return root
    }

    private fun setupGaitCaptureControls() {
        binding.gaitRecordSecondsInput.setText((sensorViewModel.recordSeconds.value ?: 10).toString())
        binding.gaitRecordSecondsInput.addTextChangedListener {
            val value = it?.toString()?.trim()?.toIntOrNull() ?: return@addTextChangedListener
            if (value in 3..120) {
                sensorViewModel.setRecordSeconds(value)
                binding.gaitRecordSecondsLayout.error = null
            } else {
                binding.gaitRecordSecondsLayout.error = "范围 3-120 秒"
            }
        }

        binding.gaitImportPathInput.setText(sensorViewModel.gaitImportPath.value.orEmpty())
        binding.gaitImportPathInput.addTextChangedListener {
            sensorViewModel.setGaitImportPath(it?.toString().orEmpty())
        }

        binding.btnRecordGait.setOnClickListener {
            sensorViewModel.startRecordingProcess()
        }

        binding.btnImportGait.setOnClickListener {
            sensorViewModel.importAndAnalyzeGaitFromPath(binding.gaitImportPathInput.text?.toString().orEmpty())
        }

        sensorViewModel.recordSeconds.observe(viewLifecycleOwner) {
            val text = it.toString()
            if (binding.gaitRecordSecondsInput.text?.toString() != text) {
                binding.gaitRecordSecondsInput.setText(text)
            }
            binding.btnRecordGait.text = "录制并拟合 ${it}s"
        }

        sensorViewModel.gaitImportPath.observe(viewLifecycleOwner) {
            if (binding.gaitImportPathInput.text?.toString() != it) {
                binding.gaitImportPathInput.setText(it)
            }
        }

        sensorViewModel.isRecording.observe(viewLifecycleOwner) { recording ->
            if (recording) {
                registerImuRecordingSensors()
            } else {
                unregisterImuRecordingSensors()
            }
            binding.btnRecordGait.isEnabled = !recording
            binding.btnImportGait.isEnabled = !recording
        }

        sensorViewModel.recordingMessage.observe(viewLifecycleOwner) {
            if (it.isNotBlank()) binding.gaitAnalysisResultValue.text = it
        }

        sensorViewModel.analysisResult.observe(viewLifecycleOwner) { result ->
            if (result.error != null) {
                binding.gaitAnalysisResultValue.text = result.error
                showToast(result.error)
                return@observe
            }
            binding.gaitFreqValue.text = formatUs("%.2f", result.frequency)
            binding.gaitAnalysisResultValue.text = buildString {
                append(formatUs("频率 %.2fHz\n", result.frequency))
                append(result.message)
                if (result.exportPath.isNotBlank()) {
                    append("\n导出: ${result.exportPath}")
                }
            }
            showToast("步态拟合完成，正在同步配置")
            updateRemoteConfig()
        }
    }

    private fun registerImuRecordingSensors() {
        val manager = sensorManager ?: return
        manager.unregisterListener(imuRecordingListener)
        val delay = SensorManager.SENSOR_DELAY_GAME
        val types = intArrayOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GRAVITY,
        )
        types.forEach { type ->
            manager.getDefaultSensor(type)?.let { sensor ->
                runCatching { manager.registerListener(imuRecordingListener, sensor, delay) }
                    .onFailure { showToast("传感器注册失败: ${sensor.name}") }
            }
        }
    }

    private fun unregisterImuRecordingSensors() {
        sensorManager?.unregisterListener(imuRecordingListener)
    }

    private fun showToast(message: String) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatUs(pattern: String, vararg args: Any?): String {
        return String.format(Locale.US, pattern, *args)
    }

    private fun String.toInputDoubleOrNull(): Double? {
        return normalizeDecimalInput()?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun String.toInputFloatOrNull(): Float? {
        return normalizeDecimalInput()?.toFloatOrNull()?.takeIf { it.isFinite() }
    }

    private fun String.normalizeDecimalInput(): String? {
        val raw = trim()
        if (raw.isEmpty()) return null
        if (raw.contains('.')) return raw
        return if (raw.count { it == ',' } == 1) raw.replace(',', '.') else raw
    }

    private fun showDiagnosticsResult(report: BlackBoxProbe.Report) {
        val sb = StringBuilder()
        sb.appendLine("=== 系统诊断报告 ===")
        sb.appendLine()
        sb.appendLine("综合健康度: ${report.overallHealth.name}")
        sb.appendLine(report.summary)
        sb.appendLine()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("系统诊断")
            .setMessage(sb.toString())
            .setPositiveButton("关闭", null)
            .setNegativeButton("复制到剪贴板") { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("诊断报告", sb.toString()))
                showToast("诊断报告已复制到剪贴板")
            }
            .show()
    }


    private fun updateRemoteConfig() {
        val context = requireContext()
        with(mockServiceViewModel) {
            locationManager?.let { mgr ->
                if(!MockServiceHelper.putConfig(mgr, context)) {
                    showToast("更新远程配置失败")
                } else {
                    showToast("同步配置成功")
                    // 同步后刷新当前步数
                    kotlin.runCatching {
                        val rely = MockServiceHelper.syncConfig(mgr)
                        if (rely != null) {
                            val currentSteps = rely.getLong("current_step_count", 0L)
                            binding.currentStepCountValue.text = "${currentSteps}步"
                        }
                    }
                }
            } ?: showToast("定位服务未就绪")
        }
    }

    @SuppressLint("MissingInflatedId")
    private fun showDialog(titleText: String, valueText: String, handler: (String) -> Unit) {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_input, null)

        val title = dialogView.findViewById<TextView>(R.id.title)
        title.text = titleText

        val value = dialogView.findViewById<TextInputEditText>(R.id.value)
        value.setText(valueText)

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        builder
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                handler(value.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterImuRecordingSensors()
        _binding = null
    }
}
