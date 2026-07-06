package tv.kyshu.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.kyshu.R
import tv.kyshu.databinding.FragmentSensorSettingsBinding
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.viewmodel.MockServiceViewModel
import tv.kyshu.ui.viewmodel.SensorViewModel
import tv.kyshu.utils.GaitCaptureStore

class SensorSettingsFragment : Fragment() {
    private var _binding: FragmentSensorSettingsBinding? = null
    private val binding get() = _binding!!

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
    private var progressDialog: AlertDialog? = null
    private var progressMessageView: TextView? = null
    private var autoImportJob: Job? = null
    private var suppressImportPathWatcher = false
    private var lastAutoImportPath: String? = null

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
        if (granted) {
            binding.btnRequestPermission.visibility = View.GONE
            updateSensorSampling()
        } else {
            Toast.makeText(requireContext(), R.string.sensor_permission_needed_toast, Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSensorSettingsBinding.inflate(inflater, container, false)
        val context = requireContext()
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        linearSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        orientationSensor = sensorManager?.getDefaultSensor(orientationSensorType)

        setupUi()
        setupObservers()
        updatePermissionButton()
        scheduleAutoImport(viewModel.gaitImportPath.value.orEmpty(), immediate = true)
        return binding.root
    }

    private fun setupUi() {
        binding.stepCountMockSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.setStepCountMock(checked)
            syncRemoteConfig()
        }
        binding.keepWalkingWaveformSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.setKeepWalkingWaveform(checked)
            syncRemoteConfig()
        }
        binding.stepCountValueLayout.setOnClickListener {
            showDialog(getString(R.string.set_step_count), (viewModel.currentStepCount.value ?: 0).toString()) { raw ->
                val count = raw.toIntOrNull() ?: return@showDialog
                if (count !in 0..100_000) {
                    Toast.makeText(requireContext(), getString(R.string.step_count_limit_error, 100_000), Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                viewModel.setStepCount(count)
                syncRemoteConfig()
            }
        }
        binding.shakeMockSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.setShakeMock(checked)
            syncRemoteConfig()
        }
        binding.gaitRecordSecondsInput.addTextChangedListener {
            val value = it?.toString()?.trim()?.toIntOrNull()
            if (value == null) {
                binding.gaitRecordSecondsLayout.error = null
                return@addTextChangedListener
            }
            if (value !in 3..120) {
                binding.gaitRecordSecondsLayout.error = getString(R.string.sensor_record_seconds_range_error)
                return@addTextChangedListener
            }
            binding.gaitRecordSecondsLayout.error = null
            viewModel.setRecordSeconds(value)
        }
        binding.dozeSensorIntervalInput.addTextChangedListener {
            val value = it?.toString()?.trim()?.toIntOrNull()
            if (value == null) {
                binding.dozeSensorIntervalLayout.error = null
                return@addTextChangedListener
            }
            if (value !in 40..2000) {
                binding.dozeSensorIntervalLayout.error = getString(R.string.doze_sensor_interval_range_error)
                return@addTextChangedListener
            }
            binding.dozeSensorIntervalLayout.error = null
            viewModel.setDozeSensorIntervalMs(value)
            syncRemoteConfig()
        }
        binding.btnRecordGait.setOnClickListener {
            showToast("已开始录制准备")
            viewModel.startRecordingProcess()
        }
        binding.gaitImportPathInput.addTextChangedListener {
            if (suppressImportPathWatcher) return@addTextChangedListener
            val path = it?.toString().orEmpty()
            viewModel.setGaitImportPath(path)
            scheduleAutoImport(path)
        }
        binding.btnImportGait.setOnClickListener {
            analyzeImportPath(manual = true)
        }
        binding.btnRequestPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                showToast("正在请求运动权限")
                requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        binding.btnToggleSensorDetails.setOnClickListener {
            isSensorDetailsExpanded = !isSensorDetailsExpanded
            binding.sensorDetailsLayout.visibility = if (isSensorDetailsExpanded) View.VISIBLE else View.GONE
            binding.btnToggleSensorDetails.text = getString(
                if (isSensorDetailsExpanded) R.string.sensor_details_collapse else R.string.sensor_details_expand,
            )
            showToast(if (isSensorDetailsExpanded) "已展开传感器数据" else "已收起传感器数据")
            updateSensorSampling()
        }
    }

    private fun setupObservers() {
        viewModel.stepCountMockEnabled.observe(viewLifecycleOwner) {
            if (binding.stepCountMockSwitch.isChecked != it) binding.stepCountMockSwitch.isChecked = it
        }
        viewModel.shakeMockEnabled.observe(viewLifecycleOwner) {
            if (binding.shakeMockSwitch.isChecked != it) binding.shakeMockSwitch.isChecked = it
        }
        viewModel.keepWalkingWaveformEnabled.observe(viewLifecycleOwner) {
            if (binding.keepWalkingWaveformSwitch.isChecked != it) binding.keepWalkingWaveformSwitch.isChecked = it
        }
        viewModel.currentStepCount.observe(viewLifecycleOwner) {
            binding.stepCountValue.text = getString(R.string.step_count_unit, it)
        }
        viewModel.recordSeconds.observe(viewLifecycleOwner) {
            val text = getString(R.string.sensor_record_seconds_value, it)
            if (binding.gaitRecordSecondsInput.text.toString() != text) binding.gaitRecordSecondsInput.setText(text)
            binding.btnRecordGait.text = getString(R.string.gait_record_button, it)
        }
        viewModel.dozeSensorIntervalMs.observe(viewLifecycleOwner) {
            val text = it.toString()
            if (binding.dozeSensorIntervalInput.text.toString() != text) binding.dozeSensorIntervalInput.setText(text)
        }
        viewModel.gaitImportPath.observe(viewLifecycleOwner) {
            val text = it.orEmpty()
            if (binding.gaitImportPathInput.text.toString() != text) {
                suppressImportPathWatcher = true
                binding.gaitImportPathInput.setText(text)
                binding.gaitImportPathInput.setSelection(text.length)
                suppressImportPathWatcher = false
            }
        }
        viewModel.isRecording.observe(viewLifecycleOwner) { recording ->
            updateBusyUi(recording, viewModel.isAnalyzing.value == true)
            updateSensorSampling()
        }
        viewModel.isAnalyzing.observe(viewLifecycleOwner) { analyzing ->
            updateBusyUi(viewModel.isRecording.value == true, analyzing)
        }
        viewModel.recordingMessage.observe(viewLifecycleOwner) {
            if (!it.isNullOrBlank()) {
                if (viewModel.isRecording.value == true || viewModel.isAnalyzing.value == true) {
                    showProgressDialog(it)
                }
                progressMessageView?.text = it
                binding.gaitAnalysisResultValue.text = it
            }
        }
        viewModel.analysisResult.observe(viewLifecycleOwner) { result ->
            if (result.error != null) {
                binding.gaitAnalysisResultValue.text = result.error
                showToast(result.error)
                return@observe
            }
            binding.gaitAnalysisResultValue.text = buildString {
                append(getString(R.string.gait_analysis_loaded_summary, result.frequency))
                append('\n')
                append(result.message)
                if (result.exportPath.isNotBlank()) {
                    append('\n')
                    append(getString(R.string.gait_analysis_export_path, result.exportPath))
                }
            }
            syncRemoteConfig(showSuccess = true)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.recording_analysis_dialog_title)
                .setMessage(getString(R.string.recording_analysis_verify, result.frequency, result.message, result.exportPath))
                .setPositiveButton(R.string.gait_analysis_loaded_button, null)
                .show()
        }
        viewModel.realSensorData.observe(viewLifecycleOwner) { data ->
            when (data.type) {
                Sensor.TYPE_STEP_COUNTER -> binding.realStepText.text = getString(R.string.real_step_count, data.stepCount)
                Sensor.TYPE_ACCELEROMETER -> binding.realShakeText.text = getString(R.string.real_shake_status_format, data.x, data.y, data.z)
                Sensor.TYPE_LINEAR_ACCELERATION -> binding.realLinearText.text = getString(R.string.real_linear_status_format, data.x, data.y, data.z)
                Sensor.TYPE_GYROSCOPE -> binding.realGyroText.text = getString(R.string.real_gyro_status_format, data.x, data.y, data.z)
                orientationSensorType -> binding.realOrientationText.text = getString(R.string.real_orientation_status_format, data.x, data.y, data.z)
            }
        }
        viewModel.mockSensorData.observe(viewLifecycleOwner) { data ->
            when (data.type) {
                Sensor.TYPE_STEP_COUNTER -> binding.mockStepText.text = getString(R.string.mock_step_count, data.stepCount)
                Sensor.TYPE_ACCELEROMETER -> binding.mockShakeText.text = getString(R.string.mock_shake_status_format, data.x, data.y, data.z)
                Sensor.TYPE_LINEAR_ACCELERATION -> binding.mockLinearText.text = getString(R.string.mock_linear_status_format, data.x, data.y, data.z)
                Sensor.TYPE_GYROSCOPE -> binding.mockGyroText.text = getString(R.string.mock_gyro_status_format, data.x, data.y, data.z)
                orientationSensorType -> binding.mockOrientationText.text = getString(R.string.mock_orientation_status_format, data.x, data.y, data.z)
            }
        }
    }

    private fun updatePermissionButton() {
        binding.btnRequestPermission.visibility = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun showDialog(title: String, initialValue: String, onConfirm: (String) -> Unit) {
        val input = TextInputEditText(requireContext()).apply {
            setText(initialValue)
            setSelectAllOnFocus(true)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProgressDialog(message: String?) {
        if (progressDialog != null) {
            progressMessageView?.text = message.orEmpty()
            return
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }
        val messageView = TextView(requireContext()).apply {
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            text = message.orEmpty()
        }
        container.addView(ProgressBar(requireContext()).apply { isIndeterminate = true })
        container.addView(messageView)
        progressMessageView = messageView
        progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.recording_prepare_title)
            .setView(container)
            .setCancelable(false)
            .create()
            .apply { show() }
    }

    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
        progressMessageView = null
    }

    private fun updateBusyUi(recording: Boolean, analyzing: Boolean) {
        val busy = recording || analyzing
        if (busy) {
            showProgressDialog(viewModel.recordingMessage.value)
        } else {
            dismissProgressDialog()
        }
        binding.btnRecordGait.isEnabled = !busy
        binding.btnImportGait.isEnabled = !busy
    }

    private fun analyzeImportPath(manual: Boolean) {
        val path = binding.gaitImportPathInput.text?.toString().orEmpty()
        if (manual && path.isBlank()) {
            showToast(getString(R.string.gait_import_path_empty))
            return
        }
        if (path.isBlank()) return
        binding.gaitAnalysisResultValue.text = getString(R.string.gait_import_analyzing)
        if (manual) showToast(getString(R.string.gait_import_analyzing))
        viewModel.importAndAnalyzeGaitFromPath(path)
    }

    private fun scheduleAutoImport(path: String, immediate: Boolean = false) {
        autoImportJob?.cancel()
        val trimmed = path.trim()
        if (trimmed.isBlank() || trimmed == lastAutoImportPath) return
        autoImportJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!immediate) delay(700L)
            val resolved = GaitCaptureStore.resolveImportDirectory(trimmed, requireContext().packageName)
            val dir = resolved.directory ?: return@launch
            if (!GaitCaptureStore.hasRequiredCaptureFiles(dir)) return@launch
            lastAutoImportPath = dir.absolutePath
            binding.gaitAnalysisResultValue.text = getString(R.string.gait_import_auto_loading, dir.absolutePath)
            viewModel.importAndAnalyzeGaitFromPath(dir.absolutePath)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSensorSampling()
    }

    override fun onPause() {
        super.onPause()
        stopSensorSampling()
    }

    private fun safeRegisterListener(listener: SensorEventListener, sensor: Sensor, delay: Int) {
        runCatching {
            sensorManager?.registerListener(listener, sensor, delay)
        }.onFailure {
            runCatching { sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    private fun updateSensorSampling() {
        stopSensorSampling()

        val isRecording = viewModel.isRecording.value == true
        val shouldSampleMotion = isSensorDetailsExpanded || isRecording
        val baseDelay = if (isRecording) SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_UI
        val stepDelay = SensorManager.SENSOR_DELAY_NORMAL
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        ) {
            stepSensor?.let {
                safeRegisterListener(realListener, it, stepDelay)
                safeRegisterListener(mockListener, it, stepDelay)
            }
        }
        if (!shouldSampleMotion) return
        listOfNotNull(accelSensor, linearSensor, gyroSensor, gravitySensor).forEach {
            safeRegisterListener(realListener, it, baseDelay)
            safeRegisterListener(mockListener, it, baseDelay)
        }
        if (isSensorDetailsExpanded) {
            orientationSensor?.let {
                safeRegisterListener(realListener, it, baseDelay)
                safeRegisterListener(mockListener, it, baseDelay)
            }
        }
    }

    private fun stopSensorSampling() {
        sensorManager?.unregisterListener(realListener)
        sensorManager?.unregisterListener(mockListener)
    }

    private fun syncRemoteConfig(showSuccess: Boolean = false) {
        val manager = mockServiceViewModel.locationManager ?: run {
            if (showSuccess) showToast(getString(R.string.sensor_remote_not_ready))
            return
        }
        if (!MockServiceHelper.putConfig(manager, requireContext())) {
            showToast(getString(R.string.sensor_remote_sync_failed))
        } else if (showSuccess) {
            showToast(getString(R.string.gait_analysis_loaded_toast))
        }
    }

    private fun showToast(message: String) {
        val context = requireContext().applicationContext
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        autoImportJob?.cancel()
        stopSensorSampling()
        dismissProgressDialog()
        _binding = null
    }
}
