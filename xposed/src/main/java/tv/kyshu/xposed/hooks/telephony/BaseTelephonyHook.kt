package tv.kyshu.xposed.hooks.telephony

import android.os.Build
import android.os.Bundle
import android.telephony.NeighboringCellInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import tv.kyshu.xposed.BaseDivineService
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger

abstract class BaseTelephonyHook: BaseDivineService() {
    companion object {
        val hookGetCellLocation = object: XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                if (param == null || param.result == null) return
                if (!FakeLoc.enable) return

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("[BaseTelephonyHook] ${param.method.name}: injected!")
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || param.result.javaClass.name == "android.os.Bundle") {
                    param.result = Bundle().apply {
                        putInt("cid", Int.MAX_VALUE)
                        putInt("lac", Int.MAX_VALUE)
                        putInt("psc", Int.MAX_VALUE)
                        putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                        putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                        putBoolean("empty", false)
                        putBoolean("emptyParcel", false)
                        putInt("mFlags", 1536)
                        putBoolean("parcelled", false)
                        putInt("baseStationId", Int.MAX_VALUE)
                        putInt("systemId", Int.MAX_VALUE)
                        putInt("networkId", Int.MAX_VALUE)
                        putInt("size", 0)
                    }
                } else {
                    param.result = null
                }
            }
        }

        val hookGetNeighboringCellInfoList = object: XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                if (param == null || param.result == null) return
                if (!FakeLoc.enable) return

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("[BaseTelephonyHook] ${param.method.name}: injected!")
                }
                param.result = emptyList<NeighboringCellInfo>()
            }
        }
    }
}