package tv.kyshu.xposed

import android.os.Bundle
import tv.kyshu.xposed.stability.DomainGuard
import tv.kyshu.xposed.utils.DiagnosticAspect
import tv.kyshu.xposed.utils.FlightRecorder
import tv.kyshu.xposed.utils.Logger


interface HookRuntimeStateStore {
    var forceDisable: Boolean
    var enable: Boolean
    var enableMockGnss: Boolean
    var enableMockWifi: Boolean
    var latitude: Double
    var longitude: Double
    var altitude: Double
    var speed: Double
    var accuracy: Float
    var bearing: Double
    var hasBearings: Boolean
}

class HookLifecycleController(
    private val store: HookRuntimeStateStore,
    private val setNativeStatus: (Boolean) -> Unit = {},
    private val clearListeners: () -> Unit = {},
    private val onStart: () -> Unit = {},
    private val refreshGnssStatus: () -> Unit = {},
    private val isSystemServerProcess: () -> Boolean = { false },
) {
    fun supports(commandId: String): Boolean = commandId in supportedCommands

    fun handle(commandId: String, rely: Bundle): Boolean {
        return DiagnosticAspect.traceBusiness(
            label = "HookLifecycleController.handle",
            detail = "command=$commandId ${DiagnosticAspect.bundleSummary(rely)}",
        ) {
        val result = when (commandId) {
            "start" -> {
                if (store.forceDisable) {
                    Logger.warn("[HookLifecycle] START blocked: forceDisable is active")
                    false
                } else {
                    val hasLat = rely.containsKey("lat")
                    val hasLon = rely.containsKey("lon")
                    if (hasLat != hasLon) {
                        throw IllegalArgumentException("start requires lat/lon pair; hasLat=$hasLat hasLon=$hasLon")
                    }
                    val parsedLat = if (hasLat) {
                        MockCommandContract.requireLatitude(MockCommandContract.Extract.requireDouble(rely, "lat"), "lat")
                    } else {
                        null
                    }
                    val parsedLon = if (hasLon) {
                        MockCommandContract.requireLongitude(MockCommandContract.Extract.requireDouble(rely, "lon"), "lon")
                    } else {
                        null
                    }
                    val parsedSpeed = if (rely.containsKey("speed")) {
                        MockCommandContract.requireSpeed(MockCommandContract.Extract.requireDouble(rely, "speed"), "speed")
                    } else {
                        null
                    }
                    val parsedAltitude = if (rely.containsKey("altitude")) {
                        MockCommandContract.requireAltitude(MockCommandContract.Extract.requireDouble(rely, "altitude"), "altitude")
                    } else {
                        null
                    }
                    val parsedAccuracy = if (rely.containsKey("accuracy")) {
                        MockCommandContract.requireAccuracy(MockCommandContract.Extract.requireFloat(rely, "accuracy"), "accuracy")
                    } else {
                        null
                    }
                    if (parsedLat != null && parsedLon != null) {
                        store.latitude = parsedLat
                        store.longitude = parsedLon
                    }
                    if (rely.containsKey("enable_mock_gnss")) {
                        store.enableMockGnss = rely.getBoolean("enable_mock_gnss")
                    }
                    // 星图与位置模拟统一开关：开启位置模拟同时开启GNSS模拟
                    if (!rely.containsKey("enable_mock_gnss")) {
                        store.enableMockGnss = true
                    }
                    store.enable = true
                    if (parsedSpeed != null) store.speed = parsedSpeed
                    if (parsedAltitude != null) store.altitude = parsedAltitude
                    if (parsedAccuracy != null) store.accuracy = parsedAccuracy
                    onStart()
                    setNativeStatus(true)
                    if (isSystemServerProcess() && store.enableMockGnss) {
                        refreshGnssStatus()
                    }
                    true
                }
            }

            "stop" -> {
                store.enable = false
                store.hasBearings = false
                setNativeStatus(false)
                clearListeners()
                true
            }

            "is_start" -> {
                rely.putBoolean("is_start", store.enable)
                Logger.info("[HookLifecycle] is_start=${store.enable}")
                true
            }

            "start_gnss_mock" -> {
                store.enableMockGnss = true
                Logger.info("[HookLifecycle] start_gnss_mock enableMockGnss=${store.enableMockGnss}")
                true
            }

            "stop_gnss_mock" -> {
                store.enableMockGnss = false
                Logger.info("[HookLifecycle] stop_gnss_mock enableMockGnss=${store.enableMockGnss}")
                true
            }

            "is_gnss_start" -> {
                rely.putBoolean("is_gnss_start", store.enableMockGnss)
                Logger.info("[HookLifecycle] is_gnss_start=${store.enableMockGnss}")
                true
            }

            "start_wifi_mock" -> {
                store.enableMockWifi = true
                Logger.info("[HookLifecycle] start_wifi_mock enableMockWifi=${store.enableMockWifi}")
                true
            }

            "stop_wifi_mock" -> {
                store.enableMockWifi = false
                Logger.info("[HookLifecycle] stop_wifi_mock enableMockWifi=${store.enableMockWifi}")
                true
            }

            "is_wifi_mock_start" -> {
                rely.putBoolean("is_wifi_mock_start", store.enableMockWifi)
                Logger.info("[HookLifecycle] is_wifi_mock_start=${store.enableMockWifi}")
                true
            }

            "ping" -> {
                rely.putString("pong", "pong")
                true
            }

            else -> false
        }
        if (result) {
            FlightRecorder.state(
                stepId = "LIFECYCLE",
                detail = commandId,
                extra = mapOf(
                    "enabled_hooks" to enabledHookCount(),
                    "listener_count" to 0,
                    "safe_mode" to DomainGuard.safeMode,
                ),
            )
            Logger.info("[HookLifecycle] $commandId" +
                (if (commandId == "start" && rely.containsKey("lat") && rely.containsKey("lon"))
                    " locationProvided=true,enableMockGnss=${rely.getBoolean("enable_mock_gnss", store.enableMockGnss)}" else "") +
                (if (commandId == "stop") " enabled=${store.enable}" else "") +
                (if (commandId == "ping") " pong=pong" else ""))
        } else {
            Logger.warn("[HookLifecycle] unknown command: $commandId")
        }
        result
        }
    }

    private fun enabledHookCount(): Int {
        var count = 0
        if (store.enable) count++
        if (store.enableMockGnss) count++
        if (store.enableMockWifi) count++
        return count
    }

    companion object {
        private val supportedCommands = setOf(
            "start",
            "stop",
            "is_start",
            "start_gnss_mock",
            "stop_gnss_mock",
            "is_gnss_start",
            "start_wifi_mock",
            "stop_wifi_mock",
            "is_wifi_mock_start",
            "ping",
        )
    }
}
