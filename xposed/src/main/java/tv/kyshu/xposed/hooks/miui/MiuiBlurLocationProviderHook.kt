package tv.kyshu.xposed.hooks.miui

import android.os.Build
import android.telephony.CellIdentity
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.BaseLocationHook
import tv.kyshu.xposed.factory.CellInfoFactory
import tv.kyshu.xposed.hooks.blindhook.BlindHookLocation
import tv.kyshu.xposed.utils.BinderUtils
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.beforeHook
import tv.kyshu.xposed.utils.onceHookAllMethod
import tv.kyshu.xposed.utils.onceHookMethodBefore

object MiuiBlurLocationProviderHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        val cMiuiBlurLocationManagerImpl = XposedHelpers.findClassIfExists("com.android.server.location.MiuiBlurLocationManagerImpl", classLoader)
        if (cMiuiBlurLocationManagerImpl != null) {
            BlindHookLocation(cMiuiBlurLocationManagerImpl, classLoader)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val hooker: XC_MethodHook.MethodHookParam.() -> Unit = {
                    if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                        result = CellInfoFactory.createCdmaIdentity(FakeLoc.latitude, FakeLoc.longitude)
                    }
                }
                if(cMiuiBlurLocationManagerImpl.onceHookMethodBefore("getBlurryCellLocation", CellIdentity::class.java) { hooker() } == null) {
                    cMiuiBlurLocationManagerImpl.onceHookMethodBefore("getBlurryCellLocation",
                        CellIdentity::class.java, Int::class.java, String::class.java) { hooker() }
                }
            }

            cMiuiBlurLocationManagerImpl.onceHookAllMethod("getBlurryCellInfos", beforeHook {
                if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                    result = CellInfoFactory.createCdmaInfoList(FakeLoc.latitude, FakeLoc.longitude)
                }
            })
        }
    }


}
