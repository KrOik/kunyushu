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
package tv.kyshu.xposed.utils

import android.content.ContentValues
import android.content.Context
import android.location.Location
import tv.kyshu.xposed.config.GaitData
import tv.kyshu.xposed.config.LocationData
import tv.kyshu.xposed.config.MockState
import tv.kyshu.xposed.config.MockWifiData
import tv.kyshu.xposed.config.SensorData
import tv.kyshu.xposed.config.WifiData
import tv.kyshu.xposed.utils.config.ConfigLoader
import tv.kyshu.xposed.utils.config.ContentProviderLoader
import tv.kyshu.xposed.utils.config.XPrefLoader
import org.json.JSONArray
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * FakeLoc — 定位 / WiFi / 传感器 / 步态模拟全局配置门面。
 *
 * 自 H-NEW-7 重构后，实际字段存储已拆分到 5 个聚焦的配置对象中：
 *   [MockState]    — 全局控制状态（enable、forceDisable、日志标志等）
 *   [LocationData] — 位置模拟数据（经纬度、精度、GNSS/NMEA 等）
 *   [SensorData]   — 传感器模拟（计步、摇一摇、Doze 间隔等）
 *   [WifiData]     — WiFi 模拟（SSID、MAC、RSSI 波动等）
 *   [GaitData]     — 步态模拟参数（频率、振幅、JSON 曲线）
 *
 * 所有旧属性通过 @Deprecated 委托保持向后兼容。新代码应直接访问子对象。
 */
object FakeLoc {

    // ==================== 子配置对象 ====================

    val mockState = MockState()
    val locationData = LocationData()
    val sensorData = SensorData()
    val wifiData = WifiData()
    val gaitData = GaitData()

    // ==================== 系统上下文 (私有) ====================

    private var systemContextRef: WeakReference<Context>? = null

    fun setContext(context: Context) {
        systemContextRef = WeakReference(context.applicationContext)
    }

    fun getContext(): Context? {
        return systemContextRef?.get()
    }

    // ==================== 加载器 ====================

    private val loaders: List<ConfigLoader>
        get() = listOfNotNull(
            ContentProviderLoader(getContext()),
            XPrefLoader()
        )

    // ==================== 内部常量与状态 ====================

    const val DEFAULT_MAC = "02:00:00:00:00:00"

    private const val RELOAD_INTERVAL = 500L
    private const val PROVIDER_BACKOFF_MS = 60_000L
    private val PROVIDER_KEY_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
    private val providerReadBackoff = ConcurrentHashMap<String, Long>()
    private val providerWriteBackoff = ConcurrentHashMap<String, Long>()
    private const val DEFAULT_MIN_SATELLITES = 12
    private const val MAX_MOCK_SATELLITES = 35
    private var lastRootLogAt = 0L
    private var lastRootStatus: String? = null

    // ==================== @Deprecated 向后兼容委托 — MockState ====================

    @Deprecated("Use mockState.enable", ReplaceWith("mockState.enable"))
    var enable: Boolean
        get() = mockState.enable
        set(value) { mockState.enable = value }

    @Deprecated("Use mockState.forceDisable", ReplaceWith("mockState.forceDisable"))
    var forceDisable: Boolean
        get() = mockState.forceDisable
        set(value) { mockState.forceDisable = value }

    @Deprecated("Use mockState.enableDebugLog", ReplaceWith("mockState.enableDebugLog"))
    var enableDebugLog: Boolean
        get() = mockState.enableDebugLog
        set(value) { mockState.enableDebugLog = value }

    @Deprecated("Use mockState.enableLog", ReplaceWith("mockState.enableLog"))
    var enableLog: Boolean
        get() = mockState.enableLog
        set(value) { mockState.enableLog = value }

    @Deprecated("Use mockState.rootAvailable", ReplaceWith("mockState.rootAvailable"))
    var rootAvailable: Boolean
        get() = mockState.rootAvailable
        set(value) { mockState.rootAvailable = value }

    @Deprecated("Use mockState.rootMode", ReplaceWith("mockState.rootMode"))
    var rootMode: Boolean
        get() = mockState.rootMode
        set(value) { mockState.rootMode = value }

    // ==================== @Deprecated 向后兼容委托 — LocationData ====================

    @Deprecated("Use locationData.latitude", ReplaceWith("locationData.latitude"))
    var latitude: Double
        get() = locationData.latitude
        set(value) { locationData.latitude = value }

    @Deprecated("Use locationData.longitude", ReplaceWith("locationData.longitude"))
    var longitude: Double
        get() = locationData.longitude
        set(value) { locationData.longitude = value }

    @Deprecated("Use locationData.altitude", ReplaceWith("locationData.altitude"))
    var altitude: Double
        get() = locationData.altitude
        set(value) { locationData.altitude = value }

    @Deprecated("Use locationData.accuracy", ReplaceWith("locationData.accuracy"))
    var accuracy: Float
        get() = locationData.accuracy
        set(value) { locationData.accuracy = value }

    @Deprecated("Use locationData.speed", ReplaceWith("locationData.speed"))
    var speed: Double
        get() = locationData.speed
        set(value) { locationData.speed = value }

    @Deprecated("Use locationData.bearing", ReplaceWith("locationData.bearing"))
    var bearing: Double
        get() = locationData.bearing
        set(value) { locationData.bearing = value }

    @Deprecated("Use locationData.enableMockGnss", ReplaceWith("locationData.enableMockGnss"))
    var enableMockGnss: Boolean
        get() = locationData.enableMockGnss
        set(value) { locationData.enableMockGnss = value }

    @Deprecated("Use locationData.enableNMEA", ReplaceWith("locationData.enableNMEA"))
    var enableNMEA: Boolean
        get() = locationData.enableNMEA
        set(value) { locationData.enableNMEA = value }

    @Deprecated("Use locationData.minSatellites", ReplaceWith("locationData.minSatellites"))
    var minSatellites: Int
        get() = locationData.minSatellites
        set(value) { locationData.minSatellites = value }

    @Deprecated("Use locationData.rinexPath", ReplaceWith("locationData.rinexPath"))
    var rinexPath: String?
        get() = locationData.rinexPath
        set(value) { locationData.rinexPath = value }

    @Deprecated("Use locationData.hideMock", ReplaceWith("locationData.hideMock"))
    var hideMock: Boolean
        get() = locationData.hideMock
        set(value) { locationData.hideMock = value }

    @Deprecated("Use locationData.lastLocation", ReplaceWith("locationData.lastLocation"))
    var lastLocation: Location?
        get() = locationData.lastLocation
        set(value) { locationData.lastLocation = value }

    // ==================== @Deprecated 向后兼容委托 — SensorData ====================

    @Deprecated("Use sensorData.hookSensor", ReplaceWith("sensorData.hookSensor"))
    var hookSensor: Boolean
        get() = sensorData.hookSensor
        set(value) { sensorData.hookSensor = value }

    @Deprecated("Use sensorData.dozeSensorIntervalMs", ReplaceWith("sensorData.dozeSensorIntervalMs"))
    var dozeSensorIntervalMs: Long
        get() = sensorData.dozeSensorIntervalMs
        set(value) { sensorData.dozeSensorIntervalMs = value }

    @Deprecated("Use sensorData.enableStepCountMock", ReplaceWith("sensorData.enableStepCountMock"))
    var enableStepCountMock: Boolean
        get() = sensorData.enableStepCountMock
        set(value) { sensorData.enableStepCountMock = value }

    @Deprecated("Use sensorData.stepCount", ReplaceWith("sensorData.stepCount"))
    var stepCount: Int
        get() = sensorData.stepCount
        set(value) { sensorData.stepCount = value }

    @Deprecated("Use sensorData.enableShakeMock", ReplaceWith("sensorData.enableShakeMock"))
    var enableShakeMock: Boolean
        get() = sensorData.enableShakeMock
        set(value) { sensorData.enableShakeMock = value }

    @Deprecated("Use sensorData.keepWalkingWaveform", ReplaceWith("sensorData.keepWalkingWaveform"))
    var keepWalkingWaveform: Boolean
        get() = sensorData.keepWalkingWaveform
        set(value) { sensorData.keepWalkingWaveform = value }

    // ==================== @Deprecated 向后兼容委托 — WifiData ====================

    @Deprecated("Use wifiData.enableMockWifi", ReplaceWith("wifiData.enableMockWifi"))
    var enableMockWifi: Boolean
        get() = wifiData.enableMockWifi
        set(value) { wifiData.enableMockWifi = value }

    @Deprecated("Use wifiData.disableWifiScan", ReplaceWith("wifiData.disableWifiScan"))
    var disableWifiScan: Boolean
        get() = wifiData.disableWifiScan
        set(value) { wifiData.disableWifiScan = value }

    @Deprecated("Use wifiData.mockSSID", ReplaceWith("wifiData.mockSSID"))
    var mockSSID: String
        get() = wifiData.mockSSID
        set(value) { wifiData.mockSSID = value }

    @Deprecated("Use wifiData.mockBSSID", ReplaceWith("wifiData.mockBSSID"))
    var mockBSSID: String
        get() = wifiData.mockBSSID
        set(value) { wifiData.mockBSSID = value }

    @Deprecated("Use wifiData.mockMac", ReplaceWith("wifiData.mockMac"))
    var mockMac: String
        get() = wifiData.mockMac
        set(value) { wifiData.mockMac = value }

    @Deprecated("Use wifiData.mockIp", ReplaceWith("wifiData.mockIp"))
    var mockIp: String
        get() = wifiData.mockIp
        set(value) { wifiData.mockIp = value }

    @Deprecated("Use wifiData.mockWifiScanCount", ReplaceWith("wifiData.mockWifiScanCount"))
    var mockWifiScanCount: Int
        get() = wifiData.mockWifiScanCount
        set(value) { wifiData.mockWifiScanCount = value }

    @Deprecated("Use wifiData.mockWifiList", ReplaceWith("wifiData.mockWifiList"))
    var mockWifiList: List<MockWifiData>
        get() = wifiData.mockWifiList
        set(value) { wifiData.mockWifiList = value }

    @Deprecated("Use wifiData.hookWifi", ReplaceWith("wifiData.hookWifi"))
    var hookWifi: Boolean
        get() = wifiData.hookWifi
        set(value) { wifiData.hookWifi = value }

    @Deprecated("Use wifiData.lastRssiFluctuationTime", ReplaceWith("wifiData.lastRssiFluctuationTime"))
    var lastRssiFluctuationTime: Long
        get() = wifiData.lastRssiFluctuationTime
        set(value) { wifiData.lastRssiFluctuationTime = value }

    @Deprecated("Use wifiData.currentRssiOffset", ReplaceWith("wifiData.currentRssiOffset"))
    var currentRssiOffset: Int
        get() = wifiData.currentRssiOffset
        set(value) { wifiData.currentRssiOffset = value }

    @Deprecated("Use wifiData.consecutiveRssiStableCounts", ReplaceWith("wifiData.consecutiveRssiStableCounts"))
    var consecutiveRssiStableCounts: Int
        get() = wifiData.consecutiveRssiStableCounts
        set(value) { wifiData.consecutiveRssiStableCounts = value }

    @Deprecated("Use wifiData.currentRssiTriggerProbability", ReplaceWith("wifiData.currentRssiTriggerProbability"))
    var currentRssiTriggerProbability: Float
        get() = wifiData.currentRssiTriggerProbability
        set(value) { wifiData.currentRssiTriggerProbability = value }

    @Deprecated("Use wifiData.currentLinkSpeed", ReplaceWith("wifiData.currentLinkSpeed"))
    var currentLinkSpeed: Int
        get() = wifiData.currentLinkSpeed
        set(value) { wifiData.currentLinkSpeed = value }

    @Deprecated("Use wifiData.isRssiProbReduced", ReplaceWith("wifiData.isRssiProbReduced"))
    var isRssiProbReduced: Boolean
        get() = wifiData.isRssiProbReduced
        set(value) { wifiData.isRssiProbReduced = value }

    // ==================== @Deprecated 向后兼容委托 — GaitData ====================

    @Deprecated("Use gaitData.gaitFreq", ReplaceWith("gaitData.gaitFreq"))
    var gaitFreq: Float
        get() = gaitData.gaitFreq
        set(value) { gaitData.gaitFreq = value }

    @Deprecated("Use gaitData.gaitAmpScale", ReplaceWith("gaitData.gaitAmpScale"))
    var gaitAmpScale: Float
        get() = gaitData.gaitAmpScale
        set(value) { gaitData.gaitAmpScale = value }

    @Deprecated("Use gaitData.gaitParamsJson", ReplaceWith("gaitData.gaitParamsJson"))
    var gaitParamsJson: String
        get() = gaitData.gaitParamsJson
        set(value) { gaitData.gaitParamsJson = value }

    // ==================== @Deprecated 方法委托 ====================

    @Deprecated("Use locationData.nextBearing()", ReplaceWith("locationData.nextBearing()"))
    fun nextBearing(): Double = locationData.nextBearing()

    @Deprecated("Use wifiData.updateSignalState()", ReplaceWith("wifiData.updateSignalState()"))
    fun updateSignalState() = wifiData.updateSignalState()

    // ==================== 保留字段（未迁移到子对象） ====================

    @Volatile
    var isAppInBackground = false

    @Volatile
    var isScreenOff = false

    @Volatile
    var isDeviceIdleMode = false

    /**
     * 是否禁用GetCurrentLocation方法（在部分系统不禁用可能导致hook失效）
     */
    @Volatile
    var disableGetCurrentLocation = true

    /**
     * 是否禁用RegisterLocationListener方法
     */
    @Volatile
    var disableRegisterLocationListener = false

    /**
     * 如果TelephonyHook失效，可能需要打开此开关
     */
    @Volatile
    var disableFusedLocation = true

    @Volatile
    var disableNetworkLocation = true

    @Volatile
    var disableRequestGeofence = false

    @Volatile
    var disableGetFromLocation = false

    /**
     * 是否允许AGPS模块（当前没什么鸟用）
     */
    @Volatile
    var enableAGPS = false

    /**
     * 是否屏蔽基站信息
     */
    @Volatile
    var hideBaseStation = true

    /**
     * 将网络定位降级为Cdma
     */
    @Volatile
    var needDowngradeToCdma = true

    /**
     * 反定位复原加强（启用后将导致部分应用在关闭Portal后需要重新启动才能重新获取定位）
     */
    @Volatile
    var loopBroadcastLocation = false

    @Volatile
    var isSystemServerProcess = false

    // 速度相关辅助参数
    @Volatile
    var minSpeed = 1.2

    @Volatile
    var maxSpeed = 1.5

    @Volatile
    var speedRange = 0.15

    @Volatile
    var speedAmplitude = 1.0

    @Volatile
    var hasBearings = false

    // ==================== Reload 入口 ====================

    fun reload() {
        val now = System.currentTimeMillis()
        if (now - mockState.lastReloadTime < RELOAD_INTERVAL) {
            return
        }
        if (!mockState.reloadLock.tryLock()) {
            return
        }
        try {
            val nowLocked = System.currentTimeMillis()
            if (nowLocked - mockState.lastReloadTime < RELOAD_INTERVAL) {
                return
            }
            mockState.lastReloadTime = nowLocked

            for (loader in loaders) {
                try {
                    val data = loader.load()
                    if (data != null && data.isNotEmpty()) {
                        updateFromMap(data)
                        logRootModeIfNeeded(loader.name)
                        if (mockState.enableDebugLog) {
                            Logger.debug("FakeLoc: Reloaded using ${loader.name}. StepMock=$enableStepCountMock, Count=$stepCount")
                        }
                        return
                    }
                } catch (e: Throwable) {
                    Logger.error("FakeLoc: Error loading from ${loader.name}", e)
                }
            }
        } finally {
            mockState.reloadLock.unlock()
        }
    }

    // ==================== updateFromMap（编排） ====================

    internal fun updateFromMap(map: Map<String, Any?>) {
        // 1) 各子对象更新自身字段
        mockState.updateFromMap(map)
        locationData.updateFromMap(map)
        sensorData.updateFromMap(map)
        wifiData.updateFromMap(map)
        gaitData.updateFromMap(map)

        // 2) 保留字段（未迁移到子对象）
        isAppInBackground = map.getVal("isAppInBackground", isAppInBackground)
        isScreenOff = map.getVal("isScreenOff", isScreenOff)
        isDeviceIdleMode = map.getVal("isDeviceIdleMode", isDeviceIdleMode)

        disableGetCurrentLocation = map.getVal("disableGetCurrentLocation", disableGetCurrentLocation)
        disableRegisterLocationListener = map.getVal("disableRegisterLocationListener", disableRegisterLocationListener)
        disableFusedLocation = map.getVal("disableFusedProvider", disableFusedLocation)
        disableRequestGeofence = !map.getVal("enableRequestGeofence", !disableRequestGeofence)
        disableGetFromLocation = !map.getVal("enableGetFromLocation", !disableGetFromLocation)

        enableAGPS = map.getVal("enableAGPS", enableAGPS)
        loopBroadcastLocation = map.getVal("loopBroadcastLocation", loopBroadcastLocation)
        needDowngradeToCdma = map.getVal("needDowngradeToCdma", needDowngradeToCdma)
        hideBaseStation = map.getVal("hideBaseStation", hideBaseStation)

        // 3) Mock WiFi 列表（需 parse，由本层处理）
        if (map.containsKey("mockWifiList")) {
            wifiData.mockWifiList = parseMockWifiList(map["mockWifiList"])
        }

        // 4) 交叉后处理
        wifiData.normalizeMockWifi()

        Logger.info("[FakeLoc] config applied: enable=$enable, mockGnss=$enableMockGnss, NMEA=$enableNMEA, mockWifi=$enableMockWifi, hookSensor=$hookSensor, stepMock=$enableStepCountMock, shakeMock=$enableShakeMock")
        MockAuditTrail.config("FakeLoc", "reload: enable=$enable gnss=$enableMockGnss nmea=$enableNMEA wifi=$enableMockWifi sensor=$hookSensor step=$enableStepCountMock shake=$enableShakeMock forceDisable=$forceDisable")
    }

    // ==================== Provider 读写 ====================

    fun readProviderValue(key: String): String? {
        if (!isValidProviderKey(key)) return null
        val context = getContext() ?: return null
        val now = System.currentTimeMillis()
        val deniedAt = providerReadBackoff[key]
        if (deniedAt != null && now - deniedAt < PROVIDER_BACKOFF_MS) {
            return null
        }
        return runCatching {
            context.contentResolver.query(ContentProviderLoader.CONTENT_URI, null, null, null, null)?.use { cursor ->
                val keyIndex = cursor.getColumnIndex("key")
                val valueIndex = cursor.getColumnIndex("value")
                if (keyIndex == -1 || valueIndex == -1) return@use null
                while (cursor.moveToNext()) {
                    if (cursor.getString(keyIndex) == key) {
                        return@use cursor.getString(valueIndex)
                    }
                }
                null
            }
        }.onFailure {
            providerReadBackoff[key] = now
        }.getOrNull()
    }

    fun writeProviderValue(key: String, value: Any?, type: String? = null): Boolean {
        if (!isValidProviderKey(key)) return false
        val context = getContext() ?: return false
        val now = System.currentTimeMillis()
        val deniedAt = providerWriteBackoff[key]
        if (deniedAt != null && now - deniedAt < PROVIDER_BACKOFF_MS) {
            return false
        }
        val (valueStr, valueType) = when (value) {
            null -> "" to (type ?: "string")
            is Boolean -> value.toString() to (type ?: "boolean")
            is Int -> value.toString() to (type ?: "int")
            is Long -> value.toString() to (type ?: "long")
            is Float -> value.toString() to (type ?: "float")
            else -> value.toString() to (type ?: "string")
        }
        return runCatching {
            val cv = ContentValues()
            cv.put("key", key)
            cv.put("value", valueStr)
            cv.put("type", valueType)
            val updated = context.contentResolver.update(ContentProviderLoader.CONTENT_URI, cv, "key=?", arrayOf(key))
            if (updated > 0) {
                true
            } else {
                // First-time write: row may not exist yet, try insert
                context.contentResolver.insert(ContentProviderLoader.CONTENT_URI, cv) != null
            }
        }.onFailure {
            providerWriteBackoff[key] = now
        }.getOrDefault(false)
    }

    // ==================== 内部工具方法 ====================

    private fun logRootModeIfNeeded(loaderName: String) {
        val status = "rootAvailable=$rootAvailable, rootMode=$rootMode, rinexPath=${rinexPath ?: ""}, loader=$loaderName"
        val now = System.currentTimeMillis()
        if (status != lastRootStatus || now - lastRootLogAt > 60000L) {
            lastRootStatus = status
            lastRootLogAt = now
            Logger.info("FakeLoc: $status")
        }
    }

    private fun isValidProviderKey(key: String): Boolean {
        return key.isNotBlank() && PROVIDER_KEY_PATTERN.matches(key)
    }

    private inline fun <reified T> Map<String, Any?>.getVal(key: String, default: T): T {
        val value = this[key] ?: return default
        return try {
            when (T::class) {
                Boolean::class -> {
                    if (value is Boolean) value as T
                    else (value.toString() == "true") as T
                }
                Int::class -> {
                    if (value is Number) value.toInt() as T
                    else value.toString().toIntOrNull() as? T ?: default
                }
                Long::class -> {
                    if (value is Number) value.toLong() as T
                    else value.toString().toLongOrNull() as? T ?: default
                }
                Float::class -> {
                    if (value is Number) value.toFloat() as T
                    else value.toString().toFloatOrNull() as? T ?: default
                }
                String::class -> value.toString() as T
                else -> default
            }
        } catch (e: Exception) {
            default
        }
    }

    private fun parseMockWifiList(value: Any?): List<MockWifiData> {
        val raw = value?.toString()?.trim().orEmpty()
        if (raw.isEmpty() || raw == "[]") return emptyList()
        return runCatching<List<MockWifiData>> {
            val array = JSONArray(raw)
            ArrayList<MockWifiData>(array.length()).apply {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        MockWifiData(
                            ssid = item.optString("ssid", ""),
                            bssid = item.optString("bssid", DEFAULT_MAC),
                            rssi = item.optInt("rssi", -55),
                            frequency = item.optInt("frequency", 5200),
                            channelWidth = item.optInt("channelWidth", 2),
                            capabilities = item.optString("capabilities", "[WPA2-PSK-CCMP][ESS]"),
                            standard = item.optInt("standard", 6),
                        ),
                    )
                }
            }
        }.onFailure {
            if (mockState.enableDebugLog) {
                Logger.warn("FakeLoc: Failed to parse mockWifiList", it)
            }
        }.getOrElse { emptyList() }
    }

    // ==================== 地理计算工具 ====================

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radius * c
    }

    fun jitterLocation(
        lat: Double = locationData.latitude,
        lon: Double = locationData.longitude,
        n: Double = Random.nextDouble(0.0, locationData.accuracy.toDouble()),
        angle: Double = locationData.bearing
    ): Pair<Double, Double> {
        val earthRadius = 6371000.0
        val radiusInDegrees = n / 15 / earthRadius * (180 / PI)

        val jitterAngle = if (Random.nextBoolean()) angle + 45 else angle - 45

        val newLat = lat + radiusInDegrees * cos(Math.toRadians(jitterAngle))
        val newLon = lon + radiusInDegrees * sin(Math.toRadians(jitterAngle)) / cos(Math.toRadians(lat))

        return Pair(newLat, newLon)
    }

    fun moveLocation(
        lat: Double = locationData.latitude,
        lon: Double = locationData.longitude,
        n: Double,
        angle: Double = locationData.bearing
    ): Pair<Double, Double> {
        val earthRadius = 6371000.0
        val radiusInDegrees = Random.nextDouble(n, n + 1.2) / earthRadius * (180 / PI)
        val newLat = lat + radiusInDegrees * cos(Math.toRadians(angle))
        val newLon = lon + radiusInDegrees * sin(Math.toRadians(angle)) / cos(Math.toRadians(lat))
        return Pair(newLat, newLon)
    }

    fun calculateBearing(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val lat1 = Math.toRadians(latA)
        val lon1 = Math.toRadians(lonA)
        val lat2 = Math.toRadians(latB)
        val lon2 = Math.toRadians(lonB)

        val deltaLon = lon2 - lon1

        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360  // 标准化到0-360度

        return bearing
    }
}
