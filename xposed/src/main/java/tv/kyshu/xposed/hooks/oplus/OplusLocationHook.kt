package tv.kyshu.xposed.hooks.oplus

import android.location.LocationListener
import android.os.Bundle
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.BaseLocationHook
import tv.kyshu.xposed.hooks.blindhook.BlindHookLocation
import tv.kyshu.xposed.hooks.blindhook.BlindHookLocation.invoke
import tv.kyshu.xposed.hooks.fused.ThirdPartyLocationHook
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.afterHook
import tv.kyshu.xposed.utils.beforeHook
import tv.kyshu.xposed.utils.hookMethodAfter
import tv.kyshu.xposed.utils.onceHookBefore
import tv.kyshu.xposed.utils.toClass
import java.lang.reflect.Modifier

object OplusLocationHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        ThirdPartyLocationHook(classLoader)

        // OPlus/Oppo proprietary location services
        val oplusClasses = listOf(
            "oplus.location.OplusLocationManager",
            "com.oplus.location.OplusLocationProvider",
        )
        for (className in oplusClasses) {
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
            Logger.info("[OplusLocationHook] Found $className, applying BlindHookLocation")
            val hooked = BlindHookLocation(clazz, classLoader)
            if (hooked > 0) {
                Logger.info("[OplusLocationHook] BlindHookLocation hooked $hooked methods on $className")
            }
            // Also hook sendExtraCommand if present
            clazz.declaredMethods.filter {
                it.name == "sendExtraCommand" && Modifier.isPublic(it.modifiers)
            }.forEach { method ->
                method.onceHookBefore {
                    if (args.size < 2 || !FakeLoc.enable) return@onceHookBefore
                    val provider = args[0] as? String ?: return@onceHookBefore
                    val command = args[1] as? String ?: return@onceHookBefore
                    val bundle = args.getOrNull(2) as? Bundle
                    if (bundle != null && provider == tv.kyshu.xposed.utils.ChannelIdentity.providerName) {
                        if (tv.kyshu.xposed.RemoteCommandHandler.handleInstruction(command, bundle)) {
                            result = true
                        }
                    }
                }
            }
        }

        // OPlus location feature hooks
        val oplusFeatureClasses = listOf(
            "oplus.location.OplusDeviceConfig",
            "oplus.app.OplusAppUtils",
        )
        for (className in oplusFeatureClasses) {
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
            BlindHookLocation(clazz, classLoader)
            Logger.debug("[OplusLocationHook] Applied BlindHookLocation to $className")
        }

        // OPlus telephony vendor hooks
        val oplusTelephonyClasses = listOf(
            "oplus.telephony.OplusTelephonyManager",
            "com.oplus.telephony.OplusTelephonyManager",
            "oplus.telephony.OplusCellLocation",
            "com.oplus.telephony.OplusCellIdentity",
        )
        for (className in oplusTelephonyClasses) {
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
            Logger.info("[OplusLocationHook] Found OPlus telephony class: $className")
            val hooked = BlindHookLocation(clazz, classLoader)
            if (hooked > 0) {
                Logger.info("[OplusLocationHook] OPlus telephony: BlindHookLocation hooked $hooked methods on $className")
            }
        }
    }
}