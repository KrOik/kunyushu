package tv.kyshu.xposed

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import tv.kyshu.xposed.security.RemoteCommandSecurity
import tv.kyshu.xposed.utils.BinderUtils
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.ChannelIdentity
import tv.kyshu.xposed.utils.Logger
import java.util.concurrent.ConcurrentHashMap

abstract class BaseDivineService {
    private var commandSequence: Long = 0L

    companion object {
        private const val DEFERRED_INIT_ATTEMPTS = 60
        private const val DEFERRED_INIT_INTERVAL_MS = 1_000L

        private val initializedServices = ConcurrentHashMap.newKeySet<String>()
        private val pendingServices = ConcurrentHashMap.newKeySet<String>()
    }

    protected fun ensureDivineService(from: String): Boolean {
        if (FakeLoc.isSystemServerProcess) {
            return true
        }
        if (initializedServices.contains(from)) {
            return true
        }
        if (initDivineService(from, logFailures = false)) {
            initializedServices.add(from)
            return true
        }
        scheduleDeferredInit(from)
        return false
    }

    private fun scheduleDeferredInit(from: String) {
        if (!pendingServices.add(from)) {
            return
        }
        Thread({
            try {
                repeat(DEFERRED_INIT_ATTEMPTS) { attempt ->
                    try {
                        Thread.sleep(DEFERRED_INIT_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                    if (initDivineService(from, logFailures = false)) {
                        initializedServices.add(from)
                        Logger.info("[BaseDivineService] Deferred init succeeded in $from after ${attempt + 1}s")
                        return@Thread
                    }
                }
                Logger.warn("[BaseDivineService] Deferred init unavailable in $from after ${DEFERRED_INIT_ATTEMPTS}s")
            } finally {
                pendingServices.remove(from)
            }
        }, "Svc-Divine-$from").apply { isDaemon = true }.start()
    }

    /**
     * if the hook is TelephonyService? or other service?
     * this service may not be in the same space as the `system_server`,
     * so a binder is used to talk.
     */
    protected fun initDivineService(from: String, retryCount: Int = 0, logFailures: Boolean = true): Boolean {
        if (FakeLoc.isSystemServerProcess) {
            initializedServices.add(from)
            return true
        }

        fun tryFetchLocationManager(): LocationManager? {
            var locationManager = BinderUtils.getSystemContext()?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            var count = 0
            while (locationManager == null) {
                Thread.sleep(1000)
                if (count++ > 10) {
                    break
                }
                locationManager = BinderUtils.getSystemContext()?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            }
            return locationManager
        }

        val locationManager = tryFetchLocationManager()
        if (locationManager == null) {
            if (logFailures) {
                Logger.warn("LocationManager not found in $from")
            }
            return false
        }

        if (!locationManager.isProviderEnabled(ChannelIdentity.providerName)) {
            if (retryCount > 10) {
                if (logFailures) {
                    Logger.warn("Location provider ${ChannelIdentity.providerName} not ready in $from")
                }
                return false
            }
            Thread.sleep(100)
            return initDivineService(from, retryCount + 1, logFailures)
        }

        val randomKey = exchangeSessionKey(locationManager)
        if (randomKey.isNullOrEmpty()){
            if (logFailures) {
                Logger.warn("Failed to init service in $from")
            }
            return false
        }

        syncConfig(locationManager, randomKey)

        val proxyBinder = object: Binder() {
            override fun getInterfaceDescriptor(): String {
                return "tv.kyshu.service.${from}Helper"
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) {
                    val sessionKey = data.readString() ?: randomKey
                    val bundle = data.readBundle(javaClass.classLoader)!!
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("ProxyBinder($from): $bundle")
                    }
                    if(!RemoteCommandHandler.handleSessionInstruction(sessionKey, bundle)) {
                        Logger.error("Failed to handle instruction in $from")
                    }
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
        val (proxyOk, _) = sendAuthenticated(locationManager, randomKey, "set_proxy") {
            putBinder("proxy", proxyBinder)
        }
        if (!proxyOk) {
            if (logFailures) {
                Logger.warn("Failed to init service proxy in $from")
            }
            return false
        }
        initializedServices.add(from)
        return true
    }

    private fun exchangeSessionKey(locationManager: LocationManager): String? {
        val rely = Bundle()
        if (!locationManager.sendExtraCommand(ChannelIdentity.providerName, "exchange_key", rely)) {
            return null
        }
        val sessionKey = RemoteCommandSecurity.decryptSessionKey(rely) ?: return null
        RemoteCommandHandler.registerSessionKey(sessionKey)
        return sessionKey
    }

    @Synchronized
    private fun nextCommandSequence(): Long {
        commandSequence += 1
        return commandSequence
    }

    private fun sendAuthenticated(
        locationManager: LocationManager,
        randomKey: String,
        commandId: String,
        bundleBuilder: Bundle.() -> Unit = {},
    ): Pair<Boolean, Bundle> {
        val rely = Bundle().apply {
            putString("command_id", commandId)
            bundleBuilder()
        }
        if (!RemoteCommandSecurity.attachEnvelope(rely, randomKey, nextCommandSequence())) {
            Logger.error("Failed to attach auth envelope for DivineService command=$commandId")
            return false to rely
        }
        if (!locationManager.sendExtraCommand(ChannelIdentity.providerName, randomKey, rely)) {
            return false to rely
        }
        if (!RemoteCommandSecurity.decryptSensitivePayload(rely, randomKey)) {
            Logger.error("Failed to decrypt DivineService response for command=$commandId")
            return false to rely
        }
        return true to rely
    }

    /**
     * Synchronize configurations in different processes
     */
    private fun syncConfig(locationManager: LocationManager, randomKey: String) {
        val (ok, rely) = sendAuthenticated(locationManager, randomKey, "sync_config")
        if(ok) {
            FakeLoc.enable = rely.getBoolean("enable", FakeLoc.enable)
            FakeLoc.latitude = rely.getDouble("latitude", FakeLoc.latitude)
            FakeLoc.longitude = rely.getDouble("longitude", FakeLoc.longitude)
            FakeLoc.altitude = rely.getDouble("altitude", FakeLoc.altitude)
            FakeLoc.speed = rely.getDouble("speed", FakeLoc.speed)
            FakeLoc.speedAmplitude = rely.getDouble("speed_amplitude", FakeLoc.speedAmplitude)
            FakeLoc.hasBearings = rely.getBoolean("has_bearings", FakeLoc.hasBearings)
            FakeLoc.bearing = rely.getDouble("bearing", FakeLoc.bearing)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                FakeLoc.lastLocation = rely.getParcelable("last_location", Location::class.java)
            } else {
                FakeLoc.lastLocation = rely.getParcelable("last_location")
            }
            FakeLoc.enableLog = rely.getBoolean("enable_log", FakeLoc.enableLog)
            FakeLoc.enableDebugLog = rely.getBoolean("enable_debug_log", FakeLoc.enableDebugLog)
            FakeLoc.disableGetCurrentLocation = rely.getBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
            FakeLoc.disableRegisterLocationListener = rely.getBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
            FakeLoc.disableFusedLocation = rely.getBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
            FakeLoc.enableAGPS = rely.getBoolean("enable_agps", FakeLoc.enableAGPS)
            FakeLoc.enableNMEA = rely.getBoolean("enable_nmea", FakeLoc.enableNMEA)
            FakeLoc.hideMock = rely.getBoolean("hide_mock", FakeLoc.hideMock)
            FakeLoc.hookWifi = rely.getBoolean("hook_wifi", FakeLoc.hookWifi)
            FakeLoc.needDowngradeToCdma = rely.getBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
            Logger.debug("Synced config for DivineService")
        } else {
            Logger.error("Failed to sync config for DivineService")
        }
    }
}
