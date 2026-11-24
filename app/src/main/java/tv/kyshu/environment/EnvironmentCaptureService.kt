package tv.kyshu.environment

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import tv.kyshu.R
import tv.kyshu.xposed.utils.Log
import kotlin.coroutines.resume

data class CaptureServiceState(
    val running: Boolean = false,
    val lastCapturedAtMillis: Long = 0,
    val wifiCount: Int = 0,
    val cellCount: Int = 0,
    val bleCount: Int = 0,
    val savedSamples: Int = 0,
    val message: String = "",
)

class EnvironmentCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private lateinit var repository: EnvironmentRepository
    private lateinit var scanner: EnvironmentScanner
    private lateinit var settings: EnvironmentSettings

    override fun onCreate() {
        super.onCreate()
        repository = EnvironmentRepository(EnvironmentDatabase.get(this))
        scanner = EnvironmentScanner(this)
        settings = EnvironmentSettings(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            else -> startCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (scanJob?.isActive == true) return
        if (!hasLocationPermission()) {
            state.value = CaptureServiceState(message = "缺少位置权限")
            stopSelf()
            return
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_sensors_24)
            .setContentTitle("环境图谱采集中")
            .setContentText("正在记录位置对应的 Wi-Fi、基站与 BLE 环境")
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        state.value = state.value.copy(running = true, message = "等待定位")
        scanJob = scope.launch {
            var lastSaved: Location? = null
            var saved = 0
            while (isActive) {
                try {
                    val location = latestLocation()
                    if (location == null) {
                        state.value = state.value.copy(message = "暂时无法获取真实位置")
                    } else if (lastSaved == null || location.distanceTo(lastSaved!!) >= settings.minimumScanDistanceMeters) {
                        val capture = scanner.capture(location.latitude, location.longitude, settings)
                        if (capture.wifi.isNotEmpty() || capture.cells.isNotEmpty() || capture.ble.isNotEmpty()) {
                            repository.save(capture)
                            saved++
                            lastSaved = Location(location)
                            state.value = CaptureServiceState(
                                running = true,
                                lastCapturedAtMillis = System.currentTimeMillis(),
                                wifiCount = capture.wifi.size,
                                cellCount = capture.cells.size,
                                bleCount = capture.ble.size,
                                savedSamples = saved,
                                message = "已保存当前位置环境",
                            )
                        } else {
                            state.value = state.value.copy(message = "本次扫描没有可保存的数据")
                        }
                    } else {
                        state.value = state.value.copy(message = "移动距离不足，等待下一次扫描")
                    }
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (error: Throwable) {
                    Log.w("PTL.Environment", "environment capture failed", error)
                    state.value = state.value.copy(message = "采集失败：${error.javaClass.simpleName}")
                }
                delay(settings.scanIntervalSeconds * 1_000L)
            }
        }
    }

    private fun stopCapture() {
        scanJob?.cancel()
        scanJob = null
        state.value = state.value.copy(running = false, message = "采集已停止")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun latestLocation(): Location? {
        if (!hasLocationPermission()) return null
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = manager.getProviders(true)
            .sortedBy { if (it == LocationManager.GPS_PROVIDER) 0 else 1 }
        for (provider in providers) {
            val fresh = withTimeoutOrNull(5_000) { currentLocation(manager, provider) }
            if (fresh != null) return fresh
        }
        return providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun currentLocation(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= 30) {
                val cancellation = CancellationSignal()
                runCatching {
                    manager.getCurrentLocation(provider, cancellation, mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
                continuation.invokeOnCancellation { cancellation.cancel() }
            } else {
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location)
                    }
                    override fun onProviderDisabled(provider: String) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(null)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
                    .onFailure { if (continuation.isActive) continuation.resume(null) }
                continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
            }
        }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "环境图谱采集", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        state.value = state.value.copy(running = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "tv.kyshu.environment.START"
        const val ACTION_STOP = "tv.kyshu.environment.STOP"
        private const val CHANNEL_ID = "portal_environment_capture"
        private const val NOTIFICATION_ID = 0x454e56
        private val state = MutableStateFlow(CaptureServiceState())
        val serviceState = state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, EnvironmentCaptureService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, EnvironmentCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
