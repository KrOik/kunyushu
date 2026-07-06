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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import tv.kyshu.R
import tv.kyshu.databinding.FragmentSensorSettingsBinding
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.viewmodel.MockServiceViewModel
import tv.kyshu.ui.viewmodel.SensorViewModel

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
            viewModel.startRecordingProcess()
        }
        binding.gaitImportPathInput.addTextChangedListener {
            viewModel.setGaitImportPath(it?.toString().orEmpty())
        }
        binding.btnImportGait.setOnClickListener {
            viewModel.importAndAnalyzeGaitFromPath(binding.gaitImportPathInput.text?.toString().orEmpty())
        }
        binding.btnRequestPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        binding.btnToggleSensorDetails.setOnClickListener {
            isSensorDetailsExpanded = !isSensorDetailsExpanded
            binding.sensorDetailsLayout.visibility = if (isSensorDetailsExpanded) View.VISIBLE else View.GONE
            binding.btnToggleSensorDetails.text = getString(
                if (isSensorDetailsExpanded) R.string.sensor_details_collapse else R.string.sensor_details_expand,
            )
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
            if (binding.gaitImportPathInput.text.toString() != text) binding.gaitImportPathInput.setText(text)
        }
        viewModel.isRecording.observe(viewLifecycleOwner) { recording ->
            if (recording) showProgressDialog(viewModel.recordingMessage.value) else dismissProgressDialog()
            binding.btnRecordGait.isEnabled = !recording
            binding.btnImportGait.isEnabled = !recording
            updateSensorSampling()
        }
        viewModel.recordingMessage.observe(viewLifecycleOwner) {
            if (!it.isNullOrBlank()) {
                showProgressDialog(it)
                progressMessageView?.text = it
            }
        }
        viewModel.analysisResult.observe(viewLifecycleOwner) { result ->
            if (result.error != null) {
                Toast.makeText(requireContext(), result.error, Toast.LENGTH_SHORT).show()
                return@observe
            }
            syncRemoteConfig()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.recording_analysis_dialog_title)
                .setMessage(getString(R.string.recording_analysis_verify, result.frequency, result.message, result.exportPath))
                .setPositiveButton(android.R.string.ok, null)
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

    override fun onResume() {
        super.onResume()
        updateSensorSampling()
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(realListener)
        sensorManager?.unregisterListener(mockListener)
    }

    private fun safeRegisterListener(listener: SensorEventListener, sensor: Sensor, delay: Int) {
        runCatching {
            sensorManager?.registerListener(listener, sensor, delay)
        }.onFailure {
            runCatching { sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    private fun updateSensorSampling() {
        sensorManager?.unregisterListener(realListener)
        sensorManager?.unregisterListener(mockListener)

        val isRecording = viewModel.isRecording.value == true
        val baseDelay = if (isSensorDetailsExpanded || isRecording) SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_UI
        val stepDelay = SensorManager.SENSOR_DELAY_NORMAL
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        ) {
            stepSensor?.let {
                safeRegisterListener(realListener, it, stepDelay)
                safeRegisterListener(mockListener, it, stepDelay)
            }
        }
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

    private fun syncRemoteConfig() {
        val manager = mockServiceViewModel.locationManager ?: return
        if (!MockServiceHelper.putConfig(manager, requireContext())) {
            Toast.makeText(requireContext(), R.string.sensor_remote_sync_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dismissProgressDialog()
        _binding = null
    }
}
