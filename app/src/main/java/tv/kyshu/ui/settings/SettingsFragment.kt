package tv.kyshu.ui.settings

import dagger.hilt.android.AndroidEntryPoint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.kyshu.BuildConfig
import tv.kyshu.ext.accuracy
import tv.kyshu.ext.altitude
import tv.kyshu.ext.altitudeJitter
import tv.kyshu.ext.debug
import tv.kyshu.ext.disableFusedProvider
import tv.kyshu.ext.disableGetCurrentLocation
import tv.kyshu.ext.disableNetworkLocation
import tv.kyshu.ext.disableRegisterLocationListener
import tv.kyshu.ext.disableWifiScan
import tv.kyshu.ext.dozeSensorIntervalMs
import tv.kyshu.ext.enableMockWifi
import tv.kyshu.ext.enableShakeMock
import tv.kyshu.ext.enableStepCountMock
import tv.kyshu.ext.gaitAmpScale
import tv.kyshu.ext.gaitFreq
import tv.kyshu.ext.gaitImportPath
import tv.kyshu.ext.gaitRecordSeconds
import tv.kyshu.ext.hideMock
import tv.kyshu.ext.hideBaseStation
import tv.kyshu.ext.hookSensor
import tv.kyshu.ext.hookWifi
import tv.kyshu.ext.keepWalkingWaveform
import tv.kyshu.ext.loopBroadcastLocation
import tv.kyshu.ext.maxSpeed
import tv.kyshu.ext.minSatelliteCount
import tv.kyshu.ext.mockBSSID
import tv.kyshu.ext.mockIp
import tv.kyshu.ext.mockMac
import tv.kyshu.ext.mockSSID
import tv.kyshu.ext.mockWifiScanCount
import tv.kyshu.ext.needDowngradeToCdma
import tv.kyshu.ext.reportDuration
import tv.kyshu.ext.speed
import tv.kyshu.ext.speedAmplitude
import tv.kyshu.ext.stepCount
import tv.kyshu.service.BlackBoxProbe
import tv.kyshu.service.MockDomainConfigPrefs
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.compose.components.PortalSection
import tv.kyshu.ui.compose.components.PortalSwitchRow
import tv.kyshu.ui.compose.portalComposeView
import tv.kyshu.ui.viewmodel.MockServiceViewModel
import tv.kyshu.ui.viewmodel.SensorViewModel
import tv.kyshu.xposed.utils.Log
import java.security.SecureRandom
import java.util.Locale

@AndroidEntryPoint
class SettingsFragment : Fragment() {
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()
    private val sensorViewModel by viewModels<SensorViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        portalComposeView { SettingsScreen() }

    @Composable
    private fun SettingsScreen() {
        val context = requireContext()
        val scope = rememberCoroutineScope()
        var altitude by remember { mutableStateOf(context.altitude) }
        var altitudeJitter by remember { mutableStateOf(context.altitudeJitter) }
        var speed by remember { mutableStateOf(context.speed) }
        var accuracy by remember { mutableStateOf(context.accuracy) }
        var reportDuration by remember { mutableStateOf(context.reportDuration) }
        var satelliteCount by remember { mutableStateOf(context.minSatelliteCount) }
        var debug by remember { mutableStateOf(context.debug) }
        var allowGetCurrent by remember { mutableStateOf(!context.disableGetCurrentLocation) }
        var allowRegisterListener by remember { mutableStateOf(!context.disableRegisterLocationListener) }
        var disableFused by remember { mutableStateOf(context.disableFusedProvider) }
        var downgradeCdma by remember { mutableStateOf(context.needDowngradeToCdma) }
        var hookSensor by remember { mutableStateOf(context.hookSensor) }
        var disableWifi by remember { mutableStateOf(context.disableWifiScan) }
        var hookWifi by remember { mutableStateOf(context.hookWifi) }
        var mockSsid by remember { mutableStateOf(context.mockSSID) }
        var mockBssid by remember { mutableStateOf(context.mockBSSID) }
        var mockMac by remember { mutableStateOf(context.mockMac) }
        var mockIp by remember { mutableStateOf(context.mockIp) }
        var wifiCount by remember { mutableStateOf(context.mockWifiScanCount) }
        var stepMock by remember { mutableStateOf(context.enableStepCountMock) }
        var targetSteps by remember { mutableStateOf(context.stepCount) }
        var shakeMock by remember { mutableStateOf(context.enableShakeMock) }
        var keepWaveform by remember { mutableStateOf(context.keepWalkingWaveform) }
        var gaitFrequency by remember { mutableStateOf(context.gaitFreq) }
        var gaitAmplitude by remember { mutableStateOf(context.gaitAmpScale) }
        var recordSeconds by remember { mutableStateOf(context.gaitRecordSeconds) }
        var importPath by remember { mutableStateOf(context.gaitImportPath) }
        var hideBaseStation by remember { mutableStateOf(context.hideBaseStation) }
        var loopBroadcast by remember { mutableStateOf(context.loopBroadcastLocation) }
        var hideMock by remember { mutableStateOf(context.hideMock) }
        var disableNetwork by remember { mutableStateOf(context.disableNetworkLocation) }
        var speedAmplitude by remember { mutableStateOf(context.speedAmplitude) }
        var editRequest by remember { mutableStateOf<EditRequest?>(null) }
        var showRecoveryDialog by remember { mutableStateOf(false) }
        var diagnostics by remember { mutableStateOf<BlackBoxProbe.Report?>(null) }
        var diagnosticsRunning by remember { mutableStateOf(false) }
        var currentStepCount by remember { mutableStateOf<Long?>(null) }

        fun sync() = syncRemoteConfig()
        fun edit(title: String, initial: String, validator: (String) -> String?) {
            editRequest = EditRequest(title, initial, validator)
        }
        fun setAltitude(raw: String): String? {
            val value = raw.toDoubleOrNull() ?: return "请输入数字"
            if (value !in 0.0..10_000.0) return "范围 0–10000 米"
            altitude = value; context.altitude = value; Log.dialogOp("Settings", "海拔高度", value.toString()); return null
        }
        fun setAltitudeJitter(raw: String): String? {
            val value = raw.toFloatOrNull() ?: return "请输入数字"
            if (value !in 0f..10f) return "范围 0–10 米"
            altitudeJitter = value; context.altitudeJitter = value; return null
        }
        fun setSpeed(raw: String): String? {
            val value = raw.toDoubleOrNull() ?: return "请输入数字"
            if (value !in 0.0..1000.0) return "范围 0–1000 米/秒"
            speed = value; context.speed = value; return null
        }
        fun setAccuracy(raw: String): String? {
            val value = raw.toFloatOrNull() ?: return "请输入数字"
            if (value !in 0f..1000f) return "范围 0–1000 米"
            accuracy = value; context.accuracy = value; return null
        }

        editRequest?.let { request ->
            EditValueDialog(request, onDismiss = { editRequest = null }) { raw ->
                val error = request.validator(raw)
                if (error == null) editRequest = null
                error
            }
        }
        if (showRecoveryDialog) {
            AlertDialog(
                onDismissRequest = { showRecoveryDialog = false },
                title = { Text("恢复安全模式") },
                text = { Text("仅在确认模块已修复后使用。请求有效期 5 分钟，必须重启手机后生效。") },
                dismissButton = { TextButton(onClick = { showRecoveryDialog = false }) { Text("取消") } },
                confirmButton = {
                    TextButton(onClick = {
                        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
                            .joinToString("") { "%02x".format(it) }
                        val now = System.currentTimeMillis()
                        context.getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE).edit()
                            .putString("safeRecoveryNonce", nonce)
                            .putLong("safeRecoveryIssuedAt", now)
                            .putLong("safeRecoveryExpiresAt", now + 5 * 60_000L)
                            .apply()
                        Log.buttonOp("Settings", "请求安全模式恢复")
                        showRecoveryDialog = false
                        toast("恢复请求已生成，请在 5 分钟内重启手机")
                    }) { Text("确认并生成") }
                },
            )
        }
        diagnostics?.let { report ->
            val reportText = buildString {
                appendLine("综合健康度：${report.overallHealth.name}")
                appendLine(report.summary)
                report.probes.forEach { appendLine("${if (it.passed) "✓" else "✕"} ${it.name}: ${it.detail}") }
            }
            AlertDialog(
                onDismissRequest = { diagnostics = null },
                title = { Text("系统诊断") },
                text = { Text(reportText) },
                dismissButton = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("诊断报告", reportText))
                        toast("诊断报告已复制到剪贴板")
                    }) { Text("复制") }
                },
                confirmButton = { TextButton(onClick = { diagnostics = null }) { Text("关闭") } },
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("综合设置", style = MaterialTheme.typography.headlineSmall)
            Text(
                "所有设置都会直接保存，并在服务可用时同步到系统注入端。点击值行可编辑数值。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PortalSection("控制面板") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("定位模拟：已启用\nGNSS / NMEA：已启用\n传感器：${if (hookSensor || stepMock || shakeMock) "已启用" else "未启用"}\nWi-Fi：${if (BuildConfig.ENABLE_WIFI_MOCK && (disableWifi || hookWifi)) "已启用" else "未启用"}\n基站：${if (hideBaseStation) "已启用" else "未启用"}\nBLE / NFC：按需配置", style = MaterialTheme.typography.bodyMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportConfig() }, modifier = Modifier.weight(1f)) { Text("复制配置") }
                        OutlinedButton(onClick = { importConfig(); sync() }, modifier = Modifier.weight(1f)) { Text("导入配置") }
                    }
                }
            }

            PortalSection("位置参数") {
                SettingValueRow("海拔高度", "模拟位置的基础海拔", "%.2f 米".format(Locale.US, altitude)) {
                    edit("设置海拔高度", altitude.toString()) { setAltitude(it) }
                }
                SettingValueRow("海拔抖动", "海拔上下抖动幅度", "%.1f 米".format(Locale.US, altitudeJitter)) {
                    edit("设置海拔抖动", altitudeJitter.toString()) { setAltitudeJitter(it) }
                }
                SettingValueRow("速度", "模拟位置移动速度", "%.2f 米/秒".format(Locale.US, speed)) {
                    edit("设置速度", speed.toString()) { setSpeed(it) }
                }
                SettingValueRow("精度", "向应用报告的位置精度", "%.2f 米".format(Locale.US, accuracy)) {
                    edit("设置精度", accuracy.toString()) { setAccuracy(it) }
                }
            }

            PortalSection("定位与 GNSS") {
                PortalSwitchRow("调试模式", "输出更多诊断日志", debug, onCheckedChange = { debug = it; context.debug = it; sync() })
                PortalSwitchRow("允许 getCurrentLocation", "允许应用通过该接口获取位置", allowGetCurrent, onCheckedChange = { allowGetCurrent = it; context.disableGetCurrentLocation = !it; sync() })
                PortalSwitchRow("允许注册位置监听", "允许应用注册持续位置回调", allowRegisterListener, onCheckedChange = { allowRegisterListener = it; context.disableRegisterLocationListener = !it; sync() })
                PortalSwitchRow("禁用 FusedProvider", "阻止融合定位提供者", disableFused, onCheckedChange = { disableFused = it; context.disableFusedProvider = it; sync() })
                PortalSwitchRow("CDMA 降级", "兼容部分旧设备的基站定位", downgradeCdma, onCheckedChange = { downgradeCdma = it; context.needDowngradeToCdma = it; sync() })
                PortalSwitchRow("传感器 Hook", "重启目标应用后生效", hookSensor, onCheckedChange = { hookSensor = it; context.hookSensor = it; sync() })
                SettingValueRow("上报间隔", "位置更新上报间隔", "$reportDuration ms") {
                    edit("设置上报间隔", reportDuration.toString()) {
                        val value = it.toIntOrNull() ?: return@edit "请输入整数"
                        if (value !in 0..1000) return@edit "范围 0–1000 ms"
                        reportDuration = value; context.reportDuration = value; null
                    }
                }
                SettingValueRow("最少卫星数量", "模拟的最少北斗卫星数量", "$satelliteCount 颗") {
                    edit("设置最少卫星数量", satelliteCount.toString()) {
                        val value = it.toIntOrNull() ?: return@edit "请输入整数"
                        if (value !in 0..35) return@edit "范围 0–35 颗"
                        satelliteCount = value; context.minSatelliteCount = value; null
                    }
                }
            }

            if (BuildConfig.ENABLE_WIFI_MOCK) {
                PortalSection("Wi-Fi 模拟") {
                    PortalSwitchRow("禁用 Wi-Fi 扫描", "一定程度解决位置被系统拉回（测试）", disableWifi, onCheckedChange = {
                        disableWifi = it; context.disableWifiScan = it; context.enableMockWifi = it; sync()
                    })
                    PortalSwitchRow("Wi-Fi Hook", "向应用返回配置的 Wi-Fi 信息", hookWifi, onCheckedChange = { hookWifi = it; context.hookWifi = it; sync() })
                    SettingValueRow("伪造 SSID", "返回给应用的网络名称", mockSsid) { edit("伪造 SSID", mockSsid) { mockSsid = it; context.mockSSID = it; sync(); null } }
                    SettingValueRow("伪造 BSSID", "返回给应用的接入点地址", mockBssid) { edit("伪造 BSSID", mockBssid) { if (!SettingsInputValidators.isOptionalMacAddress(it.trim())) "BSSID 格式不合法" else { mockBssid = it.trim(); context.mockBSSID = mockBssid; sync(); null } } }
                    SettingValueRow("伪造 MAC", "返回给应用的设备地址", mockMac) { edit("伪造 MAC 地址", mockMac) { if (!SettingsInputValidators.isOptionalMacAddress(it.trim())) "MAC 地址格式不合法" else { mockMac = it.trim(); context.mockMac = mockMac; sync(); null } } }
                    SettingValueRow("伪造 IP", "返回给应用的 IPv4 地址", mockIp) { edit("伪造 IP 地址", mockIp) { if (!SettingsInputValidators.isOptionalIpv4Address(it.trim())) "IPv4 地址格式不合法" else { mockIp = it.trim(); context.mockIp = mockIp; sync(); null } } }
                    SettingValueRow("扫描数量", "伪造 Wi-Fi 扫描结果数量", wifiCount.toString()) { edit("伪造 Wi-Fi 扫描数量", wifiCount.toString()) { val value = it.toIntOrNull() ?: return@edit "请输入整数"; if (value < 1) return@edit "数量必须大于 0"; wifiCount = value; context.mockWifiScanCount = value; sync(); null } }
                }
            }

            PortalSection("传感器与步态") {
                PortalSwitchRow("计步模拟", "启用目标步数注入", stepMock, onCheckedChange = { stepMock = it; context.enableStepCountMock = it; sync() })
                SettingValueRow("目标步数", "注入的步数值", targetSteps.toString()) { edit("目标步数", targetSteps.toString()) { val value = it.toIntOrNull() ?: return@edit "请输入整数"; if (value < 0) return@edit "步数必须大于等于 0"; targetSteps = value; context.stepCount = value; sync(); null } }
                PortalSwitchRow("摇晃模拟", "启用加速度变化模拟", shakeMock, onCheckedChange = { shakeMock = it; context.enableShakeMock = it; sync() })
                PortalSwitchRow("保持步行波形", "使用连续步行波形", keepWaveform, onCheckedChange = { keepWaveform = it; context.keepWalkingWaveform = it; sync() })
                SettingValueRow("步态频率", "步态模拟频率", "%.2f Hz".format(Locale.US, gaitFrequency)) { edit("步态频率", gaitFrequency.toString()) { val v = it.toFloatOrNull() ?: return@edit "请输入数字"; if (v !in 0f..10f) return@edit "范围 0–10 Hz"; gaitFrequency = v; context.gaitFreq = v; sync(); null } }
                SettingValueRow("步态幅度", "步态波形幅度比例", "%.2f".format(Locale.US, gaitAmplitude)) { edit("步态幅度", gaitAmplitude.toString()) { val v = it.toFloatOrNull() ?: return@edit "请输入数字"; if (v !in 0f..10f) return@edit "范围 0–10"; gaitAmplitude = v; context.gaitAmpScale = v; sync(); null } }
                SettingValueRow("录制时长", "步态拟合录制时间", "$recordSeconds 秒") { edit("录制时长", recordSeconds.toString()) { val v = it.toIntOrNull() ?: return@edit "请输入整数"; if (v !in 3..120) return@edit "范围 3–120 秒"; recordSeconds = v; context.gaitRecordSeconds = v; null } }
                SettingValueRow("导入目录", "步态 IMU 数据目录", importPath.ifBlank { "未配置" }) { edit("IMU 数据目录", importPath) { importPath = it; context.gaitImportPath = it; null } }
                OutlinedButton(onClick = { toast("请在“传感器设置”页面录制或导入步态数据") }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("打开步态处理页") }
            }

            PortalSection("其他位置行为") {
                PortalSwitchRow("隐藏基站", "隐藏真实基站信息", hideBaseStation, onCheckedChange = { hideBaseStation = it; context.hideBaseStation = it; sync() })
                PortalSwitchRow("循环广播位置", "持续广播模拟位置", loopBroadcast, onCheckedChange = { loopBroadcast = it; context.loopBroadcastLocation = it; sync() })
                PortalSwitchRow("隐藏模拟标识", "减少目标应用中的模拟位置提示", hideMock, onCheckedChange = { hideMock = it; context.hideMock = it; sync() })
                PortalSwitchRow("禁用网络定位", "阻止网络定位提供者", disableNetwork, onCheckedChange = { disableNetwork = it; context.disableNetworkLocation = it; sync() })
                SettingValueRow("速度振幅", "速度变化幅度", "%.1f".format(Locale.US, speedAmplitude)) { edit("速度振幅", speedAmplitude.toString()) { val v = it.toDoubleOrNull() ?: return@edit "请输入数字"; if (v < 0.0) return@edit "必须大于等于 0"; speedAmplitude = v; context.speedAmplitude = v; sync(); null } }
            }

            PortalSection("系统维护") {
                Button(
                    enabled = !diagnosticsRunning,
                    onClick = {
                        diagnosticsRunning = true
                        scope.launch {
                            diagnostics = withContext(Dispatchers.IO) {
                                BlackBoxProbe.runAll(context, mockServiceViewModel.locationManager)
                            }
                            diagnosticsRunning = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text(if (diagnosticsRunning) "诊断运行中…" else "运行系统诊断") }
                OutlinedButton(onClick = { showRecoveryDialog = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("请求安全模式恢复（需重启）") }
            }
        }
    }

    @Composable
    private fun SettingValueRow(title: String, description: String, value: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
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
    private fun EditValueDialog(request: EditRequest, onDismiss: () -> Unit, onSave: (String) -> String?) {
        var value by remember(request.title, request.initial) { mutableStateOf(request.initial) }
        var error by remember(request.title) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(request.title) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    singleLine = true,
                )
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = { TextButton(onClick = { error = onSave(value) }) { Text("保存") } },
        )
    }

    private data class EditRequest(val title: String, val initial: String, val validator: (String) -> String?)

    private fun syncRemoteConfig() {
        val appContext = context?.applicationContext ?: return
        val manager = mockServiceViewModel.locationManager ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            if (!MockServiceHelper.putConfig(manager, appContext)) {
                withContext(Dispatchers.Main) { toast("同步配置失败") }
            }
        }
    }

    private fun exportConfig() {
        val context = requireContext()
        runCatching { MockDomainConfigPrefs.exportJson(context) }.onSuccess { json ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(MockDomainConfigPrefs.PREF_KEY, json))
            toast("配置 JSON 已复制")
        }.onFailure { toast("导出配置失败：${it.message}") }
    }

    private fun importConfig() {
        val context = requireContext()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val json = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        if (json.isBlank()) { toast("剪贴板没有配置 JSON"); return }
        runCatching { MockDomainConfigPrefs.importJson(context, json) }
            .onSuccess { toast("配置 JSON 已导入：${it.length} 字符") }
            .onFailure { toast("导入配置失败：${it.message}") }
    }

    private fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
}

