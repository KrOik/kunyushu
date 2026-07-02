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
import tv.kyshu.dobby.Dobby
import tv.kyshu.xposed.hooks.LocationServiceHook
import tv.kyshu.xposed.security.RemoteCommandSecurity
import tv.kyshu.xposed.utils.LogRouter
import java.io.File
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.BinderUtils
import tv.kyshu.xposed.utils.CrashForensics
import tv.kyshu.xposed.utils.FlightRecorder
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.MockAuditTrail
import tv.kyshu.xposed.config.MockWifiData
import tv.kyshu.xposed.utils.MockProbeLog
import java.util.Collections
import kotlin.random.Random

object RemoteCommandHandler {
    private data class ProxyBinderSession(
        val binder: IBinder,
        val sessionKey: String,
    )

    @Volatile
    private var activeSessionKey: String = RemoteCommandSecurity.newSessionKey()
    internal val randomKey: String
        get() = activeSessionKey
    private val replayGuard = RemoteCommandSecurity.ReplayGuard()
    private val proxyBinders by lazy { Collections.synchronizedList(arrayListOf<ProxyBinderSession>()) }
    private val proxyCommandIds = setOf(
        MockCommandContract.Commands.PUT_CONFIG,
        MockCommandContract.Commands.UPDATE_WIFI,
        MockCommandContract.Commands.START,
        MockCommandContract.Commands.STOP,
        MockCommandContract.Commands.START_GNSS_MOCK,
        MockCommandContract.Commands.STOP_GNSS_MOCK,
        MockCommandContract.Commands.START_WIFI_MOCK,
        MockCommandContract.Commands.STOP_WIFI_MOCK,
        "set_speed_amp",
        "set_altitude",
        "set_speed",
        "set_bearing",
        "move",
        "update_location",
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
            set(value) { FakeLoc.enableMockWifi = value }
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
        return kotlin.runCatching {
            if (command == "exchange_key") {
                val userId = BinderUtils.getCallerUid()
                if (BinderUtils.isLocationProviderEnabled(userId)) {
                    val rawKey = rotateSessionKey()
                    RemoteCommandSecurity.putEncryptedSessionKey(rely, rawKey)
                } else {
                    Logger.warn("exchange_key: Permission denied for UID $userId")
                    false
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

    @Synchronized
    internal fun rotateSessionKey(): String {
        activeSessionKey = RemoteCommandSecurity.newSessionKey()
        replayGuard.registerSession(activeSessionKey)
        FlightRecorder.state("IPC", "SESSION_ROTATED")
        return activeSessionKey
    }

    internal fun registerSessionKey(sessionKey: String): Boolean {
        return replayGuard.registerSession(sessionKey)
    }

    internal fun handleSessionInstruction(
        sessionKey: String,
        rely: Bundle,
        replayGuard: RemoteCommandSecurity.ReplayGuard = this.replayGuard,
    ): Boolean {
        val commandId = rely.getString("command_id") ?: return false
        return handleAuthenticatedInstruction(sessionKey, commandId, rely, replayGuard)
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
        val estimatedByteSize = try {
            var size = 0
            for (key in rely.keySet()) {
                size += key.length * 2 // key chars * 2 bytes
                val value = rely.get(key)
                when (value) {
                    is String -> size += value.length * 2
                    is ByteArray -> size += value.size
                    is IntArray -> size += value.size * 4
                    is LongArray -> size += value.size * 8
                    is FloatArray -> size += value.size * 4
                    is DoubleArray -> size += value.size * 8
                    is Boolean -> size += 1
                    is Int -> size += 4
                    is Long -> size += 8
                    is Float -> size += 4
                    is Double -> size += 8
                    is java.util.ArrayList<*> -> {
                        value.forEach { if (it is String) size += it.length * 2 else size += 4 }
                    }
                    else -> size += 64
                }
            }
            size
        } catch (_: Exception) { 0 }
        if (estimatedByteSize > 256_000) {
            Logger.warn("[RemoteCommandHandler] oversized bundle: estimated ${estimatedByteSize} bytes, rejecting")
            return false
        }
        if (!replayGuard.accept(sessionKey, rely)) {
            Logger.warn("[RemoteCommandHandler] rejected unauthenticated or replayed command: $commandId")
            return false
        }
        if (!RemoteCommandSecurity.decryptSensitivePayload(rely, sessionKey)) {
            Logger.warn("[RemoteCommandHandler] rejected command with invalid encrypted payload: $commandId")
            return false
        }
        val proxyRelay = if (commandId in proxyCommandIds) Bundle(rely) else null
        relayToProxyBinders(commandId, proxyRelay)
        MockProbeLog.ipcReceived(commandId, "processing")
        val handled = when (commandId) {
            "set_proxy" -> {
                val proxy = rely.getBinder("proxy")
                if (proxy == null) {
                    Logger.warn("[RemoteCommandHandler] set_proxy missing binder")
                    false
                } else {
                    proxyBinders.add(ProxyBinderSession(proxy, sessionKey))
                    Logger.info("[RemoteCommandHandler] set_proxy registered, count=${proxyBinders.size}")
                    true
                }
            }
            MockCommandContract.Commands.PUT_CONFIG -> {
                MockCommandContract.applyPutConfig(rely)
                Logger.info("[RemoteCommandHandler] put_config applied")
                MockProbeLog.configSync("put_config: enable=${FakeLoc.enable} stepMock=${FakeLoc.enableStepCountMock}")
                true
            }
            MockCommandContract.Commands.SYNC_CONFIG -> {
                MockCommandContract.populateSyncReply(rely)
                Logger.info("[RemoteCommandHandler] sync_config populated")
                true
            }
            MockCommandContract.Commands.UPDATE_WIFI -> {
                handleUpdateWifi(rely)
            }
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
                val includePreviousBoot = rely.getBoolean("include_previous_boot", true)
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
            MockCommandContract.Commands.EXPORT_LOGS -> {
                val result = handleExportLogs(rely)
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
            MockCommandContract.Commands.LOAD_LIBRARY -> {
                handleLoadLibrary(rely)
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
                FakeLoc.speedAmplitude = rely.getDouble("speed_amplitude", FakeLoc.speedAmplitude)
                true
            }
            "set_altitude" -> {
                FakeLoc.altitude = rely.getDouble("altitude", FakeLoc.altitude)
                true
            }
            "set_speed" -> {
                FakeLoc.speed = rely.getDouble("speed", FakeLoc.speed)
                true
            }
            "set_bearing" -> {
                synchronized(FakeLoc) {
                    FakeLoc.bearing = rely.getDouble("bearing", FakeLoc.bearing)
                    FakeLoc.hasBearings = true
                }
                true
            }
            "move" -> {
                val distance = rely.getDouble("n", 0.0)
                if (distance == 0.0) return@handleAuthenticatedInstruction true
                val bearing = rely.getDouble("bearing", FakeLoc.bearing)
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
                val newLat = rely.getDouble("lat", 0.0)
                val newLon = rely.getDouble("lon", 0.0)
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
        if (handled) {
            if (!RemoteCommandSecurity.encryptSensitivePayload(rely, sessionKey)) {
                Logger.warn("[RemoteCommandHandler] failed to encrypt response payload: $commandId")
                return false
            }
        }
        return handled
    }

    private fun handleLoadLibrary(rely: Bundle): Boolean {
        val path = rely.getString("path") ?: run {
            rely.putString("result", "missing_path")
            return true
        }
        val result = runCatching {
            val allowedDir = File("/data/local/portal-lib").canonicalFile
            val target = File(path).canonicalFile
            val parent = target.parentFile?.canonicalFile
            if (target.name != "libportal.so" || parent?.path != allowedDir.path) {
                Logger.warn("[RemoteCommandHandler] load_library denied: $path")
                return@runCatching "denied"
            }
            if (!target.isFile || !target.canRead()) {
                Logger.warn("[RemoteCommandHandler] load_library missing or unreadable: ${target.path}")
                return@runCatching "missing"
            }
            if (Dobby.load(target.path)) {
                "success"
            } else {
                "failed:${Dobby.unavailableReason}"
            }
        }.getOrElse { e ->
            Logger.error("[RemoteCommandHandler] load_library failed", e)
            "failed:${e.javaClass.simpleName}"
        }
        rely.putString("result", result)
        return true
    }

    private fun relayToProxyBinders(commandId: String, relay: Bundle?) {
        if (relay == null || proxyBinders.isEmpty()) return
        synchronized(proxyBinders) {
            proxyBinders.removeIf { proxy ->
                val binder = proxy.binder
                if (!binder.isBinderAlive || !binder.pingBinder()) {
                    true
                } else {
                    val proxyRelay = Bundle(relay)
                    if (!RemoteCommandSecurity.attachEnvelope(
                            bundle = proxyRelay,
                            sessionKey = proxy.sessionKey,
                            sequence = requestSeq.incrementAndGet(),
                        )
                    ) {
                        Logger.error("[RemoteCommandHandler] failed to sign relay $commandId for proxy binder")
                        return@removeIf true
                    }
                    val data = Parcel.obtain()
                    try {
                        data.writeString(proxy.sessionKey)
                        data.writeBundle(proxyRelay)
                        binder.transact(1, data, null, 0)
                        false
                    } catch (t: Throwable) {
                        Logger.error("[RemoteCommandHandler] failed to relay $commandId to proxy binder", t)
                        true
                    } finally {
                        data.recycle()
                    }
                }
            }
        }
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

    private fun clearMalformedWifiPayload(): Boolean {
        FakeLoc.mockWifiList = emptyList()
        return false
    }

    private fun handleExportLogs(rely: Bundle): Boolean {
        return kotlin.runCatching {
            // Authorization gate: client must explicitly opt-in
            if (!rely.getBoolean("user_authorized", false)) {
                Logger.warn("[RemoteCommandHandler] export_logs denied: user_authorized=false")
                rely.putString("export_error", "user_authorized flag required")
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
            sb.appendLine("Portal Log Export — $ts")
            sb.appendLine("============================================")
            sb.appendLine()

            // 1) FlightRecorder telemetry (include previous boot)
            sb.appendLine("=== FlightRecorder Telemetry ===")
            val telemetry = FlightRecorder.dump(lastN = 256, includePreviousBoot = true)
            sb.appendLine(telemetry.ifEmpty { "(no telemetry)" })
            sb.appendLine()

            // 2) MockAuditTrail
            sb.appendLine("=== MockAuditTrail ===")
            val audit = MockAuditTrail.dump()
            sb.appendLine(audit.ifEmpty { "(no audit events)" })
            sb.appendLine()

            // 3) CrashForensics
            sb.appendLine("=== CrashForensics ===")
            val crash = CrashForensics.readCrashLog()
            sb.appendLine(crash.ifEmpty { "(no crash log)" })
            sb.appendLine()

            // 4) LogRouter files (latest 3)
            sb.appendLine("=== LogRouter Files ===")
            val logDir = context.filesDir.resolve("logs")
            if (logDir.isDirectory) {
                val logFiles = logDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("portal_") }
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
                            sb.appendLine(content)
                        } catch (e: Exception) {
                            sb.appendLine("(read error: ${e.message})")
                        }
                        sb.appendLine()
                    }
                }
            } else {
                sb.appendLine("(LogRouter directory not found)")
            }

            // Write export file
            val exportDir = context.filesDir.resolve("logs/export").also { it.mkdirs() }
            val exportFile = File(exportDir, "export_$ts.log")
            exportFile.writeText(sb.toString(), Charsets.UTF_8)

            val summary = mapOf(
                "telemetry_lines" to telemetry.lines().size,
                "audit_lines" to audit.lines().size,
                "crash_lines" to crash.lines().size,
                "total_bytes" to sb.length,
            ).entries.joinToString(", ") { "${it.key}=${it.value}" }

            rely.putString("export_path", exportFile.absolutePath)
            rely.putString("export_summary", summary)
            rely.putInt("export_size_bytes", sb.length)
            Logger.info("[RemoteCommandHandler] export_logs: $summary")
            // Record export in audit trail for accountability
            tv.kyshu.xposed.utils.Log.audit("EXPORT", "handleExportLogs", summary)
            true
        }.onFailure { e ->
            Logger.error("[RemoteCommandHandler] export_logs failed", e)
            rely.putString("export_error", e.message ?: e.javaClass.simpleName)
        }.getOrDefault(false)
    }
}
