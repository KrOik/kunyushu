package tv.kyshu.ext

import android.content.Context
import androidx.core.content.edit
import com.alibaba.fastjson2.JSON
import com.baidu.mapapi.map.BaiduMap
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.mock.HistoricalLocation
import tv.kyshu.ui.mock.HistoricalRoute
import tv.kyshu.xposed.utils.FakeLoc

val Context.sharedPrefs
    get() = getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)!!

var Context.selectLocation: HistoricalLocation?
    get() {
        return sharedPrefs.getString("selectedLocation", null)?.let {
            HistoricalLocation.fromString(it)
        }
    }
    set(value) = sharedPrefs.edit {
        putString("selectedLocation", value?.toString())
    }

var Context.selectRoute: HistoricalRoute?
    get() {
        return sharedPrefs.getString("selectedRoute", null)?.let {
            try {
                JSON.parseObject(it, HistoricalRoute::class.java)
            } catch (e: Exception) {
                sharedPrefs.edit {
                    putString("selectedRoute", "")
                }
                null
            }
        }
    }
    set(value) = sharedPrefs.edit {
        putString("selectedRoute", JSON.toJSONString(value))
    }

val Context.historicalLocations: List<HistoricalLocation>
    get() {
        return sharedPrefs.getStringSet("locations", emptySet())?.map {
            HistoricalLocation.fromString(it)
        } ?: emptyList()
    }

var Context.rawHistoricalLocations: Set<String>
    get() {
        return sharedPrefs.getStringSet("locations", emptySet()) ?: emptySet()
    }
    set(value) {
        sharedPrefs.edit {
            putStringSet("locations", value)
        }
    }

var Context.jsonHistoricalRoutes: String
    get() {
        return sharedPrefs.getString("routes", null) ?: ""
    }
    set(value) {
        sharedPrefs.edit {
            putString("routes", value)
        }
    }

var Context.reportDuration: Int
    get() = sharedPrefs.getInt("reportDuration", 100)
    set(value) = sharedPrefs.edit {
        putInt("reportDuration", value)
    }

var Context.minSatelliteCount: Int
    get() = sharedPrefs.getInt("minSatelliteCount", 12)
    set(value) = sharedPrefs.edit {
        putInt("minSatelliteCount", value)
    }

var Context.mapType: Int
    get() = sharedPrefs.getInt("mapType", BaiduMap.MAP_TYPE_NORMAL)
    set(value) = sharedPrefs.edit {
        putInt("mapType", value)
    }

var Context.rockerCoords: Pair<Int, Int>
    get() {
        val x = sharedPrefs.getInt("rocker_x", 0)
        val y = sharedPrefs.getInt("rocker_y", 0)
        return Pair(x, y)
    }
    set(value) = sharedPrefs.edit {
        putInt("rocker_x", value.first)
        putInt("rocker_y", value.second)
    }

var Context.speed: Double
    get() = sharedPrefs.getFloat("speed", FakeLoc.speed.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("speed", value.toFloat())
    }

var Context.altitude: Double
    get() = sharedPrefs.getFloat("altitude", FakeLoc.altitude.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("altitude", value.toFloat())
    }

var Context.accuracy: Float
    get() = sharedPrefs.getFloat("accuracy", FakeLoc.accuracy)
    set(value) = sharedPrefs.edit {
        putFloat("accuracy", value)
    }

var Context.needOpenSELinux: Boolean
    get() = sharedPrefs.getBoolean("needOpenSELinux", false)
    set(value) = sharedPrefs.edit {
        putBoolean("needOpenSELinux", value)
    }

var Context.needDowngradeToCdma: Boolean
    get() = sharedPrefs.getBoolean("needDowngradeToCdma", FakeLoc.needDowngradeToCdma)
    set(value) = sharedPrefs.edit {
        putBoolean("needDowngradeToCdma", value)
    }

var Context.hookSensor: Boolean
    get() = sharedPrefs.getBoolean("hookSensor", false)
    set(value) = sharedPrefs.edit {
        putBoolean("hookSensor", value)
    }

//var Context.updateInterval: Long
//    get() = sharedPrefs.getLong("updateInterval", FakeLoc.updateInterval)
//
//    set(value) = sharedPrefs.edit {
//        putLong("updateInterval", value)
//    }
//
//var Context.hideMock: Boolean
//    get() = sharedPrefs.getBoolean("hideMock", FakeLoc.hideMock)
//
//    set(value) = sharedPrefs.edit {
//        putBoolean("hideMock", value)
//    }

var Context.debug: Boolean
    get() = sharedPrefs.getBoolean("debug", FakeLoc.enableDebugLog)
    set(value) = sharedPrefs.edit {
        putBoolean("debug", value)
    }

var Context.disableGetCurrentLocation: Boolean
    get() = sharedPrefs.getBoolean("disableGetCurrentLocation", FakeLoc.disableGetCurrentLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("disableGetCurrentLocation", value)
    }

var Context.disableRegisterLocationListener: Boolean
    get() = sharedPrefs.getBoolean(
        "disableRegitserLocationListener",
        FakeLoc.disableRegisterLocationListener
    )
    set(value) = sharedPrefs.edit {
        putBoolean("disableRegitserLocationListener", value)
    }

var Context.disableFusedProvider: Boolean
    get() = sharedPrefs.getBoolean("disableFusedProvider", FakeLoc.disableFusedLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("disableFusedProvider", value)
        FakeLoc.disableFusedLocation = value
    }

/**
 * 是否允许地理围栏请求
 */
var Context.enableRequestGeofence: Boolean
    get() = sharedPrefs.getBoolean("enableRequestGeofence", !FakeLoc.disableRequestGeofence)
    set(value) = sharedPrefs.edit {
        putBoolean("enableRequestGeofence", value)
        FakeLoc.disableRequestGeofence = !value
    }

/**
 * 是否允许位置获取
 */
var Context.enableGetFromLocation: Boolean
    get() = sharedPrefs.getBoolean("enableGetFromLocation", !FakeLoc.disableGetFromLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("enableGetFromLocation", value)
        FakeLoc.disableGetFromLocation = !value
    }

/**
 * 是否允许AGPS模块
 */
var Context.enableAGPS: Boolean
    get() = sharedPrefs.getBoolean("enableAGPS", FakeLoc.enableAGPS)
    set(value) = sharedPrefs.edit {
        putBoolean("enableAGPS", value)
        FakeLoc.enableAGPS = value
    }

/**
 * 是否允许NMEA模块
 */
var Context.enableNMEA: Boolean
    get() = sharedPrefs.getBoolean("enableNMEA", FakeLoc.enableNMEA)
    set(value) = sharedPrefs.edit {
        putBoolean("enableNMEA", value)
        FakeLoc.enableNMEA = value
    }

var Context.disableWifiScan: Boolean
    get() = sharedPrefs.getBoolean("disableWifiScan", FakeLoc.enableNMEA)
    set(value) = sharedPrefs.edit {
        putBoolean("disableWifiScan", value)
        FakeLoc.enableMockWifi = value
    }



// ==================== WiFi模拟参数 ====================

var Context.hookWifi: Boolean
    get() = sharedPrefs.getBoolean("hookWifi", FakeLoc.wifiData.hookWifi)
    set(value) = sharedPrefs.edit {
        putBoolean("hookWifi", value)
        FakeLoc.wifiData.hookWifi = value
    }

var Context.mockSSID: String
    get() = sharedPrefs.getString("mockSSID", FakeLoc.wifiData.mockSSID) ?: FakeLoc.wifiData.mockSSID
    set(value) = sharedPrefs.edit {
        putString("mockSSID", value)
        FakeLoc.wifiData.mockSSID = value
    }

var Context.mockBSSID: String
    get() = sharedPrefs.getString("mockBSSID", FakeLoc.wifiData.mockBSSID) ?: FakeLoc.wifiData.mockBSSID
    set(value) = sharedPrefs.edit {
        putString("mockBSSID", value)
        FakeLoc.wifiData.mockBSSID = value
    }

var Context.mockMac: String
    get() = sharedPrefs.getString("mockMac", FakeLoc.wifiData.mockMac) ?: FakeLoc.wifiData.mockMac
    set(value) = sharedPrefs.edit {
        putString("mockMac", value)
        FakeLoc.wifiData.mockMac = value
    }

var Context.mockIp: String
    get() = sharedPrefs.getString("mockIp", FakeLoc.wifiData.mockIp) ?: FakeLoc.wifiData.mockIp
    set(value) = sharedPrefs.edit {
        putString("mockIp", value)
        FakeLoc.wifiData.mockIp = value
    }

var Context.mockWifiScanCount: Int
    get() = sharedPrefs.getInt("mockWifiScanCount", FakeLoc.wifiData.mockWifiScanCount)
    set(value) = sharedPrefs.edit {
        putInt("mockWifiScanCount", value)
        FakeLoc.wifiData.mockWifiScanCount = value
    }

// ==================== 传感器模拟 ====================

var Context.enableStepCountMock: Boolean
    get() = sharedPrefs.getBoolean("enableStepCountMock", FakeLoc.sensorData.enableStepCountMock)
    set(value) = sharedPrefs.edit {
        putBoolean("enableStepCountMock", value)
        FakeLoc.sensorData.enableStepCountMock = value
    }

var Context.stepCount: Int
    get() = sharedPrefs.getInt("stepCount", FakeLoc.sensorData.stepCount)
    set(value) = sharedPrefs.edit {
        putInt("stepCount", value)
        FakeLoc.sensorData.stepCount = value
    }

var Context.enableShakeMock: Boolean
    get() = sharedPrefs.getBoolean("enableShakeMock", FakeLoc.sensorData.enableShakeMock)
    set(value) = sharedPrefs.edit {
        putBoolean("enableShakeMock", value)
        FakeLoc.sensorData.enableShakeMock = value
    }

var Context.keepWalkingWaveform: Boolean
    get() = sharedPrefs.getBoolean("keepWalkingWaveform", FakeLoc.sensorData.keepWalkingWaveform)
    set(value) = sharedPrefs.edit {
        putBoolean("keepWalkingWaveform", value)
        FakeLoc.sensorData.keepWalkingWaveform = value
    }

var Context.dozeSensorIntervalMs: Long
    get() = sharedPrefs.getLong("dozeSensorIntervalMs", FakeLoc.sensorData.dozeSensorIntervalMs)
    set(value) = sharedPrefs.edit {
        putLong("dozeSensorIntervalMs", value)
        FakeLoc.sensorData.dozeSensorIntervalMs = value
    }

// ==================== 步态参数 ====================

var Context.gaitFreq: Float
    get() = sharedPrefs.getFloat("gaitFreq", FakeLoc.gaitData.gaitFreq)
    set(value) = sharedPrefs.edit {
        putFloat("gaitFreq", value)
        FakeLoc.gaitData.gaitFreq = value
    }

var Context.gaitAmpScale: Float
    get() = sharedPrefs.getFloat("gaitAmpScale", FakeLoc.gaitData.gaitAmpScale)
    set(value) = sharedPrefs.edit {
        putFloat("gaitAmpScale", value)
        FakeLoc.gaitData.gaitAmpScale = value
    }

// ==================== 其他 ====================

var Context.hideBaseStation: Boolean
    get() = sharedPrefs.getBoolean("hideBaseStation", FakeLoc.hideBaseStation)
    set(value) = sharedPrefs.edit {
        putBoolean("hideBaseStation", value)
        FakeLoc.hideBaseStation = value
    }

var Context.loopBroadcastLocation: Boolean
    get() = sharedPrefs.getBoolean("loopBroadcastLocation", FakeLoc.loopBroadcastLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("loopBroadcastLocation", value)
        FakeLoc.loopBroadcastLocation = value
    }

var Context.hideMock: Boolean
    get() = sharedPrefs.getBoolean("hideMock", FakeLoc.locationData.hideMock)
    set(value) = sharedPrefs.edit {
        putBoolean("hideMock", value)
        FakeLoc.locationData.hideMock = value
    }

var Context.disableNetworkLocation: Boolean
    get() = sharedPrefs.getBoolean("disableNetworkLocation", FakeLoc.disableNetworkLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("disableNetworkLocation", value)
        FakeLoc.disableNetworkLocation = value
    }

var Context.speedAmplitude: Double
    get() = sharedPrefs.getFloat("speedAmplitude", FakeLoc.speedAmplitude.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("speedAmplitude", value.toFloat())
        FakeLoc.speedAmplitude = value
    }

var Context.minSpeed: Double
    get() = sharedPrefs.getFloat("minSpeed", FakeLoc.minSpeed.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("minSpeed", value.toFloat())
        FakeLoc.minSpeed = value
    }

var Context.maxSpeed: Double
    get() = sharedPrefs.getFloat("maxSpeed", FakeLoc.maxSpeed.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("maxSpeed", value.toFloat())
        FakeLoc.maxSpeed = value
    }
