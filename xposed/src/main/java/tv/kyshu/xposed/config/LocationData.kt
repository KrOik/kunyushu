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

import android.location.Location
import android.location.LocationManager

/**
 * 位置模拟数据 — 经纬度、精度、速度、GNSS/NMEA 配置、RINEX 路径等。
 */
class LocationData {
    companion object {
        private const val DEFAULT_MIN_SATELLITES = 12
        private const val MAX_MOCK_SATELLITES = 35
    }

    // ==================== 坐标与运动参数 ====================

    @Volatile
    var latitude = 0.0

    @Volatile
    var longitude = 0.0

    @Volatile
    var altitude = 80.0

    @Volatile
    var speed = 1.35 // 默认步行速度 (1.2 ~ 1.5 m/s)

    @Volatile
    var minSpeed = 1.2

    @Volatile
    var maxSpeed = 1.5

    @Volatile
    var speedRange = 0.15 // 速度波动范围 (±0.15 m/s)

    @Volatile
    var speedAmplitude = 1.0

    @Volatile
    var hasBearings = false

    // P1-6 FIX: Bearing getter was not thread-safe — it mutated field on every read.
    // Now the getter is pure (no side effects). Auto-advance logic moved to nextBearing().
    @Volatile
    var bearing = 0.0

    @Volatile
    var accuracy = 25.0f
        set(value) {
            field = if (value < 0) -value else value
        }

    // ==================== GNSS / NMEA ====================

    /**
     * 模拟Gnss卫星数据开关
     */
    @Volatile
    var enableMockGnss = false

    /**
     * 是否允许NMEA模块
     */
    @Volatile
    var enableNMEA = false

    /**
     * 模拟最小卫星数量（带归一化：<1→12, >35→35）
     */
    @Volatile
    var minSatellites = DEFAULT_MIN_SATELLITES
        set(value) {
            field = when {
                value <= 0 -> DEFAULT_MIN_SATELLITES
                value > MAX_MOCK_SATELLITES -> MAX_MOCK_SATELLITES
                else -> value
            }
        }

    /**
     * RINEX 导航电文文件路径
     */
    @Volatile
    var rinexPath: String? = null

    // ==================== 大气抖动 ====================

    /**
     * 模拟大气环境对卫星信号的影响
     */
    @Volatile
    var enableAtmosphereJitter = true

    /**
     * 大气抖动强度 (0.0 ~ 1.0)
     * 0.0 = 无抖动, 1.0 = 最大抖动
     */
    @Volatile
    var atmosphereJitterIntensity = 0.3f

    // ==================== 海拔抖动 ====================

    /**
     * 模拟海拔上下抖动
     */
    @Volatile
    var enableAltitudeJitter = true

    /**
     * 海拔抖动幅度（米），默认0.7m
     */
    @Volatile
    var altitudeJitter = 0.7f

    // ==================== 隐私控制 ====================


    /**
     * 是否隐藏模拟位置
     */
    @Volatile
    var hideMock = true

    // ==================== 位置缓存 ====================

    /**
     * 上一次的位置
     */
    @Volatile
    var lastLocation: Location? = null

    // ==================== 辅助方法 ====================

    /**
     * 根据当前模拟配置创建一个 [Location] 对象。
     * Hooks 可直接使用此方法快速获得注入用位置。
     */
    fun createMockedLocation(provider: String = LocationManager.GPS_PROVIDER): Location {
        val loc = Location(provider)
        loc.latitude = latitude
        loc.longitude = longitude
        loc.altitude = altitude
        loc.accuracy = accuracy
        loc.speed = speed.toFloat()
        loc.bearing = bearing.toFloat()
        loc.time = System.currentTimeMillis()
        return loc
    }

    /**
     * Advance bearing by 0.5 degrees when no explicit bearing is set.
     * Must be called from a synchronized context (e.g., update loop).
     */
    fun nextBearing(): Double {
        var b = bearing
        if (!hasBearings) {
            b += 0.5
            if (b >= 360.0) b -= 360.0
            bearing = b
        }
        return b
    }

    // ==================== 子对象专属更新 ====================

    /**
     * 从配置 map 更新 [LocationData] 专属字段。
     */
    internal fun updateFromMap(map: Map<String, Any?>) {
        val newLatitude = map.getStrictAnyVal<Double>("latitude", "latitude", "lat")?.also(::requireLatitude)
        val newLongitude = map.getStrictAnyVal<Double>("longitude", "longitude", "lon")?.also(::requireLongitude)
        val newAltitude = map.getStrictAnyVal<Double>("altitude", "altitude")?.also(::requireFiniteAltitude)
        val newAccuracy = map.getStrictAnyVal<Float>("accuracy", "accuracy")?.also(::requireAccuracy)
        val newSpeed = map.getStrictAnyVal<Double>("speed", "speed")?.also(::requireSpeed)
        val newBearing = map.getStrictAnyVal<Double>("bearing", "bearing")?.also(::requireBearing)

        enableMockGnss = map.getAnyVal(enableMockGnss, "enableMockGnss", "enable_mock_gnss")
        enableNMEA = map.getAnyVal(enableNMEA, "enableNMEA", "enable_nmea")
        if (newLatitude != null) latitude = newLatitude
        if (newLongitude != null) longitude = newLongitude
        if (newAltitude != null) altitude = newAltitude
        if (newAccuracy != null) accuracy = newAccuracy
        if (newSpeed != null) speed = newSpeed
        if (newBearing != null) bearing = newBearing
        val rinexPathValue = when {
            map.containsKey("rinexPath") -> map.getVal("rinexPath", rinexPath ?: "")
            map.containsKey("rinex_path") -> map.getVal("rinex_path", rinexPath ?: "")
            else -> null
        }?.trim()
        if (!rinexPathValue.isNullOrEmpty()) {
            rinexPath = rinexPathValue
        }
        enableAtmosphereJitter = map.getAnyVal(enableAtmosphereJitter, "enableAtmosphereJitter", "enable_atmosphere_jitter")
        atmosphereJitterIntensity = map.getAnyVal(atmosphereJitterIntensity, "atmosphereJitterIntensity", "atmosphere_jitter_intensity")
        enableAltitudeJitter = map.getAnyVal(enableAltitudeJitter, "enableAltitudeJitter", "enable_altitude_jitter")
        altitudeJitter = map.getAnyVal(altitudeJitter, "altitudeJitter", "altitude_jitter")
        hideMock = map.getAnyVal(hideMock, "hideMock", "hide_mock")
        minSatellites = sanitizeMinSatellites(
            when {
                map.containsKey("minSatelliteCount") -> map.getVal("minSatelliteCount", minSatellites)
                map.containsKey("min_satellites") -> map.getVal("min_satellites", minSatellites)
                else -> minSatellites
            }
        )
    }

    private fun sanitizeMinSatellites(value: Int): Int {
        return when {
            value <= 0 -> DEFAULT_MIN_SATELLITES
            value > MAX_MOCK_SATELLITES -> MAX_MOCK_SATELLITES
            else -> value
        }
    }

    private fun requireLatitude(value: Double) {
        if (value !in -90.0..90.0) {
            throw ConfigParseException("Invalid config field 'latitude': out of range [-90,90], value=$value")
        }
    }

    private fun requireLongitude(value: Double) {
        if (value !in -180.0..180.0) {
            throw ConfigParseException("Invalid config field 'longitude': out of range [-180,180], value=$value")
        }
    }

    private fun requireFiniteAltitude(value: Double) {
        if (!value.isFinite() || value < -1000.0 || value > 100000.0) {
            throw ConfigParseException("Invalid config field 'altitude': out of safe range [-1000,100000], value=$value")
        }
    }

    private fun requireAccuracy(value: Float) {
        if (!value.isFinite() || value < 0f || value > 100000f) {
            throw ConfigParseException("Invalid config field 'accuracy': out of safe range [0,100000], value=$value")
        }
    }

    private fun requireSpeed(value: Double) {
        if (!value.isFinite() || value < 0.0 || value > 1000.0) {
            throw ConfigParseException("Invalid config field 'speed': out of safe range [0,1000], value=$value")
        }
    }

    private fun requireBearing(value: Double) {
        if (!value.isFinite() || value < 0.0 || value >= 360.0) {
            throw ConfigParseException("Invalid config field 'bearing': out of range [0,360), value=$value")
        }
    }
}
