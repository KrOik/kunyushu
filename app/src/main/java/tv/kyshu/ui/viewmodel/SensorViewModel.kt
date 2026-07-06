package tv.kyshu.ui.viewmodel

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.media.MediaScannerConnection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.kyshu.ext.gaitImportPath
import tv.kyshu.ext.gaitParamsJson
import tv.kyshu.ext.gaitRecordSeconds
import tv.kyshu.utils.GaitAnalyzer
import tv.kyshu.utils.GaitCaptureStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GaitAnalysisResult(
    val frequency: Float,
    val message: String,
    val exportPath: String,
    val json: String = "",
    val error: String? = null,
)

class SensorViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication()

    private val _recordSeconds = MutableLiveData(context.gaitRecordSeconds)
    val recordSeconds: LiveData<Int> = _recordSeconds

    private val _gaitImportPath = MutableLiveData(context.gaitImportPath)
    val gaitImportPath: LiveData<String> = _gaitImportPath

    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    private val _recordingMessage = MutableLiveData("")
    val recordingMessage: LiveData<String> = _recordingMessage

    private val _analysisResult = MutableLiveData<GaitAnalysisResult>()
    val analysisResult: LiveData<GaitAnalysisResult> = _analysisResult

    private val recordedAccel = ArrayList<GaitAnalyzer.SensorSample>()
    private val recordedLinear = ArrayList<GaitAnalyzer.SensorSample>()
    private val recordedGyro = ArrayList<GaitAnalyzer.SensorSample>()
    private val recordedGravity = ArrayList<GaitAnalyzer.SensorSample>()

    fun setRecordSeconds(seconds: Int) {
        if (seconds !in 3..120) return
        context.gaitRecordSeconds = seconds
        _recordSeconds.value = seconds
    }

    fun setGaitImportPath(path: String) {
        context.gaitImportPath = path
        _gaitImportPath.value = path
    }

    fun onRealSensorChanged(type: Int, values: FloatArray, timestamp: Long) {
        if (_isRecording.value != true || values.size < 3) return
        val sample = GaitAnalyzer.SensorSample(timestamp, values[0], values[1], values[2])
        synchronized(this) {
            when (type) {
                Sensor.TYPE_ACCELEROMETER -> recordedAccel.add(sample)
                Sensor.TYPE_LINEAR_ACCELERATION -> recordedLinear.add(sample)
                Sensor.TYPE_GYROSCOPE -> recordedGyro.add(sample)
                Sensor.TYPE_GRAVITY -> recordedGravity.add(sample)
            }
        }
    }

    fun startRecordingProcess() {
        if (_isRecording.value == true) return
        val seconds = _recordSeconds.value ?: 10
        viewModelScope.launch {
            for (remaining in 3 downTo 1) {
                _recordingMessage.value = "请将手机放入口袋，${remaining}秒后开始..."
                delay(1000L)
            }
            synchronized(this@SensorViewModel) {
                recordedAccel.clear()
                recordedLinear.clear()
                recordedGyro.clear()
                recordedGravity.clear()
            }
            _recordingMessage.value = "正在录制 IMU，请保持正常步行 ${seconds} 秒..."
            _isRecording.value = true
            delay(seconds * 1000L)
            _isRecording.value = false
            _recordingMessage.value = "正在拟合步态参数..."
            analyzeAndSaveRecorded()
        }
    }

    fun importAndAnalyzeGaitFromPath(path: String) {
        val resolved = GaitCaptureStore.resolveImportDirectory(path, context.packageName)
        val dir = resolved.directory
        if (dir == null) {
            _analysisResult.value = GaitAnalysisResult(0f, "", path, error = resolved.error ?: "路径无效")
            return
        }
        setGaitImportPath(dir.absolutePath)
        viewModelScope.launch(Dispatchers.IO) {
            analyzeDirectory(dir)
        }
    }

    private fun analyzeAndSaveRecorded() {
        viewModelScope.launch(Dispatchers.IO) {
            val accel = synchronized(this@SensorViewModel) { recordedAccel.toList() }
            val linear = synchronized(this@SensorViewModel) { recordedLinear.toList() }
            val gyro = synchronized(this@SensorViewModel) { recordedGyro.toList() }
            val gravity = synchronized(this@SensorViewModel) { recordedGravity.toList() }
            if (linear.size < 50) {
                _analysisResult.postValue(GaitAnalysisResult(0f, "", "", error = "数据不足，请确认线性加速度传感器可用并保持移动"))
                return@launch
            }

            val timeText = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = File(context.getExternalFilesDir(null), "captures/$timeText")
            if (!dir.exists()) dir.mkdirs()
            GaitCaptureStore.saveCsv(File(dir, "accel.csv"), accel)
            GaitCaptureStore.saveCsv(File(dir, "linear.csv"), linear)
            GaitCaptureStore.saveCsv(File(dir, "gyro.csv"), gyro)
            GaitCaptureStore.saveCsv(File(dir, "gravity.csv"), gravity)
            MediaScannerConnection.scanFile(
                context,
                arrayOf("accel.csv", "linear.csv", "gyro.csv", "gravity.csv").map { File(dir, it).absolutePath }.toTypedArray(),
                null,
                null,
            )
            analyzeSamples(linear, gyro, gravity, dir.absolutePath, parseWarnings = emptyList())
        }
    }

    private fun analyzeDirectory(dir: File) {
        val linearResult = GaitCaptureStore.readCsvSamples(File(dir, "linear.csv"))
        val gyroResult = GaitCaptureStore.readCsvSamples(File(dir, "gyro.csv"))
        val gravityResult = GaitCaptureStore.readCsvSamples(File(dir, "gravity.csv"))
        val parseWarnings = buildList {
            if (linearResult.linesSkipped > 0) add("linear.csv: ${linearResult.linesSkipped}/${linearResult.linesTotal} 行解析失败")
            if (gyroResult.linesSkipped > 0) add("gyro.csv: ${gyroResult.linesSkipped}/${gyroResult.linesTotal} 行解析失败")
            if (gravityResult.linesSkipped > 0) add("gravity.csv: ${gravityResult.linesSkipped}/${gravityResult.linesTotal} 行解析失败")
        }
        if (linearResult.samples.size < 50) {
            val suffix = if (parseWarnings.isNotEmpty()) "; ${parseWarnings.joinToString("; ")}" else ""
            _analysisResult.postValue(GaitAnalysisResult(0f, "", dir.absolutePath, error = "linear.csv 有效数据不足 (${linearResult.samples.size}条)$suffix"))
            return
        }
        analyzeSamples(linearResult.samples, gyroResult.samples, gravityResult.samples, dir.absolutePath, parseWarnings)
    }

    private fun analyzeSamples(
        linearRaw: List<GaitAnalyzer.SensorSample>,
        gyroRaw: List<GaitAnalyzer.SensorSample>,
        gravityRaw: List<GaitAnalyzer.SensorSample>,
        exportPath: String,
        parseWarnings: List<String>,
    ) {
        try {
            val (linear, gyro, gravity) = GaitCaptureStore.cropToOverlap(linearRaw, gyroRaw, gravityRaw)
            if (linear.size < 50) {
                _analysisResult.postValue(GaitAnalysisResult(0f, "", exportPath, error = "裁剪后有效数据不足 (${linear.size}条)"))
                return
            }
            val params = GaitAnalyzer.analyze(linear, gyro, gravity)
            val verify = GaitAnalyzer.verify(linear, gyro, gravity, params)
            val json = JSON.toJSONString(params)
            context.gaitParamsJson = json
            val message = buildString {
                append(verify.toString())
                if (parseWarnings.isNotEmpty()) {
                    append("\n解析警告: ")
                    append(parseWarnings.joinToString("; "))
                }
                append("\nlinear=${linear.size} gyro=${gyro.size} gravity=${gravity.size} samples")
            }
            _analysisResult.postValue(GaitAnalysisResult(params.frequency, message, exportPath, json))
        } catch (e: Exception) {
            _analysisResult.postValue(GaitAnalysisResult(0f, "", exportPath, error = "分析失败: ${e.message}"))
        }
    }
}
