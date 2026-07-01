package tv.kyshu.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
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
import tv.kyshu.ext.debug
import tv.kyshu.ext.disableFusedProvider
import tv.kyshu.ext.disableGetCurrentLocation
import tv.kyshu.ext.disableRegisterLocationListener
import tv.kyshu.ext.disableWifiScan
import tv.kyshu.ext.hookSensor
import tv.kyshu.ext.minSatelliteCount
import tv.kyshu.ext.needDowngradeToCdma
import tv.kyshu.ext.needOpenSELinux
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
import tv.kyshu.ui.viewmodel.MockServiceViewModel
import tv.kyshu.ui.viewmodel.SettingsViewModel

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

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
        binding.selinuxSwitch.isChecked = context.needOpenSELinux
        binding.selinuxSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.needOpenSELinux = isChecked
                showToast(if (isChecked) "已开启SELinux" else "已关闭SELinux")
            }
        })

        binding.altitudeValue.text = "%.2f米".format(context.altitude)
        binding.speedValue.text = "%.2f米/秒".format(context.speed)
        binding.accuracyValue.text = "%.2f米".format(context.accuracy)
        binding.reportDurationValue.text = "%dms".format(context.reportDuration)
        binding.satelliteCountValue.text = "%d颗".format(context.minSatelliteCount)

        binding.altitudeLayout.setOnClickListener {
            showDialog("设置海拔高度", binding.altitudeValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast("海拔高度不合法")
                    return@showDialog
                } else if (value > 10000) {
                    showToast("海拔高度不能超过10000米")
                    return@showDialog
                }
                context.altitude = value
                binding.altitudeValue.text = "%.2f米".format(value)
            }
        }

        binding.speedLayout.setOnClickListener {
            showDialog("设置速度", binding.speedValue.text.toString().let { it.substring(0, it.length - 3) }) {
                val value = it.toDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast("速度不合法")
                    return@showDialog
                } else if (value > 1000) {
                    showToast("速度不能超过1000米/秒")
                    return@showDialog
                }
                context.speed = value
                binding.speedValue.text = "%.2f米/秒".format(value)
            }
        }

        binding.accuracyLayout.setOnClickListener {
            showDialog("设置精度", binding.accuracyValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toFloatOrNull()
                if (value == null || value < 0.0) {
                    Toast.makeText(context, "精度不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "精度不能超过1000米", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.accuracy = value
                binding.accuracyValue.text = "%.2f米".format(value)
            }
        }

        binding.debugSwitch.isChecked = context.debug
        binding.debugSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean
            ) {
                context.debug = isChecked
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
                binding.reportDurationValue.text = "%dms".format(value)
                showToast("重新启动APP生效")
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
                binding.satelliteCountValue.text = "%d颗".format(value)
                showToast("重新启动模拟生效")
                updateRemoteConfig()
            }
        }

        binding.disableWlanScanSwitch.isChecked = requireContext().disableWifiScan
        binding.disableWlanScanSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().disableWifiScan = isChecked
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
            showToast(if (isChecked) "已开启WiFi劫持" else "已关闭WiFi劫持")
            updateRemoteConfig()
        }

        binding.mockSsidValue.text = context.mockSSID
        binding.mockSsidLayout.setOnClickListener {
            showDialog("伪造SSID", binding.mockSsidValue.text.toString()) {
                if (it.isBlank()) { showToast("SSID不能为空"); return@showDialog }
                context.mockSSID = it
                binding.mockSsidValue.text = it
                updateRemoteConfig()
            }
        }

        binding.mockBssidValue.text = context.mockBSSID
        binding.mockBssidLayout.setOnClickListener {
            showDialog("伪造BSSID", binding.mockBssidValue.text.toString()) {
                if (it.isBlank()) { showToast("BSSID不能为空"); return@showDialog }
                context.mockBSSID = it
                binding.mockBssidValue.text = it
                updateRemoteConfig()
            }
        }

        binding.mockMacValue.text = context.mockMac
        binding.mockMacLayout.setOnClickListener {
            showDialog("伪造MAC地址", binding.mockMacValue.text.toString()) {
                if (it.isBlank()) { showToast("MAC不能为空"); return@showDialog }
                context.mockMac = it
                binding.mockMacValue.text = it
                updateRemoteConfig()
            }
        }

        binding.mockIpValue.text = context.mockIp
        binding.mockIpLayout.setOnClickListener {
            showDialog("伪造IP地址", binding.mockIpValue.text.toString()) {
                if (it.isBlank()) { showToast("IP不能为空"); return@showDialog }
                context.mockIp = it
                binding.mockIpValue.text = it
                updateRemoteConfig()
            }
        }

        binding.mockWifiScanCountValue.text = context.mockWifiScanCount.toString()
        binding.mockWifiScanCountLayout.setOnClickListener {
            showDialog("伪造WiFi扫描数量", binding.mockWifiScanCountValue.text.toString()) {
                val v = it.toIntOrNull()
                if (v == null || v < 1) { showToast("数量必须 >= 1"); return@showDialog }
                context.mockWifiScanCount = v
                binding.mockWifiScanCountValue.text = v.toString()
                updateRemoteConfig()
            }
        }

        // ===================== 传感器模拟绑定 =====================

        binding.stepCountMockSwitch.isChecked = context.enableStepCountMock
        binding.stepCountMockSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.enableStepCountMock = isChecked
            showToast(if (isChecked) "已开启计步模拟" else "已关闭计步模拟")
            updateRemoteConfig()
        }

        binding.stepCountValue.text = context.stepCount.toString()
        binding.stepCountLayout.setOnClickListener {
            showDialog("目标步数", binding.stepCountValue.text.toString()) {
                val v = it.toIntOrNull()
                if (v == null || v < 0) { showToast("步数必须 >= 0"); return@showDialog }
                context.stepCount = v
                binding.stepCountValue.text = v.toString()
                updateRemoteConfig()
            }
        }

        binding.shakeMockSwitch.isChecked = context.enableShakeMock
        binding.shakeMockSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.enableShakeMock = isChecked
            showToast(if (isChecked) "已开启摇一摇模拟" else "已关闭摇一摇模拟")
            updateRemoteConfig()
        }

        binding.keepWalkingWaveformSwitch.isChecked = context.keepWalkingWaveform
        binding.keepWalkingWaveformSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.keepWalkingWaveform = isChecked
            showToast(if (isChecked) "已开启持续步行波形" else "已关闭持续步行波形")
            updateRemoteConfig()
        }

        // ===================== 步态参数绑定 =====================

        binding.gaitFreqValue.text = "%.1f".format(context.gaitFreq)
        binding.gaitFreqLayout.setOnClickListener {
            showDialog("步态频率(Hz)", binding.gaitFreqValue.text.toString()) {
                val v = it.toFloatOrNull()
                if (v == null || v <= 0f) { showToast("频率必须 > 0"); return@showDialog }
                context.gaitFreq = v
                binding.gaitFreqValue.text = "%.1f".format(v)
                updateRemoteConfig()
            }
        }

        binding.gaitAmpScaleValue.text = "%.1f".format(context.gaitAmpScale)
        binding.gaitAmpScaleLayout.setOnClickListener {
            showDialog("振幅缩放", binding.gaitAmpScaleValue.text.toString()) {
                val v = it.toFloatOrNull()
                if (v == null || v <= 0f) { showToast("缩放必须 > 0"); return@showDialog }
                context.gaitAmpScale = v
                binding.gaitAmpScaleValue.text = "%.1f".format(v)
                updateRemoteConfig()
            }
        }

        // ===================== 其他绑定 =====================

        binding.hideBaseStationSwitch.isChecked = context.hideBaseStation
        binding.hideBaseStationSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.hideBaseStation = isChecked
            showToast(if (isChecked) "已屏蔽基站信息" else "已允许基站信息")
            updateRemoteConfig()
        }

        binding.loopBroadcastLocationSwitch.isChecked = context.loopBroadcastLocation
        binding.loopBroadcastLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.loopBroadcastLocation = isChecked
            showToast(if (isChecked) "已开启反定位复原" else "已关闭反定位复原")
            updateRemoteConfig()
        }

        binding.hideMockSwitch.isChecked = context.hideMock
        binding.hideMockSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.hideMock = isChecked
            showToast(if (isChecked) "已隐藏模拟标记" else "已显示模拟标记")
            updateRemoteConfig()
        }

        binding.disableNetworkLocationSwitch.isChecked = context.disableNetworkLocation
        binding.disableNetworkLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.disableNetworkLocation = isChecked
            showToast(if (isChecked) "已禁用网络定位" else "已启用网络定位")
            updateRemoteConfig()
        }

        binding.speedAmplitudeValue.text = "%.1f".format(context.speedAmplitude)
        binding.speedAmplitudeLayout.setOnClickListener {
            showDialog("速度振幅", binding.speedAmplitudeValue.text.toString()) {
                val v = it.toDoubleOrNull()
                if (v == null || v < 0.0) { showToast("振幅必须 >= 0"); return@showDialog }
                context.speedAmplitude = v
                binding.speedAmplitudeValue.text = "%.1f".format(v)
                updateRemoteConfig()
            }
        }

        return root
    }

    private fun showToast(message: String) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRemoteConfig() {
        val context = requireContext()
        with(mockServiceViewModel) {
            locationManager?.let { mgr ->
                if(!MockServiceHelper.putConfig(mgr, context)) {
                    showToast("更新远程配置失败")
                } else {
                    showToast("同步配置成功")
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
        _binding = null
    }
}