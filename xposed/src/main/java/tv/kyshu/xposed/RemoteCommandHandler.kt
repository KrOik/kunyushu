/*
 * Copyright (C) 2025 The KunYuShu Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tv.kyshu.xposed

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import tv.kyshu.dobby.Dobby
import tv.kyshu.xposed.hooks.LocationServiceHook
import tv.kyshu.xposed.hooks.sensor.SystemSensorManagerHook
import tv.kyshu.xposed.security.RemoteCommandSecurity
import tv.kyshu.xposed.utils.LogRouter
import java.io.File
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.BinderUtils
import tv.kyshu.xposed.utils.CrashForensics
import tv.kyshu.xposed.utils.DiagnosticAspect
import tv.kyshu.xposed.utils.FlightRecorder
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.LogRedactor
import tv.kyshu.xposed.utils.MockAuditTrail
import org.json.JSONArray
import tv.kyshu.xposed.config.BleDeviceProfile
import tv.kyshu.xposed.config.MockDomain
import tv.kyshu.xposed.config.MockDomainConfig
import tv.kyshu.xposed.config.MockDomainConfigJson
import tv.kyshu.xposed.config.MockWifiData
import tv.kyshu.xposed.utils.MockProbeLog
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object RemoteCommandHandler {
    private const val MAX_PROXY_BINDERS = 8
    private const val MAX_PROXY_RELAY_QUEUE = 64
    private const val SLOW_PROXY_RELAY_MS = 50L
    private const val FIELD_REQUESTED_SCOPE = "requested_scope"
    private const val FIELD_FROM_PACKAGE = "from_package"
    private const val FIELD_FROM_PROCESS = "from_process"
    const val SCOPE_SENSOR_APP = "sensor_app"
    private const val FIELD_EXPORT_CONSENT_TOKEN = "export_consent_token"
    private const val EXPORT_CONSENT_WINDOW_MS = 2L * 60L * 1000L
    private const val MAX_EXPORT_BYTES = 512 * 1024
    private const val MAX_EXPORT_FILES = 8
    private const val MAX_EXPORT_TOTAL_BYTES = 2L * 1024L * 1024L
    private const val MAX_EXPORT_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    enum class SessionScope {
        CONTROL,
        SYNC_ONLY,
    }

    private data class ExportConsent(
        val sessionKey: String,
        val expiresAtMs: Long,
    )

    private class ProxyBinderSession(
        val binder: IBinder,
        val sessionKey: String,
    )

    @Volatile
    private var activeSessionKey: String = RemoteCommandSecurity.newSessionKey()
    internal val randomKey: String
        get() = activeSessionKey
    private val replayGuard = RemoteCommandSecurity.ReplayGuard()
    private val sessionScopes = ConcurrentHashMap<String, SessionScope>()
    private val exportConsents = ConcurrentHashMap<String, ExportConsent>()
    private val proxyBinders by lazy { Collections.synchronizedList(arrayListOf<ProxyBinderSession>()) }
    private val proxyRelayDropCount = java.util.concurrent.atomic.AtomicLong()
    private val proxyRelayExecutor by lazy {
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_PROXY_RELAY_QUEUE),
            { runnable -> Thread(runnable, "Portal-ProxyRelay").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
    private val proxyCommandIds = mutableSetOf(
        MockCommandContract.Commands.PUT_CONFIG,
        MockCommandContract.Commands.START,
        MockCommandContract.Commands.STOP,
        MockCommandContract.Commands.START_GNSS_MOCK,
        MockCommandContract.Commands.STOP_GNSS_MOCK,
        "set_speed_amp",
        "set_altitude",
        "set_speed",
        "set_bearing",
        "move",
        "update_location",
    ).apply {
        if (BuildConfig.ENABLE_WIFI_MOCK) {
            add(MockCommandContract.Commands.UPDATE_WIFI)
            add(MockCommandContract.Commands.START_WIFI_MOCK)
            add(MockCommandContract.Commands.STOP_WIFI_MOCK)
        }
    }
    private val syncOnlyCommandIds = setOf(
        MockCommandContract.Commands.SYNC_CONFIG,
    )

    private object LiveHookRuntimeState : HookRuntimeStateStore {
        override var enable: Boolean
            get() = FakeLoc.enable
            set(value) {
                synchronized(tv.kyshu.xposed.utils.FakeLoc) {
                    FakeLoc.enable = value
                    if (value) {
                        FakeLoc.forceDisable = false
                    }
                }
            }
        override var enableMockGnss: Boolean
            get() = FakeLoc.enableMockGnss
            set(value) { FakeLoc.enableMockGnss = value }
        override var enableMockWifi: Boolean
            get() = FakeLoc.enableMockWifi
            set(value) { FakeLoc.enableMockWifi = BuildConfig.ENABLE_WIFI_MOCK && value }
        override var latitude: Double
            get() = FakeLoc.latitude
            set(value) { FakeLoc.latitude = value }
        override var longitude: Double
            get() = FakeLoc.longitude
            set(value) { FakeLoc.longitude = value }
        override var altitude: Double
            get() = FakeLoc.altitude
            set(value) { FakeLoc.altitude = value }
        override var speed: Double
            get() = FakeLoc.speed
            set(value) { FakeLoc.speed = value }
        override var accuracy: Float
            get() = FakeLoc.accuracy
            set(value) { FakeLoc.accuracy = value }
        override var bearing: Double
            get() = FakeLoc.bearing
            set(value) { FakeLoc.bearing = value }
        override var hasBearings: Boolean
            get() = FakeLoc.hasBearings
            set(value) { FakeLoc.hasBearings = value }
        override var forceDisable: Boolean
            get() = FakeLoc.forceDisable
            set(value) { FakeLoc.forceDisable = value }
    }

    private val lifecycleController by lazy {
        HookLifecycleController(
            store = LiveHookRuntimeState,
            setNativeStatus = { enabled ->
                val applied = Dobby.setStatusWhenLoaded(enabled)
                if (!applied) {
                    Logger.warn("[Hook] Dobby.setStatus queued: ${Dobby.unavailableReason}")
                }
            },
            clearListeners = { LocationServiceHook.locationListeners.clear() },
            onStart = {
                // 开启模拟时强制结算一次卫星位置
                LocationServiceHook.forceUpdateSatelliteStates()
            },
            refreshGnssStatus = {
                // 系统服务进程中定时刷新 GNSS 状态时也结算卫星位置
                LocationServiceHook.forceUpdateSatelliteStates()
            },
            isSystemServerProcess = { FakeLoc.isSystemServerProcess },
        )
    }

    private val requestSeq = java.util.concurrent.atomic.AtomicLong()

    private fun Bundle.getNumberAsDouble(key: String, default: Double): Double {
        return when (val value = get(key)) {
            is Number -> value.toDouble()
            else -> default
        }
    }

    private fun sanitizeMinSatellites(value: Int): Int = when {
        value <= 0 -> 12
        value > 35 -> 35
        else -> value
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun handleInstruction(command: String, rely: Bundle): Boolean {
        return DiagnosticAspect.traceBusiness(
            label = "RemoteCommandHandler.handleInstruction",
            detail = "command=$command ${DiagnosticAspect.bundleSummary(rely)}",
        ) {
            kotlin.runCatching {
                if (command == "exchange_key") {
                    val userId = BinderUtils.getCallerUid()
                    val scope = when {
                        BinderUtils.isLocationProviderEnabled(userId) -> SessionScope.CONTROL
                        isSensorAppSyncExchangeAllowed(userId, rely) -> SessionScope.SYNC_ONLY
                        else -> null
                    }
                    if (scope == null) {
                        Logger.warn("exchange_key: Permission denied for UID $userId")
                        false
                    } else {
                        val rawKey = rotateSessionKey(scope)
                        if (scope == SessionScope.SYNC_ONLY) {
                            Logger.info(
                                "[RemoteCommandHandler] issued restricted sync session: " +
                                    "uid=$userId package=${rely.getString(FIELD_FROM_PACKAGE) ?: ""} " +
                                    "process=${rely.getString(FIELD_FROM_PROCESS) ?: ""}"
                            )
                        }
                        RemoteCommandSecurity.putEncryptedSessionKey(rely, rawKey)
                    }
                } else {
                    val commandId = rely.getString("command_id") ?: return@runCatching false
                    handleAuthenticatedInstruction(command, commandId, rely, replayGuard)
                }
            }.onFailure { e ->
                Logger.error("[RemoteCommandHandler] handleInstruction crash", e)
                false
            }.getOrDefault(false)
        }
    }

    @Synchronized
    internal fun rotateSessionKey(scope: SessionScope = SessionScope.CONTROL): String {
        activeSessionKey = RemoteCommandSecurity.newSessionKey()
        replayGuard.registerSession(activeSessionKey)
        sessionScopes[activeSessionKey] = scope
        FlightRecorder.state("IPC", "SESSION_ROTATED")
        return activeSessionKey
    }

    internal fun registerSessionKey(sessionKey: String, scope: SessionScope = SessionScope.CONTROL): Boolean {
        val registered = replayGuard.registerSession(sessionKey)
        if (registered) {
            sessionScopes[sessionKey] = scope
        }
        return registered
    }

    internal fun handleSessionInstruction(
        sessionKey: String,
        rely: Bundle,
        replayGuard: RemoteCommandSecurity.ReplayGuard = this.replayGuard,
    ): Boolean {
        return DiagnosticAspect.traceBusiness(
            label = "RemoteCommandHandler.handleSessionInstruction",
            detail = DiagnosticAspect.bundleSummary(rely),
        ) {
            val commandId = rely.getString("command_id") ?: return@traceBusiness false
            handleAuthenticatedInstruction(sessionKey, commandId, rely, replayGuard)
        }
    }

    private fun structuredLog(level: String, commandId: String, msg: String, rely: Bundle? = null, latencyMs: Long? = null) {
        val rid = requestSeq.incrementAndGet()
        val ts = System.currentTimeMillis()
        val detail = buildString {
            append("[rid=$rid ts=$ts cmd=$commandId] $msg")
            if (latencyMs != null) append(" latency=${latencyMs}ms")
        }
        when (level) {
            "debug" -> if (FakeLoc.enableDebugLog) Logger.debug(detail)
            "warn" -> Logger.warn(detail)
            "error" -> Logger.error(detail)
            else -> Logger.info(detail)
        }
    }

    private fun handleAuthenticatedInstruction(
        sessionKey: String,
        commandId: String,
        rely: Bundle,
        replayGuard: RemoteCommandSecurity.ReplayGuard,
    ): Boolean {
        // Bundle size cap: use total serialized size estimate to prevent OOM DoS
        val estimatedByteSize = MockCommandContract.estimateBundleByteSize(rely)
        if (estimatedByteSize > 256_000) {
            Logger.warn("[RemoteCommandHandler] oversized bundle: estimated ${estimatedByteSize} bytes, rejecting")
            return false
        }
        if (!replayGuard.accept(sessionKey, rely)) {
            Logger.warn("[RemoteCommandHandler] rejected unauthenticated or replayed command: $commandId")
            return false
        }
        val scope = sessionScopes[sessionKey] ?: SessionScope.CONTROL
        if (!isCommandAllowedForScope(scope, commandId)) {
            Logger.warn("[RemoteCommandHandler] rejected command outside session scope: scope=$scope command=$commandId")
            return false
        }
        if (!RemoteCommandSecurity.decryptSensitivePayload(rely, sessionKey)) {
            Logger.warn("[RemoteCommandHandler] rejected command with invalid encrypted payload: $commandId")
            return false
        }
        try {
            MockCommandContract.validateDomainCommand(commandId, rely)
        } catch (e: IllegalArgumentException) {
            val failure = domainValidationFailureSummary(e)
            structuredLog("error", commandId, "invalid command payload: $failure", rely)
            if (MockCommandContract.isDomainCommand(commandId)) {
                MockAuditTrail.skip("domain.validation", "$commandId rejected: $failure")
            }
            return false
        }
        val proxyRelay = if (commandId in proxyCommandIds) Bundle(rely) else null
        relayToProxyBinders(commandId, proxyRelay)
        MockProbeLog.ipcReceived(commandId, "processing")
        val handled = try {
            when (commandId) {
            "set_proxy" -> {
                val proxy = rely.getBinder("proxy")
                if (proxy == null) {
                    Logger.warn("[RemoteCommandHandler] set_proxy missing binder")
                    false
                } else {
                    val count = registerProxyBinder(proxy, sessionKey)
                    Logger.info("[RemoteCommandHandler] set_proxy registered, count=$count")
                    true
                }
            }
            MockCommandContract.Commands.PUT_CONFIG -> {
                val previousRinexPath = FakeLoc.rinexPath
                MockCommandContract.applyPutConfig(rely)
                SystemSensorManagerHook.updateStepGoal()
                if (previousRinexPath != FakeLoc.rinexPath) {
                    LocationServiceHook.clearSatelliteStates()
                    Logger.info("[RemoteCommandHandler] put_config refreshed RINEX path: ${FakeLoc.rinexPath ?: "(none)"}")
                }
                FakeLoc.rinexPath?.let { path ->
                    val file = File(path)
                    Logger.info(
                        "[RemoteCommandHandler] put_config RINEX file state: path=$path, " +
                            "exists=${file.exists()}, canRead=${file.canRead()}, length=${file.length()}"
                    )
                }
                Logger.info(
                    "[RemoteCommandHandler] put_config sensor state: hookSensor=${FakeLoc.hookSensor}, " +
                        "motion=${FakeLoc.sensorData.enableMotionMock}, step=${FakeLoc.enableStepCountMock}, " +
                        "stepCount=${FakeLoc.stepCount}, shake=${FakeLoc.enableShakeMock}, keepWalking=${FakeLoc.keepWalkingWaveform}"
                )
                Logger.info("[RemoteCommandHandler] put_config applied")
                MockProbeLog.configSync("put_config: enable=${FakeLoc.enable} stepMock=${FakeLoc.enableStepCountMock}")
                true
            }
            MockCommandContract.Commands.SYNC_CONFIG -> {
                MockCommandContract.populateSyncReply(rely, includeSensitive = scope == SessionScope.CONTROL)
                Logger.info("[RemoteCommandHandler] sync_config populated")
                true
            }
            MockCommandContract.Commands.UPDATE_WIFI -> {
                if (BuildConfig.ENABLE_WIFI_MOCK) {
                    handleUpdateWifi(rely)
                } else {
                    FakeLoc.mockWifiList = emptyList()
                    Logger.warn("[RemoteCommandHandler] update_wifi ignored: WiFi mock feature disabled")
                    false
                }
            }
            MockCommandContract.Commands.LOCATION_UPDATE -> handleLocationUpdate(rely)
            MockCommandContract.Commands.GNSS_UPDATE_STATUS -> handleGnssUpdateStatus(rely)
            MockCommandContract.Commands.NMEA_UPDATE_STREAM -> handleNmeaUpdateStream(rely)
            MockCommandContract.Commands.WIFI_UPDATE_CONNECTED -> handleWifiUpdateConnected(rely)
            MockCommandContract.Commands.WIFI_UPDATE_SCAN -> handleWifiUpdateScan(rely)
            MockCommandContract.Commands.CELL_UPDATE -> handleCellUpdate(rely)
            MockCommandContract.Commands.BLE_UPDATE -> handleBleUpdate(rely)
            MockCommandContract.Commands.NFC_REPLAY -> handleDisabledDomainCommand(commandId, "NFC")
            MockCommandContract.Commands.START,
            MockCommandContract.Commands.STOP,
            MockCommandContract.Commands.IS_START,
            MockCommandContract.Commands.START_GNSS_MOCK,
            MockCommandContract.Commands.STOP_GNSS_MOCK,
            MockCommandContract.Commands.IS_GNSS_START,
            MockCommandContract.Commands.START_WIFI_MOCK,
            MockCommandContract.Commands.STOP_WIFI_MOCK,
            MockCommandContract.Commands.IS_WIFI_MOCK_START,
            MockCommandContract.Commands.PING -> {
                lifecycleController.handle(commandId, rely)
            }
            MockCommandContract.Commands.DUMP_AUDIT -> {
                val lastN = rely.getInt("last_n", 0)
                val dump = if (lastN > 0) MockAuditTrail.dumpLast(lastN) else MockAuditTrail.dump()
                rely.putString("audit_dump", dump)
                Logger.info("[RemoteCommandHandler] audit dump requested, ${dump.lines().size} lines")
                true
            }
            MockCommandContract.Commands.TELEMETRY_DUMP -> {
                val lastN = rely.getInt("last_n", 256)
                val includePreviousBoot = rely.getBoolean("include_previous_boot", false)
                val dump = FlightRecorder.dump(lastN, includePreviousBoot)
                rely.putString(MockCommandContract.Keys.TELEMETRY_JSONL, dump)
                FlightRecorder.state(
                    stepId = "IPC",
                    detail = "TELEMETRY_DUMP",
                    extra = mapOf("last_n" to lastN, "include_previous_boot" to includePreviousBoot),
                )
                Logger.info("[RemoteCommandHandler] telemetry dump requested, ${dump.lines().size} lines")
                true
            }
            MockCommandContract.Commands.GRANT_LOG_EXPORT -> {
                handleGrantLogExport(sessionKey, rely)
            }
            MockCommandContract.Commands.EXPORT_LOGS -> {
                val result = handleExportLogs(sessionKey, rely)
                Logger.info("[RemoteCommandHandler] export_logs completed, result=$result")
                result
            }
            MockCommandContract.Commands.GET_SPEED -> {
                rely.putFloat("speed", FakeLoc.speed.toFloat())
                true
            }
            MockCommandContract.Commands.GET_BEARING -> {
                rely.putFloat("bearing", FakeLoc.bearing.toFloat())
                true
            }

            MockCommandContract.Commands.UPDATE_RINEX -> {
                val rinexPath = rely.getString("rinex_path") ?: run {
                    Logger.warn("[RemoteCommandHandler] update_rinex: missing rinex_path")
                    return@handleAuthenticatedInstruction false
                }
                Logger.info("[RemoteCommandHandler] update_rinex: $rinexPath")
                FakeLoc.rinexPath = rinexPath
                LocationServiceHook.clearSatelliteStates()
                true
            }
            // ---- Legacy command compatibility shim ----
            // These commands existed in the original kyshu handler but are not
            // part of MockCommandContract. Mapped for backward compatibility.
            "get_location" -> {
                rely.putDouble("lat", FakeLoc.latitude)
                rely.putDouble("lon", FakeLoc.longitude)
                true
            }
            "get_listener_size" -> {
                rely.putInt("size", LocationServiceHook.locationListeners.size)
                true
            }
            "get_altitude" -> {
                rely.putDouble("altitude", FakeLoc.altitude)
                true
            }
            "set_speed_amp" -> {
                FakeLoc.speedAmplitude = MockCommandContract.requireSpeed(
                    MockCommandContract.Extract.requireDouble(rely, "speed_amplitude"),
                    "speed_amplitude"
                )
                true
            }
            "set_altitude" -> {
                FakeLoc.altitude = MockCommandContract.requireAltitude(
                    MockCommandContract.Extract.requireDouble(rely, "altitude"),
                    "altitude"
                )
                true
            }
            "set_speed" -> {
                FakeLoc.speed = MockCommandContract.requireSpeed(
                    MockCommandContract.Extract.requireDouble(rely, "speed"),
                    "speed"
                )
                true
            }
            "set_bearing" -> {
                synchronized(FakeLoc) {
                    FakeLoc.bearing = MockCommandContract.requireBearing(
                        MockCommandContract.Extract.requireDouble(rely, "bearing"),
                        "bearing"
                    )
                    FakeLoc.hasBearings = true
                }
                true
            }
            "move" -> {
                val distance = MockCommandContract.Extract.requireDouble(rely, "n")
                if (distance == 0.0) return@handleAuthenticatedInstruction true
                val bearing = if (rely.containsKey("bearing")) {
                    MockCommandContract.requireBearing(MockCommandContract.Extract.requireDouble(rely, "bearing"), "bearing")
                } else {
                    FakeLoc.bearing
                }
                val newLoc = FakeLoc.moveLocation(
                    n = distance,
                    angle = bearing
                )
                synchronized(FakeLoc) {
                    FakeLoc.bearing = bearing
                    FakeLoc.hasBearings = true
                }
                if (FakeLoc.isSystemServerProcess) {
                    LocationServiceHook.callOnLocationChanged()
                }
                true
            }
            "update_location" -> {
                val mode = rely.getString("mode")
                val requiresCoordinates = mode != "random"
                val newLat = if (requiresCoordinates) {
                    MockCommandContract.requireLatitude(MockCommandContract.Extract.requireDouble(rely, "lat"), "lat")
                } else {
                    0.0
                }
                val newLon = if (requiresCoordinates) {
                    MockCommandContract.requireLongitude(MockCommandContract.Extract.requireDouble(rely, "lon"), "lon")
                } else {
                    0.0
                }
                var updatedLat: Double
                var updatedLon: Double
                when (mode) {
                    "+" -> {
                        updatedLat = newLat + FakeLoc.latitude
                        updatedLon = newLon + FakeLoc.longitude
                    }
                    "-" -> {
                        updatedLat = FakeLoc.latitude - newLat
                        updatedLon = FakeLoc.longitude - newLon
                    }
                    "*" -> {
                        updatedLat = newLat * FakeLoc.latitude
                        updatedLon = newLon * FakeLoc.longitude
                    }
                    "/" -> {
                        if (newLat == 0.0 || newLon == 0.0) return@handleAuthenticatedInstruction false
                        updatedLat = newLat / FakeLoc.latitude
                        updatedLon = newLon / FakeLoc.longitude
                    }
                    "=" -> {
                        updatedLat = newLat
                        updatedLon = newLon
                    }
                    "random" -> {
                        updatedLat = Random.nextDouble(-90.0, 90.0)
                        updatedLon = Random.nextDouble(-180.0, 180.0)
                    }
                    else -> return@handleAuthenticatedInstruction false
                }
                synchronized(FakeLoc) {
                    FakeLoc.latitude = updatedLat
                    FakeLoc.longitude = updatedLon
                }
                true
            }
            "broadcast_location" -> {
                LocationServiceHook.callOnLocationChanged()
                true
            }
            else -> {
                Logger.warn("[RemoteCommandHandler] unknown command: $commandId")
                false
            }
            }
        } catch (e: IllegalArgumentException) {
            val failure = domainValidationFailureSummary(e)
            structuredLog("error", commandId, "invalid command payload: $failure", rely)
            auditDomainCommandFailure(commandId, rely, "handler_exception:$failure")
            false
        }
        if (!handled) {
            auditDomainCommandFailure(commandId, rely, "handler_false")
        }
        if (handled) {
            if (!RemoteCommandSecurity.encryptSensitivePayload(rely, sessionKey)) {
                Logger.warn("[RemoteCommandHandler] failed to encrypt response payload: $commandId")
                return false
            }
        }
        return handled
    }

    private fun isCommandAllowedForScope(scope: SessionScope, commandId: String): Boolean {
        return scope == SessionScope.CONTROL || commandId in syncOnlyCommandIds
    }

    private fun domainValidationFailureSummary(error: IllegalArgumentException): String {
        return error.message
            ?.substringBefore(':')
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
    }

    private fun auditDomainCommandFailure(commandId: String, rely: Bundle, reason: String) {
        if (!MockCommandContract.isDomainCommand(commandId)) return
        MockAuditTrail.skip(
            "domain.failure",
            "$commandId failed reason=$reason fields=${domainPayloadFieldCount(rely)} bytes=${domainPayloadSize(rely)}",
        )
    }

    private fun isSensorAppSyncExchangeAllowed(uid: Int, rely: Bundle): Boolean {
        if (uid < 10_000) return false
        if (rely.getString(FIELD_REQUESTED_SCOPE) != SCOPE_SENSOR_APP) return false
        val packageName = rely.getString(FIELD_FROM_PACKAGE)
            ?.takeIf { it.length in 1..128 && it.all(::isSafePackageChar) }
            ?: return false
        val processName = rely.getString(FIELD_FROM_PROCESS)
            ?.takeIf { it.length in 1..160 && it.all(::isSafeProcessChar) }
            ?: return false
        val uidPackages = BinderUtils.getUidPackageNames(uid = uid) ?: return false
        val allowed = uidPackages.contains(packageName)
        if (!allowed) {
            Logger.warn(
                "[RemoteCommandHandler] restricted sync exchange denied: " +
                    "uid=$uid package=$packageName process=$processName uidPackages=${uidPackages.joinToString()}"
            )
        }
        return allowed
    }

    private fun isSafePackageChar(char: Char): Boolean {
        return char in 'a'..'z' ||
            char in 'A'..'Z' ||
            char in '0'..'9' ||
            char == '_' ||
            char == '.'
    }

    private fun isSafeProcessChar(char: Char): Boolean {
        return isSafePackageChar(char) || char == ':'
    }



    private fun relayToProxyBinders(commandId: String, relay: Bundle?) {
        if (relay == null) return
        val sessions = snapshotProxyBinders()
        sessions.forEach { proxy ->
            val proxyRelay = Bundle(relay)
            try {
                proxyRelayExecutor.execute {
                    relayToProxyBinder(commandId, proxy, proxyRelay)
                }
            } catch (_: RejectedExecutionException) {
                logProxyRelayDropped(commandId)
            }
        }
    }

    private fun registerProxyBinder(binder: IBinder, sessionKey: String): Int {
        synchronized(proxyBinders) {
            // No Binder calls under this lock: a frozen peer can exhaust system_server's
            // Binder pool even when the eventual relay uses FLAG_ONEWAY.
            proxyBinders.removeIf { proxy -> proxy.binder === binder }
            while (proxyBinders.size >= MAX_PROXY_BINDERS) {
                proxyBinders.removeAt(0)
            }
            proxyBinders.add(ProxyBinderSession(binder, sessionKey))
            return proxyBinders.size
        }
    }

    private fun snapshotProxyBinders(): List<ProxyBinderSession> {
        synchronized(proxyBinders) {
            return proxyBinders.toList()
        }
    }

    private fun relayToProxyBinder(commandId: String, proxy: ProxyBinderSession, proxyRelay: Bundle) {
        val binder = proxy.binder
        // Drop work queued for a replaced/evicted session without probing the peer.
        if (synchronized(proxyBinders) { proxyBinders.none { it === proxy } }) return
        if (!RemoteCommandSecurity.attachEnvelope(
                bundle = proxyRelay,
                sessionKey = proxy.sessionKey,
                sequence = requestSeq.incrementAndGet(),
            )
        ) {
            Logger.error("[RemoteCommandHandler] failed to sign relay $commandId for proxy binder")
            removeProxyBinder(proxy)
            return
        }
        val data = Parcel.obtain()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            // isBinderAlive is a local cached check for BinderProxy. Never pingBinder:
            // ping is synchronous IPC, including for frozen processes.
            if (!binder.isBinderAlive) {
                removeProxyBinder(proxy)
                return
            }
            data.writeString(proxy.sessionKey)
            data.writeBundle(proxyRelay)
            if (!binder.transact(1, data, null, IBinder.FLAG_ONEWAY)) {
                removeProxyBinder(proxy)
                return
            }
            val costMs = SystemClock.elapsedRealtime() - startedAt
            if (costMs > SLOW_PROXY_RELAY_MS) {
                Logger.warn("[RemoteCommandHandler] slow proxy relay command=$commandId cost=${costMs}ms")
            }
        } catch (t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            Logger.error("[RemoteCommandHandler] failed to relay $commandId to proxy binder", t)
            removeProxyBinder(proxy)
        } finally {
            data.recycle()
        }
    }

    private fun removeProxyBinder(proxy: ProxyBinderSession) {
        synchronized(proxyBinders) {
            proxyBinders.remove(proxy)
        }
    }

    private fun logProxyRelayDropped(commandId: String) {
        val dropped = proxyRelayDropCount.incrementAndGet()
        if (dropped == 1L || dropped % 64L == 0L) {
            Logger.warn("[RemoteCommandHandler] proxy relay queue full; dropped=$dropped command=$commandId")
        }
    }

    internal fun clearProxyBindersForTest() {
        proxyRelayExecutor.queue.clear()
        synchronized(proxyBinders) {
            proxyBinders.clear()
        }
        exportConsents.clear()
    }

    private fun handleUpdateWifi(rely: Bundle): Boolean {
        val ssids = rely.getStringArrayList("ssids") ?: return false
        val bssids = rely.getStringArrayList("bssids") ?: return false
        val rssis = rely.getIntegerArrayList("rssis") ?: return false
        val freqs = rely.getIntegerArrayList("freqs") ?: return false
        val caps = rely.getStringArrayList("caps") ?: return clearMalformedWifiPayload()
        val standards = rely.getIntegerArrayList("standards") ?: return clearMalformedWifiPayload()
        val widths = rely.getIntegerArrayList("widths") ?: return clearMalformedWifiPayload()

        val count = ssids.size
        if (count > 500) {
            Logger.warn("[RemoteCommandHandler] update_wifi count=$count exceeds limit=500, rejecting")
            return false
        }
        if (ssids.any { (it?.length ?: 0) > 32 }) { Logger.warn("[RemoteCommandHandler] SSID exceeds 32 bytes"); return false }
        if (bssids.any { (it?.length ?: 0) > 17 }) { Logger.warn("[RemoteCommandHandler] BSSID exceeds 17 bytes"); return false }
        if (caps.any { (it?.length ?: 0) > 256 }) { Logger.warn("[RemoteCommandHandler] caps string exceeds 256 bytes"); return false }
        if (caps.size != count || standards.size != count || widths.size != count) {
            Logger.warn("[RemoteCommandHandler] update_wifi detail array size mismatch: ssids=$count caps=${caps.size} standards=${standards.size} widths=${widths.size}")
            return clearMalformedWifiPayload()
        }
        if (bssids.size != count || rssis.size != count || freqs.size != count) {
            Logger.warn("[RemoteCommandHandler] update_wifi array size mismatch: ssids=$count bssids=${bssids.size} rssis=${rssis.size} freqs=${freqs.size}")
            return false
        }

        val list = ArrayList<MockWifiData>(count)
        for (i in 0 until count) {
            list.add(MockWifiData(
                ssid = ssids[i] ?: "",
                bssid = bssids[i] ?: "",
                rssi = rssis[i],
                frequency = freqs[i],
                channelWidth = widths[i],
                capabilities = caps[i] ?: "",
                standard = standards[i],
            ))
        }
        FakeLoc.mockWifiList = list
        Logger.info("[RemoteCommandHandler] update_wifi: updated ${list.size} entries")
        return true
    }

    private fun handleLocationUpdate(rely: Bundle): Boolean {
        synchronized(FakeLoc) {
            with(MockCommandContract) {
                val keys = MockCommandContract.Keys
                val extract = MockCommandContract.Extract
                if (rely.containsKey(keys.ENABLE)) FakeLoc.enable = rely.getBoolean(keys.ENABLE)
                if (rely.containsKey(keys.LATITUDE)) {
                    FakeLoc.latitude = requireLatitude(extract.requireDouble(rely, keys.LATITUDE))
                }
                if (rely.containsKey(keys.LONGITUDE)) {
                    FakeLoc.longitude = requireLongitude(extract.requireDouble(rely, keys.LONGITUDE))
                }
                if (rely.containsKey(keys.ALTITUDE)) {
                    FakeLoc.altitude = requireAltitude(extract.requireDouble(rely, keys.ALTITUDE))
                }
                if (rely.containsKey(keys.SPEED)) {
                    FakeLoc.speed = requireSpeed(extract.requireDouble(rely, keys.SPEED))
                }
                if (rely.containsKey(keys.ACCURACY)) {
                    FakeLoc.accuracy = requireAccuracy(extract.requireFloat(rely, keys.ACCURACY))
                }
                if (rely.containsKey(keys.BEARING)) {
                    FakeLoc.bearing = requireBearing(extract.requireDouble(rely, keys.BEARING))
                    FakeLoc.locationData.hasBearings = true
                }
            }
        }
        rely.putString("domain_state", if (FakeLoc.enable) "active" else "configured")
        MockAuditTrail.config("domain.location", "updated fields=${domainPayloadFieldCount(rely)}")
        return true
    }

    private fun handleGnssUpdateStatus(rely: Bundle): Boolean {
        if (!BuildConfig.ENABLE_GNSS_MOCK) return handleDisabledDomainCommand(rely.getString("command_id") ?: "gnss", "GNSS")
        if (rely.containsKey(MockCommandContract.Keys.ENABLE_MOCK_GNSS)) {
            FakeLoc.enableMockGnss = rely.getBoolean(MockCommandContract.Keys.ENABLE_MOCK_GNSS)
        } else {
            FakeLoc.enableMockGnss = true
        }
        if (rely.containsKey(MockCommandContract.Keys.MIN_SATELLITES)) {
            FakeLoc.minSatellites = rely.getInt(MockCommandContract.Keys.MIN_SATELLITES)
        }
        rely.putBoolean("enable_mock_gnss", FakeLoc.enableMockGnss)
        rely.putInt("min_satellites", FakeLoc.minSatellites)
        rely.putString("domain_state", if (FakeLoc.enableMockGnss) "active" else "configured")
        MockAuditTrail.config("domain.gnss", "updated minSatellites=${FakeLoc.minSatellites}")
        return true
    }

    private fun handleNmeaUpdateStream(rely: Bundle): Boolean {
        if (!BuildConfig.ENABLE_NMEA_MOCK) return handleDisabledDomainCommand(rely.getString("command_id") ?: "nmea", "NMEA")
        if (rely.containsKey(MockCommandContract.Keys.ENABLE_NMEA)) {
            FakeLoc.enableNMEA = rely.getBoolean(MockCommandContract.Keys.ENABLE_NMEA)
        } else if (rely.containsKey(MockCommandContract.Keys.PAYLOAD_HEX) || rely.containsKey(MockCommandContract.Keys.PAYLOAD_JSON)) {
            FakeLoc.enableNMEA = true
        }
        rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
        rely.putString("domain_state", if (FakeLoc.enableNMEA) "active" else "configured")
        MockAuditTrail.config("domain.nmea", "updated payloadBytes=${domainPayloadSize(rely)}")
        return true
    }

    private fun handleWifiUpdateConnected(rely: Bundle): Boolean {
        if (!BuildConfig.ENABLE_WIFI_MOCK) {
            FakeLoc.enableMockWifi = false
            return handleDisabledDomainCommand(MockCommandContract.Commands.WIFI_UPDATE_CONNECTED, "WLAN_CONNECTED")
        }
        val keys = MockCommandContract.Keys
        if (rely.containsKey(keys.ENABLE_MOCK_WIFI)) FakeLoc.enableMockWifi = rely.getBoolean(keys.ENABLE_MOCK_WIFI) else FakeLoc.enableMockWifi = true
        if (rely.containsKey(keys.HOOK_WIFI)) FakeLoc.hookWifi = rely.getBoolean(keys.HOOK_WIFI)
        if (rely.containsKey(keys.MOCK_SSID)) FakeLoc.mockSSID = rely.getString(keys.MOCK_SSID).orEmpty()
        if (rely.containsKey(keys.MOCK_BSSID)) FakeLoc.mockBSSID = rely.getString(keys.MOCK_BSSID).orEmpty()
        if (rely.containsKey(keys.MOCK_MAC)) FakeLoc.mockMac = rely.getString(keys.MOCK_MAC).orEmpty()
        if (rely.containsKey(keys.MOCK_IP)) FakeLoc.mockIp = rely.getString(keys.MOCK_IP).orEmpty()
        rely.putString("domain_state", if (FakeLoc.enableMockWifi) "active" else "configured")
        MockAuditTrail.config("domain.wifi.connected", "updated ssidLen=${FakeLoc.mockSSID.length}")
        return true
    }

    private fun handleWifiUpdateScan(rely: Bundle): Boolean {
        if (!BuildConfig.ENABLE_WIFI_MOCK) {
            FakeLoc.mockWifiList = emptyList()
            return handleDisabledDomainCommand(MockCommandContract.Commands.WIFI_UPDATE_SCAN, "WLAN_SCAN")
        }
        if (rely.containsKey(MockCommandContract.Keys.PAYLOAD_JSON)) {
            FakeLoc.mockWifiList = parseWifiScanPayload(rely.getString(MockCommandContract.Keys.PAYLOAD_JSON).orEmpty())
            FakeLoc.mockWifiScanCount = FakeLoc.mockWifiList.size.coerceAtLeast(1)
            FakeLoc.enableMockWifi = true
            rely.putInt("applied_count", FakeLoc.mockWifiList.size)
            rely.putString("domain_state", "active")
            MockAuditTrail.config("domain.wifi.scan", "updated count=${FakeLoc.mockWifiList.size}")
            return true
        }
        val updated = handleUpdateWifi(rely)
        if (updated) {
            FakeLoc.enableMockWifi = true
            rely.putInt("applied_count", FakeLoc.mockWifiList.size)
            rely.putString("domain_state", "active")
        }
        return updated
    }

    private fun handleCellUpdate(rely: Bundle): Boolean {
        if (!BuildConfig.ENABLE_CELL_MOCK) return handleDisabledDomainCommand(MockCommandContract.Commands.CELL_UPDATE, "CELL")
        when (rely.getString(MockCommandContract.Keys.DOMAIN_MODE)) {
            "mask_real" -> {
                FakeLoc.hideBaseStation = true
                FakeLoc.needDowngradeToCdma = false
            }
            "replace_with_profile" -> {
                FakeLoc.hideBaseStation = false
                FakeLoc.needDowngradeToCdma = false
            }
            "off" -> {
                FakeLoc.hideBaseStation = false
                FakeLoc.needDowngradeToCdma = false
            }
        }
        rely.putBoolean(MockCommandContract.Keys.HIDE_BASE_STATION, FakeLoc.hideBaseStation)
        rely.putString("domain_state", if (FakeLoc.hideBaseStation) "active" else "configured")
        MockAuditTrail.config("domain.cell", "updated mode=${rely.getString(MockCommandContract.Keys.DOMAIN_MODE) ?: "default"}")
        return true
    }

    private fun handleBleUpdate(rely: Bundle): Boolean {
        if (!BuildConfig.ENABLE_BLE_MOCK) {
            FakeLoc.enableBleMock = false
            FakeLoc.bleDevices = emptyList()
            return handleDisabledDomainCommand(MockCommandContract.Commands.BLE_UPDATE, "BLE")
        }

        val profiles = if (rely.containsKey(MockCommandContract.Keys.PAYLOAD_JSON)) {
            parseBleDevicePayload(rely.getString(MockCommandContract.Keys.PAYLOAD_JSON).orEmpty())
        } else {
            emptyList()
        }
        val enabled = rely.getBoolean(MockCommandContract.Keys.ENABLE, profiles.isNotEmpty())
        FakeLoc.enableBleMock = enabled
        FakeLoc.bleDevices = if (enabled) profiles else emptyList()

        rely.putInt("applied_count", FakeLoc.bleDevices.size)
        rely.putString("domain_state", if (FakeLoc.enableBleMock) "active" else "configured")
        MockAuditTrail.config("domain.ble", "updated count=${FakeLoc.bleDevices.size}")
        return true
    }

    private fun handleDisabledDomainCommand(commandId: String, domain: String): Boolean {
        Logger.warn("[RemoteCommandHandler] $commandId ignored: $domain mock feature disabled")
        MockAuditTrail.skip("domain.$domain", "$commandId disabled by build-time feature flag")
        return false
    }

    private fun parseWifiScanPayload(payloadJson: String): List<MockWifiData> {
        if (payloadJson.isBlank()) return emptyList()
        val array = JSONArray(payloadJson)
        val result = ArrayList<MockWifiData>(array.length().coerceAtMost(32))
        for (index in 0 until array.length().coerceAtMost(32)) {
            val item = array.optJSONObject(index) ?: continue
            result += MockWifiData(
                ssid = item.optString("ssid", ""),
                bssid = item.optString("bssid", FakeLoc.DEFAULT_MAC),
                rssi = item.optInt("rssi", item.optInt("rssiDbm", -65)),
                frequency = item.optInt("frequency", item.optInt("frequencyMhz", 2412)),
                channelWidth = item.optInt("channelWidth", 0),
                capabilities = item.optString("capabilities", ""),
                standard = item.optInt("standard", 0),
            )
        }
        return result
    }

    private fun parseBleDevicePayload(payloadJson: String): List<BleDeviceProfile> {
        val trimmed = payloadJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val configJson = if (trimmed.startsWith("[")) {
            """{"schema_version":${MockDomainConfig.CURRENT_SCHEMA_VERSION},"ble_devices":$trimmed}"""
        } else {
            trimmed
        }
        val config = MockDomainConfigJson.importJson(configJson, legalize = true)
        val issues = config.validate().filter { it.domain == MockDomain.BLE }
        if (issues.isNotEmpty()) {
            throw IllegalArgumentException("Invalid BLE payload: ${issues.first().field} ${issues.first().reason}")
        }
        return config.bleDevices.take(MockCommandContract.MAX_BLE_PROFILES)
    }

    private fun domainPayloadFieldCount(rely: Bundle): Int =
        rely.keySet().count { !it.startsWith("__") && it != "command_id" }

    private fun domainPayloadSize(rely: Bundle): Int =
        MockCommandContract.estimateBundleByteSize(rely)

    private fun clearMalformedWifiPayload(): Boolean {
        FakeLoc.mockWifiList = emptyList()
        return false
    }

    private fun handleGrantLogExport(sessionKey: String, rely: Bundle): Boolean {
        if (!rely.getBoolean("user_authorized", false)) {
            Logger.warn("[RemoteCommandHandler] grant_log_export denied: user_authorized=false")
            rely.putString("export_error", "user_authorized flag required")
            return false
        }
        pruneExportConsents()
        val token = RemoteCommandSecurity.newNonce()
        exportConsents[token] = ExportConsent(
            sessionKey = sessionKey,
            expiresAtMs = System.currentTimeMillis() + EXPORT_CONSENT_WINDOW_MS,
        )
        rely.putString(FIELD_EXPORT_CONSENT_TOKEN, token)
        rely.putLong("export_consent_expires_ms", EXPORT_CONSENT_WINDOW_MS)
        tv.kyshu.xposed.utils.Log.audit("EXPORT", "grantLogExport", "consent_granted ttl_ms=$EXPORT_CONSENT_WINDOW_MS")
        return true
    }

    private fun consumeExportConsent(sessionKey: String, rely: Bundle): Boolean {
        pruneExportConsents()
        val token = rely.getString(FIELD_EXPORT_CONSENT_TOKEN)?.takeIf { it.isNotBlank() } ?: return false
        val consent = exportConsents.remove(token) ?: return false
        val now = System.currentTimeMillis()
        return consent.sessionKey == sessionKey && consent.expiresAtMs >= now
    }

    private fun pruneExportConsents() {
        val now = System.currentTimeMillis()
        exportConsents.entries.removeIf { it.value.expiresAtMs < now }
    }

    private fun handleExportLogs(sessionKey: String, rely: Bundle): Boolean {
        return kotlin.runCatching {
            // Authorization gate: client must explicitly opt-in and present a
            // short-lived, one-time consent token issued over an authenticated
            // CONTROL session.
            if (!rely.getBoolean("user_authorized", false)) {
                Logger.warn("[RemoteCommandHandler] export_logs denied: user_authorized=false")
                rely.putString("export_error", "user_authorized flag required")
                return@runCatching false
            }
            if (!consumeExportConsent(sessionKey, rely)) {
                Logger.warn("[RemoteCommandHandler] export_logs denied: invalid consent token")
                rely.putString("export_error", "valid export consent token required")
                return@runCatching false
            }

            val context = FakeLoc.getContext() ?: run {
                Logger.error("[RemoteCommandHandler] export_logs: no context")
                rely.putString("export_error", "no context")
                return@runCatching false
            }

            val sb = StringBuilder(64 * 1024)
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())

            sb.appendLine("============================================")
            sb.appendLine("PTL Log Export - $ts")
            sb.appendLine("============================================")
            sb.appendLine()

            // 1) FlightRecorder telemetry (include previous boot)
            sb.appendLine("=== FlightRecorder Telemetry ===")
            val telemetry = FlightRecorder.dump(lastN = 256, includePreviousBoot = true)
            sb.appendLine(LogRedactor.redactForExport(telemetry.ifEmpty { "(no telemetry)" }))
            sb.appendLine()

            // 2) Hook install report
            sb.appendLine("=== HookInstallReport ===")
            sb.appendLine(LogRedactor.redactForExport(FlightRecorder.dumpHookInstallReport()))
            sb.appendLine()

            // 3) MockAuditTrail
            sb.appendLine("=== MockAuditTrail ===")
            val audit = MockAuditTrail.dump()
            sb.appendLine(LogRedactor.redactForExport(audit.ifEmpty { "(no audit events)" }))
            sb.appendLine()

            // 4) CrashForensics
            sb.appendLine("=== CrashForensics ===")
            val crash = CrashForensics.readCrashLog()
            sb.appendLine(LogRedactor.redactForExport(crash.ifEmpty { "(no crash log)" }))
            sb.appendLine()

            // 5) LogRouter files (latest 3)
            sb.appendLine("=== LogRouter Files ===")
            val logDir = context.filesDir.resolve("logs")
            if (logDir.isDirectory) {
                val logFiles = logDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("svc_") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(3) ?: emptyList()
                if (logFiles.isEmpty()) {
                    sb.appendLine("(no LogRouter files)")
                } else {
                    for (file in logFiles) {
                        sb.appendLine("--- ${file.name} (${file.length() / 1024}KB) ---")
                        try {
                            // Cap each file at 16KB to keep export bounded
                            val content = if (file.length() > 16 * 1024) {
                                file.readText(Charsets.UTF_8).takeLast(16 * 1024)
                                    .let { "(truncated to last 16KB)\n$it" }
                            } else {
                                file.readText(Charsets.UTF_8)
                            }
                            sb.appendLine(LogRedactor.redactForExport(content))
                        } catch (e: Exception) {
                            sb.appendLine("(read error: ${LogRedactor.throwableSummary(e)})")
                        }
                        sb.appendLine()
                    }
                }
            } else {
                sb.appendLine("(LogRouter directory not found)")
            }

            // Write export file
            val exportDir = context.filesDir.resolve("logs/export").also { it.mkdirs() }
            cleanupExportDir(exportDir)
            val exportFile = File(exportDir, "export_$ts.log")
            val safeExport = boundedExportText(LogRedactor.redactForExport(sb.toString()))
            exportFile.writeText(safeExport, Charsets.UTF_8)
            cleanupExportDir(exportDir)

            val summary = mapOf(
                "telemetry_lines" to telemetry.lines().size,
                "audit_lines" to audit.lines().size,
                "crash_lines" to crash.lines().size,
                "total_bytes" to safeExport.toByteArray(Charsets.UTF_8).size,
            ).entries.joinToString(", ") { "${it.key}=${it.value}" }

            rely.putString("export_id", exportFile.name)
            rely.putString("export_path", "logs/export/${exportFile.name}")
            rely.putString("export_summary", summary)
            rely.putInt("export_size_bytes", safeExport.toByteArray(Charsets.UTF_8).size)
            Logger.info("[RemoteCommandHandler] export_logs: $summary")
            // Record export in audit trail for accountability
            tv.kyshu.xposed.utils.Log.audit("EXPORT", "handleExportLogs", summary)
            true
        }.onFailure { e ->
            Logger.error("[RemoteCommandHandler] export_logs failed", e)
            rely.putString("export_error", LogRedactor.throwableSummary(e))
        }.getOrDefault(false)
    }

    private fun boundedExportText(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_EXPORT_BYTES) return text
        val prefix = "(export truncated to last ${MAX_EXPORT_BYTES / 1024}KB)\n"
        val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
        val tailBudget = (MAX_EXPORT_BYTES - prefixBytes.size).coerceAtLeast(0)
        if (tailBudget == 0) return prefix.take(MAX_EXPORT_BYTES)
        var start = bytes.size - tailBudget
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) {
            start += 1
        }
        val tail = if (start < bytes.size) {
            String(bytes, start, bytes.size - start, Charsets.UTF_8)
        } else {
            ""
        }
        return prefix + tail
    }

    private fun cleanupExportDir(exportDir: File) {
        val cutoff = System.currentTimeMillis() - MAX_EXPORT_AGE_MS
        exportDir.listFiles { file -> file.isFile && file.name.startsWith("export_") && file.name.endsWith(".log") }
            ?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        val files = exportDir.listFiles { file -> file.isFile && file.name.startsWith("export_") && file.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.toMutableList() ?: return
        while (files.size > MAX_EXPORT_FILES) {
            files.removeLastOrNull()?.delete()
        }
        var totalBytes = files.sumOf { it.length() }
        while (totalBytes > MAX_EXPORT_TOTAL_BYTES && files.isNotEmpty()) {
            val removed = files.removeLast()
            totalBytes -= removed.length()
            removed.delete()
        }
    }
}
