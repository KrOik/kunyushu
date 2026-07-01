package tv.kyshu.xposed.hooks.telephony.miui

import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityWcdma
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellLocation
import android.telephony.cdma.CdmaCellLocation
import android.telephony.gsm.GsmCellLocation
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.hooks.telephony.BaseTelephonyHook
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import java.util.ArrayList

object MiuiTelephonyManagerHook: BaseTelephonyHook() {
    operator fun invoke(classLoader: ClassLoader) {
        try {
            val cMiuiTelephonyManager = XposedHelpers.findClassIfExists(
                "com.miui.internal.telephony.BaseTelephonyManagerAndroidImpl", classLoader)
            val cTelephonyManagerEx = XposedHelpers.findClassIfExists(
                "miui.telephony.TelephonyManagerEx", classLoader)

            if (FakeLoc.enableDebugLog) {
                Logger.debug("[MiuiTelephonyManagerHook] MiuiTelephonyManager found: ${cMiuiTelephonyManager != null}")
                Logger.debug("[MiuiTelephonyManagerHook] TelephonyManagerEx found: ${cTelephonyManagerEx != null}")
            }

            cMiuiTelephonyManager?.let { clazz ->
                val hookedCount = clazz.declaredMethods.count { method ->
                    if (method.returnType == CellLocation::class.java) {
                        XposedBridge.hookMethod(method, hookGetCellLocation)
                        true
                    } else false
                }
                Logger.info("[MiuiTelephonyManagerHook] Hooked $hookedCount CellLocation methods in MiuiTelephonyManager")
            }

            cTelephonyManagerEx?.let { clazz ->
                var count = clazz.declaredMethods.count { method ->
                    if (method.returnType == CellLocation::class.java) {
                        XposedBridge.hookMethod(method, hookGetCellLocation)
                        true
                    } else false
                }
                Logger.info("[MiuiTelephonyManagerHook] Hooked $count CellLocation methods in TelephonyManagerEx")

                count += XposedBridge.hookAllMethods(clazz, "getNeighboringCellInfo", hookGetNeighboringCellInfoList).size
                count += XposedBridge.hookAllMethods(clazz, "getNeighboringCellInfoForSlot", hookGetNeighboringCellInfoList).size
                count += XposedBridge.hookAllMethods(clazz, "getNeighboringCellInfoForSubscription", hookGetNeighboringCellInfoList).size
                Logger.info("[MiuiTelephonyManagerHook] Hooked $count total methods in TelephonyManagerEx")
            }
        } catch (e: Exception) {
            Logger.error("[MiuiTelephonyManagerHook] Failed to install hooks", e)
        }
    }
}