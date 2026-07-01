/*
 * Copyright (C) 2025-2026 The KunYuShu Project
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
 *
 * N5 FIX (2026-06-30): Deep probe enhancements inspired by chunqiu2 methodology.
 * Adds /proc-level diagnostics: LSPosed injection verification, debugger attachment
 * detection, process state snapshot, and memory map analysis.
 * All probes are READ-ONLY — they only read /proc files. No hooks, no memory writes.
 */

package tv.kyshu.xposed

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.FlightRecorder
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.ChannelIdentity
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

object ReadinessProbe {

    // Expected .so file name patterns in the native library directory.
    private val SO_PATTERNS = listOf(
        Regex("^libkys_.*\\.so$"),
        Regex("^libkunyushu\\.so$"),
    )

    // LSPosed/Xposed markers to search for in /proc/self/maps.
    // Pattern from chunqiu2: file-level existence + memory-map verification.
    private val LSPOSED_MAPS_MARKERS = listOf(
        "XposedBridge", "lsposed", "lspd", "edxposed",
        "libkunyushu", "kunyushu", "de.robv.android.xposed",
        "org.lsposed",
    )

    // Filesystem paths that indicate Magisk/KernelSU environment.
    // Pattern from chunqiu2: path existence probe via stat/open.
    private val ROOT_ENV_PATHS = listOf(
        "/sys/kernel/ksu",
        "/data/adb/modules",
        "/data/adb/magisk",
    )

    private const val IPC_PROBE_TIMEOUT_MS = 500L

    // ---- Probe Result (enhanced) ----

    data class ProbeResult(
        val sensorMockAvailable: Boolean,
        val sensorMockDetail: String,
        val locationMockAvailable: Boolean,
        val locationMockDetail: String,
        val systemFrameworkDetected: Boolean,
        val systemFrameworkDetail: String,
        val nativeLibraryAvailable: Boolean,
        val nativeLibraryDetail: String,
        val deviceFingerprint: String,
        val deviceInWhitelist: Boolean,
        val contentProviderHealthy: Boolean,
        val contentProviderDetail: String,
        // N5: Deep probe additions
        val lsposedInCurrentProcess: Boolean,
        val lsposedMapsMatches: List<String>,
        val debuggerAttached: Boolean,
        val tracerPid: Int,
        val oomScoreAdj: Int,
        val processCgroup: String,
        val rootEnvironment: List<String>,
        val procSnapshot: Map<String, String>,
        val recommendations: List<String>,
    )

    // ---- Main probe entry ----

    fun probe(context: Context?): ProbeResult {
        val recommendations = mutableListOf<String>()

        // Probe 1: ContentProvider
        val (cpHealthy, cpDetail) = probeContentProvider()
        if (!cpHealthy) {
            recommendations.add(
                "ContentProvider unreachable. Ensure KunYuShu APK is installed and not force-stopped.")
        }

        // Probe 2: Native .so
        val (soAvailable, soDetail) = probeNativeLibrary(context)
        if (!soAvailable) {
            recommendations.add(
                "Native library (.so) not found.")
        }

        // Probe 3: IPC channel
        val (sfwDetected, sfwDetail) = probeSystemFrameworkIPC(context)
        if (!sfwDetected) {
            recommendations.add(
                "System Framework not detected in LSPosed scope. " +
                "Add 'System Framework' to LSPosed module scope and REBOOT.")
        }

        // Probe 4: Device fingerprint
        val fingerprint = Build.FINGERPRINT ?: "unknown"
        val inWhitelist = probeFingerprintWhitelist(fingerprint)
        if (!inWhitelist) {
            recommendations.add(
                "Device fingerprint NOT in ALLOWED_FINGERPRINTS. " +
                "Native hooks disabled until fingerprint is added.")
        }

        // ---- N5: Deep /proc probes ----

        // Probe 5: LSPosed injection verification via /proc/self/maps
        val (lsposedInProc, lsposedMatches) = probeLsposedInProcess()
        if (!lsposedInProc) {
            recommendations.add(
                "LSPosed module NOT detected in current process memory map. " +
                "Module may not be loaded. Check LSPosed manager for scope configuration.")
        }

        // Probe 6: Debugger attachment via TracerPid
        val (debuggerAttached, tracerPid) = probeDebuggerAttachment()
        if (debuggerAttached) {
            recommendations.add(
                "DEBUGGER ATTACHED (TracerPid=$tracerPid). " +
                "This process is being traced — sensor/location timing may be affected.")
        }

        // Probe 7: Process state snapshot
        val (oomScore, cgroup) = probeProcessState()

        // Probe 8: Root environment
        val rootPaths = probeRootEnvironment()

        // Probe 9: Full /proc snapshot
        val procSnap = captureProcSnapshot()

        // ---- Synthesize availability ----

        val sensorAvailable = cpHealthy
        val sensorDetail = if (sensorAvailable) {
            "Sensor mock available. ContentProvider: OK. LSPosed in process: $lsposedInProc."
        } else {
            "Sensor mock unavailable: $cpDetail"
        }

        val locationAvailable = sfwDetected && soAvailable && cpHealthy
        val locationDetail = buildString {
            if (locationAvailable) {
                append("Location mock prerequisites met.")
            } else {
                append("Location mock unavailable. Missing: ")
                val missing = mutableListOf<String>()
                if (!sfwDetected) missing.add("System Framework in LSPosed scope")
                if (!soAvailable) missing.add("native library")
                if (!cpHealthy) missing.add("ContentProvider")
                append(missing.joinToString(", "))
            }
        }

        return ProbeResult(
            sensorMockAvailable = sensorAvailable,
            sensorMockDetail = sensorDetail,
            locationMockAvailable = locationAvailable,
            locationMockDetail = locationDetail,
            systemFrameworkDetected = sfwDetected,
            systemFrameworkDetail = sfwDetail,
            nativeLibraryAvailable = soAvailable,
            nativeLibraryDetail = soDetail,
            deviceFingerprint = fingerprint,
            deviceInWhitelist = inWhitelist,
            contentProviderHealthy = cpHealthy,
            contentProviderDetail = cpDetail,
            lsposedInCurrentProcess = lsposedInProc,
            lsposedMapsMatches = lsposedMatches,
            debuggerAttached = debuggerAttached,
            tracerPid = tracerPid,
            oomScoreAdj = oomScore,
            processCgroup = cgroup,
            rootEnvironment = rootPaths,
            procSnapshot = procSnap,
            recommendations = recommendations,
        )
    }

    // ---- N5: Deep /proc probes (chunqiu2 methodology) ----

    /**
     * Probe /proc/self/maps for LSPosed/Xposed/KunYuShu entries.
     * Confirms that the module is actually loaded in the current process,
     * not just that the APK is installed.
     *
     * Pattern from chunqiu2: maps analysis for injection detection.
     * READ-ONLY: only reads /proc/self/maps file.
     */
    private fun probeLsposedInProcess(): Pair<Boolean, List<String>> {
        val matches = mutableListOf<String>()
        return try {
            val mapsFile = File("/proc/self/maps")
            if (!mapsFile.canRead()) {
                return Pair(false, emptyList())
            }
            mapsFile.forEachLine { line ->
                for (marker in LSPOSED_MAPS_MARKERS) {
                    if (line.contains(marker, ignoreCase = true)) {
                        // Extract the pathname (last field in the line)
                        val parts = line.trim().split("\\s+".toRegex())
                        val path = parts.lastOrNull() ?: ""
                        if (path.isNotEmpty() && !matches.any { it.contains(marker) }) {
                            matches.add("$marker -> $path")
                        }
                    }
                }
            }
            val detected = matches.isNotEmpty()
            if (detected) {
                Logger.info("[ReadinessProbe] LSPosed detected in process: ${matches.size} markers found")
            } else {
                Logger.warn("[ReadinessProbe] LSPosed NOT detected in process memory map")
            }
            Pair(detected, matches)
        } catch (e: Exception) {
            Logger.warn("[ReadinessProbe] /proc/self/maps probe failed: ${e.message}")
            Pair(false, emptyList())
        }
    }

    /**
     * Probe debugger attachment via TracerPid in /proc/self/status.
     * A non-zero TracerPid indicates an attached debugger (gdb, lldb, frida, etc.).
     *
     * Pattern from chunqiu2: TracerPid detection via fopen/fgets/strstr.
     * READ-ONLY: only reads /proc/self/status file.
     */
    private fun probeDebuggerAttachment(): Pair<Boolean, Int> {
        return try {
            val statusFile = File("/proc/self/status")
            if (!statusFile.canRead()) return Pair(false, -1)
            var tracerPid = 0
            statusFile.forEachLine { line ->
                if (line.startsWith("TracerPid:")) {
                    tracerPid = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val attached = tracerPid > 0
            if (attached) {
                Logger.warn("[ReadinessProbe] Debugger detected: TracerPid=$tracerPid")
            }
            Pair(attached, tracerPid)
        } catch (e: Exception) {
            Logger.debug("[ReadinessProbe] TracerPid probe failed: ${e.message}")
            Pair(false, -1)
        }
    }

    /**
     * Probe process state: OOM score adjustment and cgroup.
     * Reveals whether the process is in foreground/background/cached state,
     * which affects CPU throttling and sensor dispatch timing.
     *
     * Pattern from chunqiu2: process environment assessment.
     */
    private fun probeProcessState(): Pair<Int, String> {
        var oomScore = 0
        var cgroup = "unknown"
        try {
            val oomFile = File("/proc/self/oom_score_adj")
            if (oomFile.canRead()) {
                oomScore = oomFile.readText().trim().toIntOrNull() ?: 0
            }
            val cgroupFile = File("/proc/self/cgroup")
            if (cgroupFile.canRead()) {
                // Extract the first meaningful controller line
                cgroup = cgroupFile.readLines()
                    .firstOrNull { it.isNotBlank() }
                    ?.trim() ?: "unknown"
                // Truncate for logging — cgroup lines can be very long
                if (cgroup.length > 120) cgroup = cgroup.take(120) + "..."
            }
        } catch (_: Exception) { }
        return Pair(oomScore, cgroup)
    }

    /**
     * Probe root environment: check for Magisk/KernelSU path evidence.
     * Uses File.exists() (stat syscall) — same as chunqiu2's statx probe.
     *
     * READ-ONLY: only checks file existence, no modifications.
     */
    private fun probeRootEnvironment(): List<String> {
        val found = mutableListOf<String>()
        for (path in ROOT_ENV_PATHS) {
            try {
                if (File(path).exists()) {
                    found.add(path)
                }
            } catch (_: Exception) { }
        }
        if (found.isNotEmpty()) {
            Logger.info("[ReadinessProbe] Root environment detected: ${found.joinToString()}")
        }
        return found
    }

    /**
     * Capture a structured snapshot of key /proc fields for offline analysis.
     * Bundles TracerPid, OOM score, cgroup, and maps summary into a single map.
     *
     * Pattern from chunqiu2: structured evidence collection.
     */
    private fun captureProcSnapshot(): Map<String, String> {
        val snap = mutableMapOf<String, String>()
        try {
            // Process name
            val cmdline = File("/proc/self/cmdline").readText().trim().replace('\u0000', ' ')
            snap["cmdline"] = cmdline.take(128)

            // Thread count
            val taskDir = File("/proc/self/task")
            snap["thread_count"] = (taskDir.list()?.size ?: 0).toString()

            // Memory usage (VmRSS from status)
            val statusFile = File("/proc/self/status")
            if (statusFile.canRead()) {
                statusFile.forEachLine { line ->
                    when {
                        line.startsWith("VmRSS:") -> snap["vm_rss_kb"] = line.substringAfter(":").trim()
                        line.startsWith("VmSize:") -> snap["vm_size_kb"] = line.substringAfter(":").trim()
                        line.startsWith("Threads:") -> snap["threads"] = line.substringAfter(":").trim()
                        line.startsWith("Name:") -> snap["process_name"] = line.substringAfter(":").trim()
                    }
                }
            }

            // SELinux context
            val attrCurrent = File("/proc/self/attr/current")
            if (attrCurrent.canRead()) {
                snap["selinux_context"] = attrCurrent.readText().trim().take(128)
            }
        } catch (_: Exception) { }
        return snap
    }

    // ---- Original probes (unchanged logic, minor enhancements) ----

    private fun probeContentProvider(): Pair<Boolean, String> {
        return try {
            FakeLoc.reload()
            Pair(true, "ContentProvider responded")
        } catch (e: Exception) {
            Pair(false, "ContentProvider probe failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun probeNativeLibrary(context: Context?): Pair<Boolean, String> {
        if (context == null) return Pair(false, "Context is null")
        return try {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            if (nativeDir == null) return Pair(false, "nativeLibraryDir is null")
            val dir = File(nativeDir)
            if (!dir.exists() || !dir.isDirectory) {
                return Pair(false, "Native library directory does not exist: $nativeDir")
            }
            val soFiles = dir.listFiles()?.filter { it.isFile } ?: emptyList()
            val matched = soFiles.filter { file ->
                SO_PATTERNS.any { it.matches(file.name) }
            }
            if (matched.isEmpty()) {
                return Pair(false,
                    "No matching .so found. Expected: libkys_*.so, libkunyushu.so. " +
                    "Found: ${soFiles.map { it.name }.take(5)}")
            }
            val paths = matched.joinToString(", ") { "${it.name} (${it.length() / 1024}KB)" }
            Pair(true, "Native library found: $paths")
        } catch (e: Exception) {
            Pair(false, "Probe failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun probeSystemFrameworkIPC(context: Context?): Pair<Boolean, String> {
        if (context == null) return Pair(false, "Context is null")
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return Pair(false, "LocationManager not available")
            val probeMarker = "probe_${SystemClock.elapsedRealtime()}"
            val outResult = Bundle().apply {
                putString("_probe_sentinel", probeMarker)
                putString("command_id", "ping")
            }
            val startMs = SystemClock.elapsedRealtime()
            val accepted = lm.sendExtraCommand(ChannelIdentity.providerName, probeMarker, outResult)
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val hasPong = outResult.containsKey("pong")
            val sentinelRemoved = !outResult.containsKey("_probe_sentinel")

            if (hasPong || sentinelRemoved) {
                Pair(true,
                    "System Framework hooks detected (IPC responded in ${elapsed}ms, " +
                    "pong=$hasPong)")
            } else {
                Pair(false,
                    "System Framework hooks NOT detected (IPC no-op in ${elapsed}ms). " +
                    "System Framework is likely NOT in LSPosed scope.")
            }
        } catch (e: SecurityException) {
            Pair(false, "Location permission denied")
        } catch (e: Exception) {
            Pair(false, "IPC probe failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun probeFingerprintWhitelist(fingerprint: String): Boolean {
        if (fingerprint.isBlank()) return false
        val allowedPrefixes = listOf(
            "Xiaomi/", "Redmi/",
            "OPPO/", "OnePlus/",
            "samsung/",
            "vivo/",
            "HUAWEI/",
            "generic/",
        )
        return allowedPrefixes.any { fingerprint.startsWith(it) }
    }

    // ---- FlightRecorder integration (enhanced with deep probe data) ----

    fun logToFlightRecorder(result: ProbeResult) {
        val configSnapshot = try {
            mapOf(
                "enable" to FakeLoc.enable,
                "enableMockGnss" to FakeLoc.enableMockGnss,
                "enableMockWifi" to FakeLoc.enableMockWifi,
                "enableStepCountMock" to FakeLoc.enableStepCountMock,
                "hookSensor" to FakeLoc.hookSensor,
                "latitude" to FakeLoc.latitude,
                "longitude" to FakeLoc.longitude,
                "forceDisable" to FakeLoc.forceDisable,
                "isAppInBackground" to FakeLoc.isAppInBackground,
                "isScreenOff" to FakeLoc.isScreenOff,
                "isDeviceIdleMode" to FakeLoc.isDeviceIdleMode,
            )
        } catch (_: Exception) {
            mapOf("config_error" to "snapshot_failed")
        }

        // N5: Include deep probe data in FlightRecorder event
        val deepProbeData = try {
            mapOf(
                "lsposed_in_process" to result.lsposedInCurrentProcess,
                "lsposed_matches" to result.lsposedMapsMatches.size,
                "debugger_attached" to result.debuggerAttached,
                "tracer_pid" to result.tracerPid,
                "oom_score_adj" to result.oomScoreAdj,
                "root_env" to result.rootEnvironment.size,
                "thread_count" to (result.procSnapshot["thread_count"] ?: "0"),
                "vm_rss_kb" to (result.procSnapshot["vm_rss_kb"] ?: "0"),
                "selinux_context" to (result.procSnapshot["selinux_context"] ?: "unknown").take(64),
            )
        } catch (_: Exception) {
            emptyMap<String, Any>()
        }

        FlightRecorder.state(
            stepId = "DIAGNOSTIC",
            detail = "READINESS_PROBE",
            extra = mapOf(
                "sensor_available" to result.sensorMockAvailable,
                "location_available" to result.locationMockAvailable,
                "system_framework" to result.systemFrameworkDetected,
                "native_library" to result.nativeLibraryAvailable,
                "content_provider" to result.contentProviderHealthy,
                "device_fingerprint" to result.deviceFingerprint.take(32),
                "recommendations" to result.recommendations.size,
            ) + configSnapshot + deepProbeData,
        )
    }
}
