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
package tv.kyshu.xposed.config

import kotlin.random.Random

/**
 * WiFi 模拟数据 — SSID、BSSID、MAC、IP、扫描列表、RSSI 波动状态等。
 * mockWifiList 由 FakeLoc.updateFromMap 通过 parseMockWifiList 设置。
 */
class WifiData {
    companion object {
        const val DEFAULT_MAC = "02:00:00:00:00:00"
    }

    // ==================== WiFi 模拟开关 ====================

    /**
     * 模拟WLAN数据
     */
    @Volatile
    var enableMockWifi = false

    @Volatile
    var disableWifiScan = false

    @Volatile
    var hookWifi = true

    // ==================== 模拟数据 ====================

    @Volatile
    var mockSSID = "KunYuShu-Wifi"

    @Volatile
    var mockBSSID = DEFAULT_MAC

    @Volatile
    var mockMac = DEFAULT_MAC

    @Volatile
    var mockIp = "192.168.1.123" // Default non-zero IP

    @Volatile
    var mockWifiScanCount = 20

    @Volatile
    var mockWifiList = emptyList<MockWifiData>()

    // ==================== RSSI 波动状态 ====================

    @Volatile
    var lastRssiFluctuationTime = 0L

    @Volatile
    var currentRssiOffset = 0

    @Volatile
    var consecutiveRssiStableCounts = 0

    @Volatile
    var currentRssiTriggerProbability = 0.25f

    @Volatile
    var currentLinkSpeed = 300

    @Volatile
    var isRssiProbReduced = false

    // ==================== RSSI 波动算法 ====================

    /**
     * 更新 RSSI 信号波动状态。
     * 每次调用会检查自上次波动以来的间隔，超过 12 秒则产生随机波动。
     */
    fun updateSignalState() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastRssiFluctuationTime == 0L) {
            lastRssiFluctuationTime = now
            return
        }

        if (now - lastRssiFluctuationTime > 12000) {
            lastRssiFluctuationTime = now

            var prob = 0.25f
            if (isRssiProbReduced) prob -= 0.05f

            val shouldFluctuate = Random.nextFloat() < prob || consecutiveRssiStableCounts >= 7

            if (shouldFluctuate) {
                // 1. Change Rate (Link Speed) - Random 200-400
                currentLinkSpeed = Random.nextInt(200, 401)

                // 2. Change Amplitude (RSSI) - Max 18dbm change
                val delta = Random.nextInt(-18, 19)
                currentRssiOffset += delta

                // Logic for probability reduction restoration
                isRssiProbReduced = !isRssiProbReduced

                consecutiveRssiStableCounts = 0
            } else {
                consecutiveRssiStableCounts++
            }
        }
    }

    // ==================== 数据校验 ====================

    /**
     * MockWiFi 数据合法化：填充空白 SSID、修复无效 MAC/IP、限制扫描计数。
     */
    internal fun normalizeMockWifi() {
        if (mockSSID.isBlank()) mockSSID = "KunYuShu-Wifi"
        if (!isValidMac(mockBSSID)) mockBSSID = DEFAULT_MAC
        if (!isValidMac(mockMac)) mockMac = DEFAULT_MAC
        if (!isValidIpv4(mockIp)) mockIp = "192.168.1.123"
        if (mockWifiScanCount < 1) mockWifiScanCount = 1
    }

    private fun isValidMac(mac: String): Boolean {
        val parts = mac.split(":")
        if (parts.size != 6) return false
        return parts.all { it.length == 2 && it.all { ch -> ch.isDigit() || ch.lowercaseChar() in 'a'..'f' } }
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull()?.let { v -> v in 1..255 } == true }
    }

    // ==================== 子对象专属更新 ====================

    /**
     * 从配置 map 更新 [WifiData] 专属字段。
     * mockWifiList 由 FakeLoc.updateFromMap 通过 parseMockWifiList 设置。
     */
    internal fun updateFromMap(map: Map<String, Any?>) {
        enableMockWifi = map.getAnyVal(enableMockWifi, "enableMockWifi", "enable_mock_wifi")
        disableWifiScan = map.getAnyVal(disableWifiScan, "disableWifiScan", "disable_wifi_scan")
        mockSSID = map.getAnyVal(mockSSID, "mockSSID", "mock_ssid")
        mockBSSID = map.getAnyVal(mockBSSID, "mockBSSID", "mock_bssid")
        mockMac = map.getAnyVal(mockMac, "mockMac", "mock_mac")
        mockIp = map.getAnyVal(mockIp, "mockIp", "mock_ip")
        mockWifiScanCount = map.getAnyVal(mockWifiScanCount, "mockWifiScanCount", "mock_wifi_scan_count")
        hookWifi = map.getAnyVal(hookWifi, "hookWifi", "hook_wifi")
    }
}
