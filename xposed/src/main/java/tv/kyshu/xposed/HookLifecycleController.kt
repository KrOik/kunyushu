package tv.kyshu.xposed

import android.os.Bundle
import tv.kyshu.xposed.stability.DomainGuard
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
        val result = when (commandId) {
            "start" -> {
                if (store.forceDisable) {
                    Logger.warn("[HookLifecycle] START blocked: forceDisable is active")
                    return@handle false
                }
                if (rely.containsKey("lat") && rely.containsKey("lon")) {
                    store.latitude = rely.getDouble("lat")
                    store.longitude = rely.getDouble("lon")
                }
                if (rely.containsKey("enable_mock_gnss")) {
                    store.enableMockGnss = rely.getBoolean("enable_mock_gnss")
                }
                store.enable = true
                store.speed = rely.getDouble("speed", store.speed)
                store.altitude = rely.getDouble("altitude", store.altitude)
                store.accuracy = rely.getFloat("accuracy", store.accuracy)
                onStart()
                setNativeStatus(true)
                if (isSystemServerProcess() && store.enableMockGnss) {
                    refreshGnssStatus()
                }
                true
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
                true
            }

            "start_gnss_mock" -> {
                store.enableMockGnss = true
                true
            }

            "stop_gnss_mock" -> {
                store.enableMockGnss = false
                true
            }

            "is_gnss_start" -> {
                rely.putBoolean("is_gnss_start", store.enableMockGnss)
                true
            }

            "start_wifi_mock" -> {
                store.enableMockWifi = true
                true
            }

            "stop_wifi_mock" -> {
                store.enableMockWifi = false
                true
            }

            "is_wifi_mock_start" -> {
                rely.putBoolean("is_wifi_mock_start", store.enableMockWifi)
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
        return result
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
