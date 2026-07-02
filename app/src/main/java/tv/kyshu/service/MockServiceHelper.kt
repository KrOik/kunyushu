package tv.kyshu.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import tv.kyshu.xposed.utils.Log as XLog
import tv.kyshu.android.root.ShellUtils
import tv.kyshu.ext.sharedPrefs
import tv.kyshu.ext.altitude
import tv.kyshu.ext.altitudeJitter
import tv.kyshu.ext.debug
import tv.kyshu.ext.disableFusedProvider
import tv.kyshu.ext.disableGetCurrentLocation
import tv.kyshu.ext.disableRegisterLocationListener
import tv.kyshu.ext.enableAGPS
import tv.kyshu.ext.enableGetFromLocation
import tv.kyshu.ext.enableNMEA
import tv.kyshu.ext.enableRequestGeofence
import tv.kyshu.ext.minSatelliteCount
import tv.kyshu.ext.needDowngradeToCdma
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
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.security.RemoteCommandBundleCodec
import tv.kyshu.xposed.security.RemoteCommandSecurity
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight probe logger for app-side operations.
 * Emits tagged events to logcat with a [Probe] prefix for easy filtering,
 * matching the xposed module's MockProbeLog format.
 */
private object MockServiceProbe {
    private const val TAG = "PortalApp"
    private const val PREFIX = "[Probe]"
    private const val MOVE_IPC_LOG_INTERVAL_MS = 5_000L
    private val lastMoveIpcLogMs = AtomicLong(Long.MIN_VALUE)
    private val suppressedMoveIpcLogs = AtomicInteger(0)

    fun init(result: String, detail: String = "") {
        XLog.d(TAG, "$PREFIX [INIT] $result ${detail.trim()}".trim())
    }

    fun ipcSent(command: String, detail: String = "") {
        if (command == "move") return
        XLog.d(TAG, "$PREFIX [IPC_SENT] $command ${detail.trim()}".trim())
    }

    fun ipcResult(command: String, success: Boolean, detail: String = "") {
        val status = if (success) "OK" else "FAIL"
        if (command == "move") {
            val now = SystemClock.elapsedRealtime()
            val last = lastMoveIpcLogMs.get()
            val insideWindow = last != Long.MIN_VALUE && now >= last && now - last < MOVE_IPC_LOG_INTERVAL_MS
            if (insideWindow) {
                suppressedMoveIpcLogs.incrementAndGet()
                return
            }
            if (!lastMoveIpcLogMs.compareAndSet(last, now)) {
                suppressedMoveIpcLogs.incrementAndGet()
                return
            }
            val suppressed = suppressedMoveIpcLogs.getAndSet(0)
            val suffix = if (suppressed > 0) " suppressed=$suppressed/${MOVE_IPC_LOG_INTERVAL_MS / 1000}s" else ""
            XLog.d(TAG, "$PREFIX [IPC_RCVD] $command -> $status$suffix")
            return
        }
        XLog.d(TAG, "$PREFIX [IPC_RCVD] $command -> $status ${detail.trim()}".trim())
    }

    fun configSync(detail: String) {
        XLog.d(TAG, "$PREFIX [CONFIG_SYNC] $detail")
    }

    fun error(msg: String, e: Throwable? = null) {
        val suffix = if (e != null) ": ${e.message}" else ""
        XLog.e(TAG, "$PREFIX [ERROR] $msg$suffix")
    }

    fun warn(msg: String) {
        XLog.w(TAG, "$PREFIX [WARN] $msg")
    }
}

object MockServiceHelper {
    @JvmStatic
    val PROVIDER_NAME: String
        get() {
            // Dynamic provider name derived from device fingerprint (like VoxLoction)
            // Falls back to "portal" for backward compatibility and emulator/test environments
            val derived = tv.kyshu.xposed.utils.ChannelIdentity.providerName
            return if (derived.isNotBlank()) derived else "portal"
        }
    private lateinit var randomKey: String
    private var commandSequence: Long = 0
    private val sendCommandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Portal-IPC-Timeout").apply { isDaemon = true }
    }

    /** Wraps sendExtraCommand with a timeout to prevent permanent Binder blocking. */
    private fun sendWithTimeout(
        locationManager: LocationManager,
        command: String,
        bundle: Bundle,
        timeoutMs: Long = 5000L
    ): Boolean {
        return try {
            val future = sendCommandExecutor.submit(Callable<Boolean> {
                locationManager.sendExtraCommand(PROVIDER_NAME, command, bundle)
            })
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            MockServiceProbe.warn("sendExtraCommand timed out (${timeoutMs}ms) for command=$command")
            false
        } catch (e: Exception) {
            MockServiceProbe.error("sendExtraCommand failed for command=$command", e)
            false
        }
    }

    /**
     * Send an authenticated command with HMAC envelope and encrypted payload.
     * Uses the session key from exchange_key and increments the sequence counter.
     * Falls back to plain send if the security module is unavailable.
     *
     * @return Pair(success, bundle). On success, bundle contains server response data.
     */
    private fun sendAuthenticated(
        locationManager: LocationManager,
        commandId: String,
        bundleBuilder: Bundle.() -> Unit
    ): Pair<Boolean, Bundle> {
        if (!::randomKey.isInitialized) {
            MockServiceProbe.warn("sendAuthenticated: randomKey not initialized for $commandId")
            return false to Bundle()
        }
        val bundle = Bundle().apply {
            putString("command_id", commandId)
            bundleBuilder()
        }
        val seq = synchronized(this) { ++commandSequence }
        val envelopeOk = kotlin.runCatching {
            RemoteCommandSecurity.attachEnvelope(bundle, randomKey, seq)
        }.onFailure { e ->
            MockServiceProbe.warn("sendAuthenticated: auth envelope attach failed for $commandId: ${e.message}")
        }.getOrDefault(false)

        if (!envelopeOk) {
            MockServiceProbe.warn("sendAuthenticated: attachEnvelope returned false for $commandId, skipping send")
            return false to bundle
        }

        MockServiceProbe.ipcSent(commandId)
        val result = sendWithTimeout(locationManager, randomKey, bundle)
        MockServiceProbe.ipcResult(commandId, result)
        if (result && !RemoteCommandSecurity.decryptSensitivePayload(bundle, randomKey)) {
            MockServiceProbe.warn("sendAuthenticated: response decrypt failed for $commandId")
            return false to bundle
        }
        return result to bundle
    }

    fun tryInitService(locationManager: LocationManager) {
        val rely = Bundle()
        MockServiceProbe.init("exchange_key_start")
        if(sendWithTimeout(locationManager, "exchange_key", rely)) {
            val sessionKey = RemoteCommandSecurity.decryptSessionKey(rely)
            if (sessionKey != null) {
                randomKey = sessionKey
                synchronized(this) { commandSequence = 0L }
                MockServiceProbe.init("exchange_key_ok")
            } else {
                MockServiceProbe.warn("exchange_key response missing encrypted session key")
            }
        } else {
            MockServiceProbe.init("exchange_key_failed")
        }
    }

    fun isMockStart(locationManager: LocationManager): Boolean {
        val (ok, rely) = sendAuthenticated(locationManager, "is_start") {}
        return ok && rely.getBoolean("is_start")
    }

    fun ping(locationManager: LocationManager): Boolean {
        val (ok, rely) = sendAuthenticated(locationManager, "ping") {}
        return ok && rely.getString("pong") == "pong"
    }

    fun isGnssMockStart(locationManager: LocationManager): Boolean {
        val (ok, rely) = sendAuthenticated(locationManager, "is_gnss_start") {}
        return ok && rely.getBoolean("is_gnss_start")
    }

    fun startGnssMock(locationManager: LocationManager): Boolean {
        return sendAuthenticated(locationManager, "start_gnss_mock") {}.first
    }

    fun stopGnssMock(locationManager: LocationManager): Boolean {
        return sendAuthenticated(locationManager, "stop_gnss_mock") {}.first
    }

    fun isWifiMockStart(locationManager: LocationManager): Boolean {
        val (ok, rely) = sendAuthenticated(locationManager, "is_wifi_mock_start") {}
        return ok && rely.getBoolean("is_wifi_mock_start")
    }

    fun startWifiMock(locationManager: LocationManager): Boolean {
        return sendAuthenticated(locationManager, "start_wifi_mock") {}.first
    }

    fun stopWifiMock(locationManager: LocationManager): Boolean {
        return sendAuthenticated(locationManager, "stop_wifi_mock") {}.first
    }

    fun tryOpenMock(
        locationManager: LocationManager,
        speed: Double,
        altitude: Double,
        accuracy: Float,
    ): Boolean {
        val (ok, _) = sendAuthenticated(locationManager, "start") {
            putDouble("speed", speed)
            putDouble("altitude", altitude)
            putFloat("accuracy", accuracy)
        }
        if (!ok) return false
        return isMockStart(locationManager)
    }

    fun tryCloseMock(locationManager: LocationManager): Boolean {
        val (ok, _) = sendAuthenticated(locationManager, "stop") {}
        if (!ok) return false
        return !isMockStart(locationManager)
    }

    fun getLocation(locationManager: LocationManager): Pair<Double, Double>? {
        val (ok, rely) = sendAuthenticated(locationManager, "get_location") {}
        if (ok) return Pair(rely.getDouble("lat"), rely.getDouble("lon"))
        return null
    }

    fun getLocationListenerSize(locationManager: LocationManager): Int? {
        val (ok, rely) = sendAuthenticated(locationManager, "get_listener_size") {}
        if (ok) return rely.getInt("size")
        return null
    }

    fun broadcastLocation(locationManager: LocationManager): Boolean {
        return sendAuthenticated(locationManager, "broadcast_location") {}.first
    }

    fun setBearing(locationManager: LocationManager, bearing: Double): Boolean {
        return sendAuthenticated(locationManager, "set_bearing") {
            putDouble("bearing", bearing)
        }.first
    }

    fun setSpeed(locationManager: LocationManager, speed: Float): Boolean {
        return sendAuthenticated(locationManager, "set_speed") {
            putFloat("speed", speed)
        }.first
    }

    fun setAltitude(locationManager: LocationManager, altitude: Double): Boolean {
        return sendAuthenticated(locationManager, "set_altitude") {
            putDouble("altitude", altitude)
        }.first
    }

    fun setSpeedAmplitude(locationManager: LocationManager, speedAmplitude: Double): Boolean {
        return sendAuthenticated(locationManager, "set_speed_amp") {
            putDouble("speed_amplitude", speedAmplitude)
        }.first
    }

    fun getSpeed(locationManager: LocationManager): Float? {
        val (ok, rely) = sendAuthenticated(locationManager, "get_speed") {}
        if (ok) return rely.getFloat("speed")
        return null
    }

    fun getBearing(locationManager: LocationManager): Float? {
        val (ok, rely) = sendAuthenticated(locationManager, "get_bearing") {}
        if (ok) return rely.getFloat("bearing")
        return null
    }

    fun getAltitude(locationManager: LocationManager): Double? {
        val (ok, rely) = sendAuthenticated(locationManager, "get_altitude") {}
        if (ok) return rely.getDouble("altitude")
        return null
    }

    fun move(locationManager: LocationManager, distance: Double, bearing: Double): Boolean {
        if (FakeLoc.enableDebugLog) {
            XLog.d("MockServiceHelper", "move: distance=$distance, bearing=$bearing")
        }
        return sendAuthenticated(locationManager, "move") {
            putDouble("n", distance)
            putDouble("bearing", bearing)
        }.first
    }

    fun setLocation(locationManager: LocationManager, lat: Double, lon: Double): Boolean {
        return updateLocation(locationManager, lat, lon, "=")
    }

    fun updateLocation(locationManager: LocationManager, lat: Double, lon: Double, mode: String): Boolean {
        return sendAuthenticated(locationManager, "update_location") {
            putDouble("lat", lat)
            putDouble("lon", lon)
            putString("mode", mode)
        }.first
    }


    fun putConfig(locationManager: LocationManager, context: Context): Boolean {
        return sendAuthenticated(locationManager, "put_config") {
            putBoolean("enable", context.sharedPrefs.getBoolean("enable", false))
            putDouble("altitude", context.altitude)
            putFloat("altitudeJitter", context.altitudeJitter)
            putDouble("speed", context.speed)
            putBoolean("enable_debug_log", context.debug)
            putBoolean("disable_get_current_location", context.disableGetCurrentLocation)
            putBoolean("disable_register_location_listener", context.disableRegisterLocationListener)
            putBoolean("disable_fused_location", context.disableFusedProvider)
            putBoolean("need_downgrade_to_2g", context.needDowngradeToCdma)
            putInt("min_satellites", context.minSatelliteCount)
            putBoolean("enable_agps", context.enableAGPS)
            putBoolean("enable_nmea", context.enableNMEA)
            putBoolean("disable_request_geofence", !context.enableRequestGeofence)
            putBoolean("disable_get_from_location", !context.enableGetFromLocation)
            putBoolean("hook_wifi", context.hookWifi)
            putString("mock_ssid", context.mockSSID)
            putString("mock_bssid", context.mockBSSID)
            putString("mock_mac", context.mockMac)
            putString("mock_ip", context.mockIp)
            putInt("mock_wifi_scan_count", context.mockWifiScanCount)
            putBoolean("enable_step_count_mock", context.enableStepCountMock)
            putInt("step_count", context.stepCount)
            putBoolean("enable_shake_mock", context.enableShakeMock)
            putBoolean("keep_walking_waveform", context.keepWalkingWaveform)
            putLong("doze_sensor_interval_ms", context.dozeSensorIntervalMs)
            putFloat("gait_freq", context.gaitFreq)
            putFloat("gait_amp_scale", context.gaitAmpScale)
            putBoolean("hide_base_station", context.hideBaseStation)
            putBoolean("loop_broadcast_location", context.loopBroadcastLocation)
            putBoolean("hide_mock", context.hideMock)
            putBoolean("disable_network_location", context.disableNetworkLocation)
            putDouble("speed_amplitude", context.speedAmplitude)
            putDouble("min_speed", context.minSpeed)
            putDouble("max_speed", context.maxSpeed)
        }.also { (ok, _) ->
            MockServiceProbe.configSync("put_config: enable=${context.sharedPrefs.getBoolean("enable", false)} ok=$ok")
        }.first
    }

    fun syncConfig(locationManager: LocationManager): Bundle? {
        val (ok, rely) = sendAuthenticated(locationManager, "sync_config") {}
        return if (ok) rely else null
    }

    fun isServiceInit(): Boolean {
        return ::randomKey.isInitialized
    }

    fun loadLibrary(locationManager: LocationManager, path: String): String? {
        val (ok, rely) = sendAuthenticated(locationManager, "load_library") {
            putString("path", path)
        }
        return if (ok) rely.getString("result") else null
    }

    @SuppressLint("DiscouragedPrivateApi")
    fun loadPortalLibrary(context: Context): Boolean {
        if (!ShellUtils.hasRoot()) return false

        val isX86: Boolean = runCatching {
            if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) {
                return@runCatching true
            }
            val clazz = Class.forName("dalvik.system.VMRuntime")
            val method = clazz.getDeclaredMethod("getRuntime")
            val runtime = method.invoke(null)
            val field = clazz.getDeclaredField("vmInstructionSet")
            field.isAccessible = true
            val instructionSet = field.get(runtime) as String
            if (instructionSet.contains("x86") ) {
                true
            } else false
        }.getOrElse { false }
        // todo: support x86

        val soDir = File("/data/local/portal-lib")
        if (!soDir.exists()) {
            ShellUtils.executeCommand("mkdir -m 755 ${soDir.absolutePath}")
        }
        val soFile = File(soDir, "libportal.so")
        var copyOk = false
        runCatching {
            val tmpSoFile = File(soDir, "libportal.so.tmp").also { file ->
                var nativeDir = context.applicationInfo.nativeLibraryDir
                val apkSoFile = File(nativeDir, "libportal.so")
                if (apkSoFile.exists()) {
                    ShellUtils.executeCommand("cp ${apkSoFile.absolutePath} ${file.absolutePath}")
                } else {
                    MockServiceProbe.error("Failed to copy portal library: ${apkSoFile.absolutePath}")
                    return@runCatching
                }
            }
            if (soFile.exists()) {
                val newHash = ShellUtils.executeCommand("sha256sum ${tmpSoFile.absolutePath}").substringBefore(' ')
                val oldHash = ShellUtils.executeCommand("sha256sum ${soFile.absolutePath}").substringBefore(' ')
                if (newHash == oldHash || oldHash.isBlank()) {
                    if (newHash.isNotEmpty()) {
                        ShellUtils.executeCommand("rm ${soFile.absolutePath}")
                        ShellUtils.executeCommand("mv ${tmpSoFile.absolutePath} ${soFile.absolutePath}")
                    }
                } else {
                    MockServiceProbe.warn("SO hash mismatch, new: $newHash, old: $oldHash")
                    ShellUtils.executeCommand("rm ${tmpSoFile.absolutePath}")
                }
            } else if (tmpSoFile.exists()) {
                ShellUtils.executeCommand("mv ${tmpSoFile.absolutePath} ${soFile.absolutePath}")
            }
            copyOk = true
        }.onFailure {
            MockServiceProbe.warn("Failed to copy portal library: ${it?.message}")
        }

        if (!copyOk) return false

        // TOCTOU guard: verify canonical path is within expected directory
        val resolvedPath = soFile.canonicalPath
        val resolvedDir = soDir.canonicalPath
        if (!resolvedPath.startsWith(resolvedDir)) {
            MockServiceProbe.error("Symlink attack detected: $resolvedPath not under $resolvedDir")
            return false
        }
        ShellUtils.executeCommand("chmod 755 ${soFile.absolutePath}")

        // Try to load via IPC to the Xposed module
        val locationManager = runCatching {
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }.getOrNull()
        if (locationManager != null) {
            if (!::randomKey.isInitialized) {
                tryInitService(locationManager)
            }
            val result = loadLibrary(locationManager, soFile.absolutePath)
            MockServiceProbe.init("load_portal_library", result ?: "IPC_FAILED")
            return result == "success"
        } else {
            MockServiceProbe.init("load_portal_library", "NO_LOCATION_MANAGER")
        }

        return false
    }

    /**
     * Always returns false — hooked by the Xposed module to fool
     * any code that checks whether the mock module is active.
     * The real return value is forced to `false` by FakeLocation
     * before any app-side code can call this method.
     */
    @JvmStatic
    fun isModuleLoaded(): Boolean = false

    /**
     * ContentProvider fallback channel — used when sendExtraCommand
     * is unavailable or the location provider is not registered.
     * Writes the command bundle as JSON to the ContentProvider.
     */
    @SuppressLint("Recycle")
    private fun sendViaContentProvider(context: Context, commandId: String, payload: Bundle): Boolean {
        if (!::randomKey.isInitialized) {
            MockServiceProbe.warn("sendViaContentProvider: randomKey not initialized")
            return false
        }
        return kotlin.runCatching {
            val uri = android.net.Uri.parse("content://tv.kyshu.xposed.config/cmd")
            val commandBundle = Bundle(payload).apply {
                putString("command_id", commandId)
            }
            val seq = synchronized(this) { ++commandSequence }
            if (!RemoteCommandSecurity.attachEnvelope(commandBundle, randomKey, seq)) {
                MockServiceProbe.warn("sendViaContentProvider: auth envelope attach failed for $commandId")
                return@runCatching false
            }

            val json = org.json.JSONObject()
                .put("session_key", randomKey)
                .put("bundle", RemoteCommandBundleCodec.encode(commandBundle))

            val contentValues = android.content.ContentValues().apply {
                put("command", json.toString())
            }
            context.contentResolver.insert(uri, contentValues) != null
        }.getOrDefault(false)
    }

    /**
     * Send a command with automatic fallback to ContentProvider.
     */
    fun sendCommandWithFallback(
        locationManager: LocationManager,
        context: Context,
        commandId: String,
        payload: Bundle
    ): Boolean {
        // Primary: try sendExtraCommand
        if (::randomKey.isInitialized) {
            val commandBundle = Bundle(payload).apply {
                putString("command_id", commandId)
            }
            if (!RemoteCommandSecurity.attachEnvelope(
                    commandBundle,
                    randomKey,
                    synchronized(this) { ++commandSequence },
                )
            ) {
                MockServiceProbe.warn("sendCommandWithFallback: attachEnvelope failed for $commandId")
            } else {
                val ok = sendWithTimeout(locationManager, randomKey, commandBundle)
                if (ok) return true
            }
            MockServiceProbe.warn("sendCommandWithFallback: sendExtraCommand failed for $commandId, falling back to ContentProvider")
        }
        // Fallback: write to ContentProvider
        val fallbackOk = sendViaContentProvider(context, commandId, payload)
        if (!fallbackOk) {
            MockServiceProbe.error("sendCommandWithFallback: ContentProvider fallback also failed for $commandId")
        }
        return fallbackOk
}
}
