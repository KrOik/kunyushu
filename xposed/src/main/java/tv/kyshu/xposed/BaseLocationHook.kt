package tv.kyshu.xposed

import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import tv.kyshu.xposed.factory.LocationFactory
import tv.kyshu.xposed.factory.NmeaSentenceFactory
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.CoordinateTransforms
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.stability.DomainGuard
import java.util.concurrent.ConcurrentHashMap

abstract class BaseLocationHook: BaseDivineService() {
    private companion object {
        val diagnosticKeys = ConcurrentHashMap.newKeySet<String>()
        // Location(Location) calls the hooked Location.set(). Shared across hook
        // instances, but never across threads: nested copies must pass through.
        val injectionInProgress = ThreadLocal<Boolean>()

        fun logOnce(key: String, message: String) {
            if (diagnosticKeys.add(key)) {
                Logger.info(message)
            }
        }
    }

    fun injectLocation(originLocation: Location, realLocation: Boolean = true): Location {
        if (injectionInProgress.get() == true ||
            DomainGuard.isDomainDisabled(DomainGuard.Domain.LOCATION_INJECTION)) {
            return originLocation
        }
        // Kuroko per-UID routing: once a UID policy table has been pushed (epoch > 0),
        // callers in system_server that are not targeted with POLICY_LOCATION always
        // receive untouched real locations. Binder.getCallingUid() is only meaningful
        // inside onTransact-style entry points; listener transports inside
        // system_server run with the system UID, so location hooks resolve policy at
        // the registration boundary (LocationManagerHook) and cache per-uid here.
        if (tv.kyshu.xposed.config.UidPolicy.epochValue() > 0 &&
            !tv.kyshu.xposed.config.UidPolicy.callerHasPolicy(tv.kyshu.xposed.config.UidPolicy.POLICY_LOCATION) &&
            tv.kyshu.xposed.config.UidPolicy.targetCount() > 0 &&
            Binder.getCallingUid() != android.os.Process.myUid() &&
            !tv.kyshu.xposed.config.UidPolicy.isTargetUid(Binder.getCallingUid())
        ) {
            return originLocation
        }
        injectionInProgress.set(true)
        return try {
            buildInjectedLocation(originLocation, realLocation)
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            // This also covers BlindHook callbacks, whose exceptions previously escaped
            // to LSPosed without disabling the failing injection path.
            DomainGuard.tripNow(DomainGuard.Domain.LOCATION_INJECTION, "injectLocation", error)
            originLocation
        } finally {
            injectionInProgress.remove()
        }
    }

    private fun buildInjectedLocation(originLocation: Location, realLocation: Boolean): Location {
        if (realLocation) {
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    originLocation.provider == LocationManager.GPS_PROVIDER && originLocation.isComplete
                } else {
                    originLocation.provider == LocationManager.GPS_PROVIDER
                }
            ) {
                FakeLoc.lastLocation = originLocation
            }
        }

        if (!FakeLoc.enable) {
            logOnce("injectLocation.disabled.${originLocation.provider}",
                "[BaseLocationHook] injectLocation skipped: FakeLoc.enable=false provider=${originLocation.provider}")
            return originLocation
        }

        if (LocationFactory.isInjected(originLocation)) {
            logOnce("injectLocation.already.${originLocation.provider}",
                "[BaseLocationHook] injectLocation skipped: already injected provider=${originLocation.provider}")
            return originLocation
        }

        val location = LocationFactory.createInjectedLocation(originLocation)
        if (FakeLoc.disableNetworkLocation && originLocation.provider == LocationManager.NETWORK_PROVIDER) {
            logOnce("injectLocation.network.rewrite",
                "[BaseLocationHook] network provider rewritten to gps: disableNetworkLocation=${FakeLoc.disableNetworkLocation}")
            location.provider = LocationManager.GPS_PROVIDER
        }

        val outputCoordinates = CoordinateTransforms.forCurrentProcess(location.latitude, location.longitude)
        location.latitude = outputCoordinates.first
        location.longitude = outputCoordinates.second

        if (FakeLoc.enableDebugLog) {
            Logger.debug("injectLocation success! lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}")
        }
        logOnce("injectLocation.success.${location.provider}",
            "[BaseLocationHook] injectLocation first success: provider=${originLocation.provider}->${location.provider}, mock=(${location.latitude},${location.longitude}), hideMock=${FakeLoc.hideMock}, acc=${location.accuracy}")

        return location
    }

    open fun injectNMEA(nmeaStr: String): String? {
        if (!FakeLoc.enable) {
            return null
        }
        return NmeaSentenceFactory.rewrite(nmeaStr)
    }
}
