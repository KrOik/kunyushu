@file:Suppress("UNCHECKED_CAST", "PrivateApi")
package tv.kyshu.xposed.hooks.telephony

import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.telephony.CellIdentity
import android.telephony.CellIdentityCdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.SignalStrength
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.factory.CellInfoFactory
import tv.kyshu.xposed.utils.BinderUtils
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.stability.DomainGuard
import tv.kyshu.xposed.stability.WirelessRuntime
import tv.kyshu.xposed.stability.replaceOrPassthrough
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.afterHook
import tv.kyshu.xposed.utils.beforeHook
import tv.kyshu.xposed.utils.hookAllMethods
import tv.kyshu.xposed.utils.hookBefore
import tv.kyshu.xposed.utils.hookMethodBefore
import tv.kyshu.xposed.utils.onceHookBefore
import tv.kyshu.xposed.utils.onceHookDoNothingMethod
import tv.kyshu.xposed.utils.onceHookMethodBefore
import java.lang.reflect.Modifier


internal object TelephonyDowngradeGate {
    fun shouldDowngradeToCdma(): Boolean = FakeLoc.enable && FakeLoc.needDowngradeToCdma &&
        !DomainGuard.isDomainDisabled(DomainGuard.Domain.TELEPHONY)
    fun shouldReplaceCellInfo(): Boolean = FakeLoc.enable && WirelessRuntime.snapshotOrLegacy().cellEnabled &&
        !DomainGuard.isDomainDisabled(DomainGuard.Domain.TELEPHONY)
}

object TelephonyHook: BaseTelephonyHook() {
    internal fun shouldDowngradeToCdma(): Boolean = TelephonyDowngradeGate.shouldDowngradeToCdma()
    internal fun shouldReplaceCellInfo(): Boolean = TelephonyDowngradeGate.shouldReplaceCellInfo()

    internal fun fakeCellIdentityCdma(): CellIdentityCdma {
        return CellInfoFactory.createCdmaIdentity(FakeLoc.latitude, FakeLoc.longitude)
    }

    internal fun fakeCellInfoCdma(): CellInfoCdma {
        return CellInfoFactory.createCdmaInfo(FakeLoc.latitude, FakeLoc.longitude)
    }

    internal fun fakeCellLocationBundle(): Bundle {
        return CellInfoFactory.createCellLocationBundle(FakeLoc.latitude, FakeLoc.longitude)
    }

    private fun fakeCellInfoList(): List<CellInfo> {
        val snapshot = WirelessRuntime.snapshotOrLegacy()
        if (snapshot.cellEnabled && snapshot.cellTowers.isNotEmpty()) {
            return CellInfoFactory.createFromProfilesStrict(snapshot.cellTowers)
        }
        return runCatching { CellInfoFactory.createCdmaInfoList(FakeLoc.latitude, FakeLoc.longitude) }
            .onFailure { Logger.warn("[TelephonyHook] fake CellInfoCdma unavailable; keeping original value", it) }
            .getOrDefault(emptyList())
    }

    operator fun invoke(classLoader: ClassLoader) {
        runCatching {
        val serviceReady = ensureDivineService("TelephonyHook")
        if (!serviceReady) {
            Logger.warn("[TelephonyHook] mock service not ready; installing local telephony hooks and waiting for deferred sync")
        }

//        kotlin.runCatching {
//            val cCellIdentityCdma =
//                XposedHelpers.findClass("android.telephony.CellIdentityCdma", classLoader)
//            val hookCdma = object: XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam?) {
//                    if (param == null) return
//
//                    if (!FakeLocationConfig.enable) return
//
//                    param.args[3] = Int.MAX_VALUE
//                    param.args[4] = Int.MAX_VALUE
//                }
//            }
//            //                                                             nid             sid                 bid            lon            lat                alphal              alphas
//            XposedHelpers.findAndHookConstructor(
//                cCellIdentityCdma,
//                Int::class.java,
//                Int::class.java,
//                Int::class.java,
//                Int::class.java,
//                Int::class.java,
//                String::class.java,
//                String::class.java,
//                hookCdma
//            )
//        }.onFailure {
//            XposedBridge.log("[PTL] Hook CellIdentityCdma failed")
//        }

//        kotlin.runCatching {
//            val cCellIdentityGsm = XposedHelpers.findClass("android.telephony.CellIdentityGsm", classLoader)
//
//        }.onFailure {
//            XposedBridge.log("[PTL] Hook CellIdentityGsm failed")
//        }

//        XposedHelpers.findClassIfExists("android.telephony.TelephonyManager", classLoader)?.let {
//            XposedBridge.hookAllMethods(it, "getNeighboringCellInfo", hookGetNeighboringCellInfoList)
//            XposedBridge.hookAllMethods(it, "getCellLocation", hookGetCellLocation)
//        }

//        kotlin.runCatching {
//            XposedHelpers.findClass("com.android.internal.telephony.ITelephony\$Stub", classLoader)
//        }.onSuccess {
//            it.declaredMethods.forEach {
//                if (it.name == "onTransact") {
//                    hookOnTransactForServiceInstance(it)
//                    return@forEach
//                }
//            }
//        }.onFailure {
//            XposedBridge.log("[PTL] ITelephony.Stub not found: ${it.stackTraceToString()}")
//        }

        if (!FakeLoc.needDowngradeToCdma) {
            Logger.info("[TelephonyHook] CDMA downgrade disabled at install; installing dormant runtime-gated hooks")
        }

        val cPhoneInterfaceManager = XposedHelpers.findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader)
            ?: run {
                Logger.warn("[TelephonyHook] PhoneInterfaceManager not found; package hook skipped")
                return
            }
        Logger.info("[TelephonyHook] installing PhoneInterfaceManager hooks: enable=${FakeLoc.enable}, cdma=${FakeLoc.needDowngradeToCdma}, serviceReady=$serviceReady")

        hookServiceState(cPhoneInterfaceManager)

        val hookGetPhoneTyp = beforeHook {
            if (shouldDowngradeToCdma()) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getActivePhoneType: injected!")
                }

                result = 2
            }
        }

        if(safeHookAll(cPhoneInterfaceManager, "getActivePhoneType", hookGetPhoneTyp).isEmpty()) {
            Logger.error("Hook PhoneInterfaceManager.getActivePhoneType failed")
        }
        if(safeHookAll(cPhoneInterfaceManager, "getActivePhoneTypeForSlot", hookGetPhoneTyp).isEmpty()) {
            Logger.warn("Hook PhoneInterfaceManager.getActivePhoneTypeForSlot failed")
        }

        cPhoneInterfaceManager.declaredMethods.find { it.name == "getAllCellInfo" }?.let {
            val hookGetAllCellInfo = afterHook {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getAllCellInfo: injected! caller = ${BinderUtils.getCallerUid()}")
                }

                if (shouldDowngradeToCdma() || shouldReplaceCellInfo()) {
                    val original = result
                    result = replaceOrPassthrough(DomainGuard.Domain.TELEPHONY, "getAllCellInfo", original) {
                        fakeCellInfoList().takeIf { it.isNotEmpty() }
                            ?: throw IllegalStateException("empty CellInfo replacement")
                    }
                }
	            }
            if (runCatching { XposedBridge.hookMethod(it, hookGetAllCellInfo) }.getOrNull() == null) {
                Logger.error("Hook PhoneInterfaceManager.getAllCellInfo failed")
            }
        }

        if(runCatching { XposedBridge.hookAllMethods(cPhoneInterfaceManager, "getCellLocation", afterHook {
                if (!shouldDowngradeToCdma()) {
                    return@afterHook
                }
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("${method.name}: injected!")
                }
                val currentResult = result
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || currentResult?.javaClass?.name == "android.os.Bundle") {
                    result = fakeCellLocationBundle()
                } else {
                    runCatching { fakeCellIdentityCdma() }
                        .onSuccess { result = it }
                        .onFailure { Logger.warn("[TelephonyHook] fake CellIdentityCdma unavailable; keeping original value", it) }
	                }
	            }) }.getOrDefault(emptySet()).isEmpty()) {
            Logger.error("Hook PhoneInterfaceManager.getCellLocation failed")
        }

        beforeHook {
            if (shouldDowngradeToCdma()) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getDataNetworkType: injected!")
                }
                result = 4
            }
        }.let {
            safeHookAll(cPhoneInterfaceManager, "getDataNetworkType", it)
            safeHookAll(cPhoneInterfaceManager, "getNetworkType", it)
            safeHookAll(cPhoneInterfaceManager, "getDataNetworkTypeForSubscriber", it)
            safeHookAll(cPhoneInterfaceManager, "getNetworkTypeForSubscriber", it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (safeHookAll(cPhoneInterfaceManager, "getNeighboringCellInfo", beforeHook {
                if (!shouldDowngradeToCdma()) {
                    return@beforeHook
                }

                result = CellInfoFactory.createNeighboringCellInfoList()
            }).isEmpty()) {
                Logger.error("Hook PhoneInterfaceManager.getNeighboringCellInfo failed")
	            }
	        }

        hookRequestCellInfoUpdate(cPhoneInterfaceManager)

        val cTelephonyRegistry = XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)
        if (cTelephonyRegistry == null) {
            Logger.debug("TelephonyRegistry not present in this process; system_server hook owns it")
        } else {
            hookTelephonyRegistry(cTelephonyRegistry)
            }
        }.onFailure { e ->
            Logger.error("[TelephonyHook] invoke failed", e)
            DomainGuard.reportCrash(DomainGuard.Domain.TELEPHONY, e)
        }

	    }

    private fun safeHookAll(ownerClass: Class<*>, methodName: String, hook: de.robv.android.xposed.XC_MethodHook): Set<de.robv.android.xposed.XC_MethodHook.Unhook> {
        return runCatching { ownerClass.hookAllMethods(methodName, hook) }
            .onFailure { Logger.warn("[TelephonyHook] $methodName hook skipped", it) }
            .getOrDefault(emptySet())
    }

    private fun hookServiceState(cPhoneInterfaceManager: Class<*>) {
        val hook = afterHook {
            if (!shouldDowngradeToCdma()) return@afterHook
            val serviceState = result ?: return@afterHook

            runCatching { XposedHelpers.callMethod(serviceState, "setRoaming", false) }
                .onFailure { Logger.warn("[TelephonyHook] ServiceState setRoaming failed", it) }
            runCatching { XposedHelpers.callMethod(serviceState, "setVoiceRegState", 0) }
                .onFailure { Logger.warn("[TelephonyHook] ServiceState setVoiceRegState failed", it) }
            runCatching { XposedHelpers.callMethod(serviceState, "setDataRegState", 0) }
                .onFailure { Logger.warn("[TelephonyHook] ServiceState setDataRegState failed", it) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    val list = XposedHelpers.callMethod(serviceState, "getNetworkRegistrationInfoList") as? List<*>
                    val fakeIdentity = runCatching { fakeCellIdentityCdma() }.getOrNull() ?: return@runCatching
                    list?.filterNotNull()?.forEach { info ->
                        XposedHelpers.callMethod(info, "setCellIdentity", fakeIdentity)
                    }
                }.onFailure {
                    Logger.warn("[TelephonyHook] ServiceState network reg info hook failed", it)
                }
            }
        }

        listOf("getServiceState", "getServiceStateForSubscriber").forEach { methodName ->
            if (safeHookAll(cPhoneInterfaceManager, methodName, hook).isEmpty()) {
                Logger.warn("[TelephonyHook] $methodName not hooked")
            }
        }
    }

    private fun hookRequestCellInfoUpdate(ownerClass: Class<*>) {
        ownerClass.declaredMethods
            .filter {
                it.name == "requestCellInfoUpdate" ||
                    it.name == "requestCellInfoUpdateWithWorkSource"
            }
            .forEach { method ->
                runCatching {
                    XposedBridge.hookMethod(method, beforeHook {
                        hookCellInfoCallbacks(args.filterNotNull())
                    })
                }.onFailure {
                    Logger.warn("[TelephonyHook] ${method.name} hook skipped", it)
                }
            }
    }

    private fun hookCellInfoCallbacks(callbacks: Iterable<Any>) {
        callbacks.forEach { callback ->
            callback.javaClass.declaredMethods
                .filter { method -> method.name == "onCellInfo" && method.parameterTypes.size == 1 }
                .forEach { onCellInfoMethod ->
                    runCatching {
                        onCellInfoMethod.isAccessible = true
                        onCellInfoMethod.onceHookBefore {
                            if (!shouldDowngradeToCdma() && !shouldReplaceCellInfo()) return@onceHookBefore
                            val original = args[0]
                            args[0] = replaceOrPassthrough(DomainGuard.Domain.TELEPHONY,
                                "requestCellInfoUpdate.onCellInfo", original) {
                                fakeCellInfoList().takeIf { it.isNotEmpty() }
                                    ?: throw IllegalStateException("empty CellInfo replacement")
                            }
                        }
                    }.onFailure {
                        Logger.warn("[TelephonyHook] requestCellInfoUpdate callback hook failed", it)
                    }
                }
        }
    }

    fun hookTelephonyRegistry(cTelephonyRegistry: Class<*>) {
        cTelephonyRegistry.declaredMethods.filter { (it.name == "listen" || it.name == "listenWithEventList") && !Modifier.isAbstract(it.modifiers) }
            .map {
                it to it.parameterTypes.indexOfFirst { typ -> typ.simpleName == "IPhoneStateListener" }
            }.forEach {
                val (m, idx) = it
                if (idx == -1) {
                    Logger.error("IPhoneStateListener not found")
                    return@forEach
                }
                m.hookBefore {
                    val listener = args[idx] as Any
                    val hasHookOnCellLocationChanged = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        listener.javaClass.onceHookMethodBefore("onCellLocationChanged", CellIdentity::class.java) {
                            if (shouldDowngradeToCdma()) {
                                result = fakeCellIdentityCdma()
                            }
                        }
                    } else {
                        listener.javaClass.onceHookMethodBefore("onCellLocationChanged", Bundle::class.java) {
                            if (shouldDowngradeToCdma()) {
                                result = fakeCellLocationBundle()
                            }
                        }
                    } != null
                    if (!hasHookOnCellLocationChanged) {
                        Logger.error("Hook onCellLocationChanged failed")
                    }
                    listener.javaClass.onceHookDoNothingMethod("onSignalStrengthChanged", Int::class.java) {
                        shouldDowngradeToCdma()
                    }
                    listener.javaClass.onceHookDoNothingMethod("onSignalStrengthsChanged", SignalStrength::class.java) { shouldDowngradeToCdma() }
                }
            }

        cTelephonyRegistry.hookMethodBefore("notifyCellInfo", List::class.java) {
            if (!shouldDowngradeToCdma() && !shouldReplaceCellInfo()) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellInfo: injected!")
            }

            val original = args[0]
            args[0] = replaceOrPassthrough(DomainGuard.Domain.TELEPHONY, "notifyCellInfo", original) {
                fakeCellInfoList().takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("empty CellInfo replacement")
            }
        }

        cTelephonyRegistry.hookMethodBefore("notifyCellInfoForSubscriber", Int::class.java, List::class.java) {
            if (!shouldDowngradeToCdma() && !shouldReplaceCellInfo()) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellInfoForSubscriber: injected!")
            }

            val original = args[1]
            args[1] = replaceOrPassthrough(DomainGuard.Domain.TELEPHONY, "notifyCellInfoForSubscriber", original) {
                fakeCellInfoList().takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("empty CellInfo replacement")
            }
        }

        cTelephonyRegistry.hookMethodBefore("notifyCellLocation", Bundle::class.java) {
            if (!shouldDowngradeToCdma()) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellLocation: injected!")
            }

            args[0] = fakeCellLocationBundle()
        }
        cTelephonyRegistry.hookMethodBefore("notifyCellLocationForSubscriber", Int::class.java, Bundle::class.java) {
            if (!shouldDowngradeToCdma()) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellLocationForSubscriber: injected!")
            }
            args[1] = fakeCellLocationBundle()
        }

        hookRequestCellInfoUpdate(cTelephonyRegistry)
    }

    @Suppress("LocalVariableName")
    fun hookSubOnTransact(classLoader: ClassLoader) {
        val cISub = XposedHelpers.findClassIfExists("com.android.internal.telephony.ISub\$Stub", classLoader)
        if (cISub == null) {
            Logger.error("ISub.Stub not found")
            return
        }

        val subClassName = "com.android.internal.telephony.ISub"
        val TRANSACTION_getActiveSubInfoCount = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getActiveSubInfoCount") }.getOrDefault(-1)
        val TRANSACTION_getActiveSubInfoCountMax = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getActiveSubInfoCountMax") }.getOrDefault(-1)
        val TRANSACTION_getActiveSubscriptionInfoList = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getActiveSubscriptionInfoList") }.getOrDefault(-1)
        val TRANSACTION_getPhoneId = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getPhoneId") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSlotIndex = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getSimStateForSlotIndex") }.getOrDefault(-1)
        val TRANSACTION_isActiveSubId = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_isActiveSubId") }.getOrDefault(-1)
        val TRANSACTION_getNetworkCountryIsoForPhone = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getNetworkCountryIsoForPhone") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSubscriber = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getSimStateForSubscriber") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSlotIdx = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getSimStateForSlotIdx") }.getOrDefault(-1)

        val hookOnTransact = beforeHook {
            if (!shouldDowngradeToCdma()) {
                return@beforeHook
            }

            val code = args[0] as Int
            val data = args[1] as Parcel
            val reply = args[2] as Parcel
            val flags = args[3] as Int

            if (code == -1) return@beforeHook

            when (code) {
                TRANSACTION_isActiveSubId -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        reply.writeBoolean(true)
                    } else {
                        reply.writeInt(1)
                    }
                    result = true
                }
                TRANSACTION_getSimStateForSlotIndex -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    result = true
                }
                TRANSACTION_getSimStateForSubscriber -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    result = true
                }
                TRANSACTION_getSimStateForSlotIdx -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    result = true
                }
//                TRANSACTION_getActiveSubInfoCount -> {
//                    data.enforceInterface(subClassName)
//                    data.readString()
//                    reply.writeNoException()
//                    reply.writeInt(1)
//                    result = true
//                }
//                TRANSACTION_getActiveSubscriptionInfoList -> {
//                    data.enforceInterface(subClassName)
//                    data.readString()
//                    reply.writeNoException()
//                    reply.writeTypedList(arrayListOf())
//                    result = true
//                }
//                TRANSACTION_getActiveSubInfoCountMax -> {
//                    data.enforceInterface(subClassName)
//                    reply.writeNoException()
//                    reply.writeInt(1)
//                    result = true
//                }
                TRANSACTION_getNetworkCountryIsoForPhone -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeString("CHN")
                    result = true
                }
                TRANSACTION_getPhoneId -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(0)
                    result = true
                }
            }
        }
        if(cISub.hookAllMethods("onTransact", hookOnTransact).isEmpty()) {
            Logger.error("Hook ISub.Stub.onTransact failed")
        }
    }

//    private fun hookOnTransactForServiceInstance(m: Method) {
//        var hook: XC_MethodHook.Unhook? = null
//        hook = XposedBridge.hookMethod(m, object : XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                if (param == null) return
//
//                val thisObject = param.thisObject
//
//                if (hook == null || thisObject == null) return
//                onFetchServiceInstance(thisObject)
//                hook?.unhook()
//                hook = null
//            }
//        })
//    }

//    private fun onFetchServiceInstance(thisObject: Any) {
//        val cITelephony = thisObject.javaClass
//
//        println("[PTL] found " + cITelephony.declaredMethods.mapNotNull {
//            if (it.returnType.javaClass.name.contains("CellLocation")) {
//                if (FakeLocationConfig.DEBUG) {
//                    XposedBridge.log("[PTL] hook method: $it")
//                }
//                XposedBridge.hookMethod(it, hookGetCellLocation)
//            } else null
//        }.size + " methods(CellLocation) to hook in ITelephony\$Stub")
//
//        XposedBridge.hookAllMethods(cITelephony, "getNeighboringCellInfo", hookGetNeighboringCellInfoList)
//    }
}
