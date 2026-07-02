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
 */

package tv.kyshu.service

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings

/**
 * 黑盒探针 — 从 APP 进程对整个虚拟定位系统做外部健康检测。
 *
 * 与 [tv.kyshu.xposed.ReadinessProbe]（在 xposed 进程内运行，白盒视角）不同，
 * 本探针站在 APP 侧（黑盒视角），只通过公开接口和 IPC 判断系统各层是否正常工作。
 * 每个探针独立上报 pass/fail，最终聚合为一个综合健康度。
 */
object BlackBoxProbe {

    /** 单次探针结果 */
    data class ProbeResult(
        val name: String,
        val passed: Boolean,
        val detail: String,
        val durationMs: Long,
        val severity: Severity,
    )

    /** 探针严重等级 */
    enum class Severity { CRITICAL, WARNING, INFO }

    /** 综合健康度 */
    enum class Health { HEALTHY, DEGRADED, CRITICAL }

    /** 完整诊断报告 */
    data class Report(
        val timestamp: Long,
        val overallHealth: Health,
        val probes: List<ProbeResult>,
        val summary: String,
    )

    // ==================== 入口 ====================

    /**
     * 运行全量诊断。返回包含所有探针结果和综合评级的报告。
     */
    fun runAll(context: Context, locationManager: LocationManager?): Report {
        val results = mutableListOf<ProbeResult>()

        results.add(probeIpcPing(locationManager))
        results.add(probeContentProvider(context))
        results.add(probeServiceInit())
        results.add(probeMockState(locationManager))
        results.add(probeSystemProviders(context))
        results.add(probePermissions(context))

        return synthesize(results)
    }

    /**
     * 快速检测 — 只跑关键探针（IPC + 内容供应器 + 服务初始化），
     * 适用于启动时或状态变化后的快速自检。
     */
    fun runQuick(context: Context, locationManager: LocationManager?): Report {
        val results = mutableListOf<ProbeResult>()

        results.add(probeIpcPing(locationManager))
        results.add(probeContentProvider(context))
        results.add(probeServiceInit())

        return synthesize(results)
    }

    // ==================== 探针实现 ====================

    /**
     * 探针 1 — IPC 连通性
     *
     * 通过当前认证 IPC 发送 PING 命令，期望收到 PONG。
     * 如果失败说明 xposed hook 未注入 system_server、provider name 不匹配，或安全通道未初始化。
     */
    private fun probeIpcPing(locationManager: LocationManager?): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        val name = "IPC 连通性"

        if (locationManager == null) {
            return ProbeResult(name, false, "LocationManager 不可用 (getSystemService 返回 null)",
                SystemClock.elapsedRealtime() - start, Severity.CRITICAL)
        }

        return try {
            val providerName = MockServiceHelper.PROVIDER_NAME
            if (!MockServiceHelper.isServiceInit()) {
                MockServiceHelper.tryInitService(locationManager)
            }
            val ok = MockServiceHelper.ping(locationManager)

            if (ok) {
                ProbeResult(name, true,
                    "sendExtraCommand 成功, provider=$providerName, 收到 pong",
                    SystemClock.elapsedRealtime() - start, Severity.INFO)
            } else {
                ProbeResult(name, false,
                    "认证 ping 失败, xposed hook 未注入、provider 未注册或安全通道未建立",
                    SystemClock.elapsedRealtime() - start, Severity.CRITICAL)
            }
        } catch (e: SecurityException) {
            ProbeResult(name, false,
                "缺少定位权限: ${e.message}",
                SystemClock.elapsedRealtime() - start, Severity.CRITICAL)
        } catch (e: Exception) {
            ProbeResult(name, false,
                "IPC 异常: ${e.javaClass.simpleName}: ${e.message}",
                SystemClock.elapsedRealtime() - start, Severity.CRITICAL)
        }
    }

    /**
     * 探针 2 — ContentProvider 健康
     *
     * 通过 FakeLoc.reload() 读取 xposed config 的内容供应器。
     * 验证 app 与 xposed 模块之间的跨进程通信是否正常。
     */
    private fun probeContentProvider(context: Context): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        val name = "配置通道"

        return try {
            tv.kyshu.xposed.utils.FakeLoc.reload()
            val enable = tv.kyshu.xposed.utils.FakeLoc.mockState.enable
            val lat = tv.kyshu.xposed.utils.FakeLoc.locationData.latitude
            val lon = tv.kyshu.xposed.utils.FakeLoc.locationData.longitude

            ProbeResult(name, true,
                "ConfigProvider 响应正常, enable=$enable, lat=$lat, lon=$lon",
                SystemClock.elapsedRealtime() - start, Severity.INFO)
        } catch (e: Exception) {
            ProbeResult(name, false,
                "ConfigProvider 不可达: ${e.javaClass.simpleName}: ${e.message}",
                SystemClock.elapsedRealtime() - start, Severity.CRITICAL)
        }
    }

    /**
     * 探针 3 — 服务初始化状态
     *
     * 检查 MockServiceHelper 是否已完成密钥交换（randomKey 已初始化）。
     * 若未初始化，说明 APP 未能成功与 xposed 模块建立安全通道。
     */
    private fun probeServiceInit(): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        val name = "服务初始化"

        val initialized = MockServiceHelper.isServiceInit()
        return if (initialized) {
            ProbeResult(name, true,
                "MockServiceHelper 已初始化, 安全通道已建立",
                SystemClock.elapsedRealtime() - start, Severity.INFO)
        } else {
            ProbeResult(name, false,
                "MockServiceHelper 未初始化, 尚未完成密钥交换",
                SystemClock.elapsedRealtime() - start, Severity.CRITICAL)
        }
    }

    /**
     * 探针 4 — Mock 启停控制
     *
     * 检查 GPS mock 和 GNSS mock 的当前启停状态。
     * 验证 IPC 控制指令的双向可达性。
     */
    private fun probeMockState(locationManager: LocationManager?): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        val name = "Mock 控制"

        if (locationManager == null) {
            return ProbeResult(name, false, "LocationManager 不可用",
                SystemClock.elapsedRealtime() - start, Severity.WARNING)
        }
        if (!MockServiceHelper.isServiceInit()) {
            return ProbeResult(name, false, "服务未初始化, 跳过 mock 状态检测",
                SystemClock.elapsedRealtime() - start, Severity.WARNING)
        }

        return try {
            val isMockStart = MockServiceHelper.isMockStart(locationManager)
            val isGnssStart = MockServiceHelper.isGnssMockStart(locationManager)
            val isWifiStart = MockServiceHelper.isWifiMockStart(locationManager)

            val details = mutableListOf<String>()
            details.add(if (isMockStart) "定位模拟: 运行中" else "定位模拟: 已停止")
            details.add(if (isGnssStart) "GNSS 模拟: 运行中" else "GNSS 模拟: 已停止")
            details.add(if (isWifiStart) "WiFi 模拟: 运行中" else "WiFi 模拟: 已停止")

            ProbeResult(name, true, details.joinToString(", "),
                SystemClock.elapsedRealtime() - start, Severity.INFO)
        } catch (e: Exception) {
            ProbeResult(name, false,
                "查询 mock 状态失败: ${e.javaClass.simpleName}: ${e.message}",
                SystemClock.elapsedRealtime() - start, Severity.WARNING)
        }
    }

    /**
     * 探针 5 — 系统定位服务状态
     *
     * 检查系统 LocationManager 的 provider 列表和 GPS 开关状态。
     * 某些 ROM 会在定位关闭时阻止 hook 工作，此探针用于识别这类环境问题。
     */
    private fun probeSystemProviders(context: Context): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        val name = "系统定位服务"

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                return ProbeResult(name, false, "LocationManager 服务不可用",
                    SystemClock.elapsedRealtime() - start, Severity.CRITICAL)
            }

            val enabledProviders = lm.getProviders(true) ?: emptyList()
            val gpsAvailable = enabledProviders.any { it == LocationManager.GPS_PROVIDER }
            val networkAvailable = enabledProviders.any { it == LocationManager.NETWORK_PROVIDER }
            val isProviderEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

            // 检查定位总开关
            val locationMode = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    locationManager.isLocationEnabled
                } else {
                    Settings.Secure.getInt(
                        context.contentResolver,
                        Settings.Secure.LOCATION_MODE,
                        Settings.Secure.LOCATION_MODE_OFF
                    ) != Settings.Secure.LOCATION_MODE_OFF
                }
            } catch (_: Exception) { null }

            val details = mutableListOf<String>()
            details.add(if (gpsAvailable) "GPS Provider: 可用" else "GPS Provider: 不可用")
            details.add(if (networkAvailable) "Network Provider: 可用" else "Network Provider: 不可用")
            details.add("定位${if (locationMode == true) "总开关: 开" else if (locationMode == false) "总开关: 关" else "总开关: 未知"}")

            val allProvidersOk = gpsAvailable || networkAvailable
            val isLocationOn = locationMode != false

            if (allProvidersOk && isLocationOn) {
                ProbeResult(name, true, details.joinToString(", "),
                    SystemClock.elapsedRealtime() - start, Severity.INFO)
            } else if (allProvidersOk) {
                ProbeResult(name, true,
                    details.joinToString(", ") + " [注意: 定位总开关已关闭, mock 可能不生效]",
                    SystemClock.elapsedRealtime() - start, Severity.WARNING)
            } else {
                ProbeResult(name, false, details.joinToString(", ") + " [无可用定位 provider]",
                    SystemClock.elapsedRealtime() - start, Severity.WARNING)
            }
        } catch (e: Exception) {
            ProbeResult(name, false,
                "查询系统服务失败: ${e.javaClass.simpleName}: ${e.message}",
                SystemClock.elapsedRealtime() - start, Severity.WARNING)
        }
    }

    /**
     * 探针 6 — 权限状态
     *
     * 检查 APP 是否持有运行所需的全部权限。
     * 权限缺失是 mock 不工作的最常见原因之一。
     */
    private fun probePermissions(context: Context): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        val name = "运行时权限"

        val required = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
        )
        val granted = required.filter { perm ->
            context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        }
        val denied = required - granted.toSet()

        return if (denied.isEmpty()) {
            ProbeResult(name, true,
                "全部 ${required.size} 项权限已授予",
                SystemClock.elapsedRealtime() - start, Severity.INFO)
        } else {
            ProbeResult(name, false,
                "缺失 ${denied.size}/${required.size} 项权限: ${denied.joinToString(", ")}",
                SystemClock.elapsedRealtime() - start, Severity.CRITICAL)
        }
    }

    // ==================== 结果聚合 ====================

    private fun synthesize(results: List<ProbeResult>): Report {
        val criticalFailures = results.count { it.severity == Severity.CRITICAL && !it.passed }
        val warnings = results.count { it.severity == Severity.WARNING && !it.passed }

        val health = when {
            criticalFailures > 0 -> Health.CRITICAL
            warnings > 0 -> Health.DEGRADED
            else -> Health.HEALTHY
        }

        val total = results.size
        val passed = results.count { it.passed }
        val summary = buildString {
            append("$passed/$total 项通过")
            if (criticalFailures > 0) append(", $criticalFailures 项严重异常")
            if (warnings > 0) append(", $warnings 项警告")
            append(" — ")
            append(
                when (health) {
                    Health.HEALTHY -> "系统运行正常"
                    Health.DEGRADED -> "系统功能部分受限"
                    Health.CRITICAL -> "系统不可用, 请检查 LSPosed 范围和权限"
                }
            )
        }

        return Report(
            timestamp = System.currentTimeMillis(),
            overallHealth = health,
            probes = results,
            summary = summary,
        )
    }
}
