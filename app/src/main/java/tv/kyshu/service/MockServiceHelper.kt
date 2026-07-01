package tv.kyshu.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import tv.kyshu.xposed.utils.Log as XLog
import tv.kyshu.android.root.ShellUtils
import tv.kyshu.ext.sharedPrefs
import tv.kyshu.ext.altitude
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
import tv.kyshu.xposed.security.RemoteCommandSecurity
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Lightweight probe logger for app-side operations.
 * Emits tagged events to logcat with a [Probe] prefix for easy filtering,
 * matching the xposed module's MockProbeLog format.
 */
private object MockServiceProbe {
    private const val TAG = "PortalApp"
    private const val PREFIX = "[Probe]"

    fun init(result: String, detail: String = "") {
        XLog.d(TAG, "$PREFIX [INIT] $result ${detail.trim()}".trim())
    }

    fun ipcSent(command: String, detail: String = "") {
        XLog.d(TAG, "$PREFIX [IPC_SENT] $command ${detail.trim()}".trim())
    }

    fun ipcResult(command: String, success: Boolean, detail: String = "") {
        val status = if (success) "OK" else "FAIL"
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
        return result to bundle
    }

    fun tryInitService(locationManager: LocationManager) {
        val rely = Bundle()
        MockServiceProbe.init("exchange_key_start")
        if(sendWithTimeout(locationManager, "exchange_key", rely)) {
            val encryptedKey = rely.getByteArray("_encrypted_key")
            val keyIv = rely.getByteArray("_key_iv")
            if (encryptedKey != null && keyIv != null) {
                try {
                    val transportKey = java.security.MessageDigest.getInstance("SHA-256").digest(
                        "portal_transport_v1".toByteArray(Charsets.UTF_8)
                    )
                    val decipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    decipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec(transportKey, "AES"),
                        javax.crypto.spec.GCMParameterSpec(128, keyIv))
                    val decrypted = decipher.doFinal(encryptedKey)
                    randomKey = decrypted.decodeToString()
                    MockServiceProbe.init("exchange_key_ok")
                } catch (e: Exception) {
                    MockServiceProbe.error("exchange_key decrypt failed: ${e.message}")
                }
            }
        } else {
            MockServiceProbe.init("exchange_key_failed")
        }
    }

    fun isMockStart(locationManager: LocationManager): Boolean {
        val (ok, rely) = sendAuthenticated(locationManager, "is_start") {}
        return ok && rely.getBoolean("is_start")
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
        }.onFailure {
            MockServiceProbe.warn("Failed to copy portal library: ${it?.message}")
         }

        // TOCTOU guard: verify canonical path is within expected directory
        val resolvedPath = soFile.canonicalPath
        val resolvedDir = soDir.canonicalPath
        if (!resolvedPath.startsWith(resolvedDir)) {
            MockServiceProbe.error("Symlink attack detected: $resolvedPath not under $resolvedDir")
            return false
        }
        ShellUtils.executeCommand("chmod 755 ${soFile.absolutePath}")
        MockServiceProbe.init("load_portal_library", "LOAD_LIBRARY_DISABLED")
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
            val json = org.json.JSONObject()
            json.put("command_id", commandId)
            val nowMs = System.currentTimeMillis()
            val seq = synchronized(this) { ++commandSequence }
            json.put("_kys_auth_nonce", java.security.SecureRandom().nextLong().toString(36))
            json.put("_kys_auth_timestamp_ms", nowMs)
            json.put("_kys_auth_sequence", seq)

            // Serialize payload fields
            for (key in payload.keySet()) {
                val value = payload.get(key)
                when (value) {
                    is String -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Long -> json.put(key, value)
                    is Double -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    else -> json.put(key, value.toString())
                }
            }

            // Compute HMAC matching RemoteCommandSecurity's type-prefixed canonicalization
            val sortedKeys = json.keys().asSequence().filter {
                !it.startsWith("_kys_")
            }.sorted().toList()
            val canonicalPayload = sortedKeys.joinToString("\n") { key ->
                val raw = json.get(key)
                val canonical = when (raw) {
                    null -> "null"
                    is String -> "String:${raw.length}:$raw"
                    is Int -> "Int:$raw"
                    is Long -> "Long:$raw"
                    is Float -> "Float:$raw"
                    is Double -> "Double:$raw"
                    is Boolean -> "Boolean:$raw"
                    else -> "${raw.javaClass.name}:$raw"
                }
                "$key=$canonical"
            }
            val payloadDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonicalPayload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val hmacInput = buildString {
                append(commandId); append('\n')
                append(json.getString("_kys_auth_nonce")); append('\n')
                append(nowMs); append('\n')
                append(seq); append('\n')
                append(payloadDigest)
            }
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(
                randomKey.toByteArray(Charsets.UTF_8), "HmacSHA256"
            ))
            val signature = mac.doFinal(hmacInput.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            json.put("_kys_auth_signature", signature)

            val contentValues = android.content.ContentValues().apply {
                put("command", json.toString())
            }
            context.contentResolver.insert(uri, contentValues)
            true
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
            val ok = sendWithTimeout(locationManager, randomKey, payload)
            if (ok) return true
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