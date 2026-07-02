package tv.kyshu.xposed.hooks.miui

import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.BaseLocationHook
import tv.kyshu.xposed.stability.DomainGuard
import tv.kyshu.xposed.utils.FlightRecorder
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.onceHookMethodBefore

object MiuiLocationManagerHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        if (!ensureDivineService("MiuiLocationManagerHook")) {
            Logger.warn("DivineService not ready in MiuiLocationManagerHook; installing hooks with deferred config sync")
        }

        // MIUI 14+ uses MiuiLocationManager / MiuiDeviceConfigProvider
        val candidateClassNames = listOf(
            "com.android.server.location.MiuiLocationManager",
            "com.android.server.location.MiuiDeviceConfigProvider",
            "com.android.server.location.MiuiBlurLocationManagerImpl",
        )

        for (className in candidateClassNames) {
            runCatching {
                val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@runCatching
                Logger.info("[MiuiLocationManagerHook] Found class: $className, installing hooks")

                // Hook deviceConfig / configProvider to block MIUI-specific location customization
                clazz.onceHookMethodBefore("onDeviceConfigChanged") {
                    Logger.debug("[MiuiLocationManagerHook] Blocked onDeviceConfigChanged")
                }
                clazz.onceHookMethodBefore("updateDeviceConfig") {
                    Logger.debug("[MiuiLocationManagerHook] Blocked updateDeviceConfig")
                }

                FlightRecorder.hookInstall("MIUI", "MIUI_LOCATION_MGR_OK",
                    mapOf("class" to className))
            }.onFailure { e ->
                FlightRecorder.hookFail("MIUI", "MIUI_LOCATION_MGR_FAIL",
                    mapOf("class" to className, "error" to e.javaClass.simpleName))
                DomainGuard.reportCrash(DomainGuard.Domain.FUSED_LOCATION, e)
            }
        }
    }
}
