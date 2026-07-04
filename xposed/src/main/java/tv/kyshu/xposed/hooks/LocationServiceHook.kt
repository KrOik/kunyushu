@file:Suppress("KotlinConstantConditions")
@file:OptIn(ExperimentalUuidApi::class)

package tv.kyshu.xposed.hooks

import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.BaseLocationHook
import tv.kyshu.xposed.RemoteCommandHandler
import tv.kyshu.xposed.hooks.gnss.GnssHook
import tv.kyshu.xposed.hooks.miui.MiuiBlurLocationProviderHook
import tv.kyshu.xposed.hooks.miui.MiuiLocationManagerHook
import tv.kyshu.xposed.hooks.telephony.miui.MiuiTelephonyManagerHook
import tv.kyshu.xposed.hooks.nmea.LocationNMEAHook
import tv.kyshu.xposed.hooks.provider.LocationProviderManagerHook
import tv.kyshu.xposed.stability.DomainGuard
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.GnssFlags
import tv.kyshu.xposed.utils.BinderUtils
import tv.kyshu.xposed.utils.ChannelIdentity
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.afterHook
import tv.kyshu.xposed.utils.beforeHook
import tv.kyshu.xposed.utils.hookAllMethods
import tv.kyshu.xposed.utils.onceHook
import tv.kyshu.xposed.utils.onceHookAllMethod
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import tv.kyshu.xposed.utils.GnssSatellite
import tv.kyshu.xposed.utils.OrbitType
import tv.kyshu.xposed.utils.satelliteList as multiConstellationSatellites
import tv.kyshu.xposed.gnss.Ephemeris
import tv.kyshu.xposed.gnss.GnssMath
import tv.kyshu.xposed.gnss.GnssType
import tv.kyshu.xposed.gnss.RinexNavigationParser
import java.io.File
import android.os.SystemClock

private const val MAX_SATELLITES = 56 // BDS 35 + GPS 32 + GLONASS 24 + others

// GPS frequencies
private const val GPS_L1_FREQ = 1575.420f // MHz
private const val GPS_L5_FREQ = 1176.450f

// 北斗频率
private const val BDS_B1I_FREQ = 1561.098f
private const val BDS_B1C_FREQ = 1575.420f
private const val BDS_B2a_FREQ = 1176.450f
private const val BDS_B2I_FREQ = 1207.140f
private const val BDS_B3I_FREQ = 1268.520f

// GLONASS 频率
private const val GLONASS_G1_FREQ = 1602.000f
private const val GLONASS_G2_FREQ = 1246.000f

// GALILEO 频率
private const val GALILEO_E1_FREQ = 1575.420f
private const val GALILEO_E5a_FREQ = 1176.450f
private const val GALILEO_E5b_FREQ = 1207.140f

// QZSS 频率
private const val QZSS_L1_FREQ = 1575.420f
private const val QZSS_L5_FREQ = 1176.450f

data class MockGnssData(
    val svCount: Int,
    val svidWithFlags: IntArray,
    val cn0s: FloatArray,
    val elevations: FloatArray,
    val azimuths: FloatArray,
    val carrierFreqs: FloatArray
)

internal object LocationServiceHook: BaseLocationHook() {

    val locationListeners = LinkedBlockingQueue<Triple<String, IInterface, IBinder.DeathRecipient>>()
    private val gnssStatusCallbackMethods = setOf(
        "onSvStatusChanged",
        "onSatelliteStatusChanged",
        "onGnssStatusChanged",
    )
    private val loggedGnssStatusCallbackMisses = ConcurrentHashMap.newKeySet<String>()

    // ==================== 卫星星图状态 ====================

    data class SatelliteState(
        val satellite: tv.kyshu.xposed.utils.GnssSatellite,
        var azimuth: Float,
        var elevation: Float,
        var cn0: Float,
        var carrierFreq: Float,
        var hasEphemeris: Boolean,
        var hasAlmanac: Boolean,
        var usedInFix: Boolean
    )

    val satelliteStates = ConcurrentHashMap<String, SatelliteState>()
    private var ephemerisMap = ConcurrentHashMap<String, Ephemeris>()
    private var lastRinexLoadTime = 0L
    private var lastGnssUpdateElapsed = 0L
    private var lastCalcLocation: Pair<Double, Double>? = null
    private var lastMockGnssData: MockGnssData? = null
    private var lastMockGnssDataTime = 0L
    private var lastSatelliteCalcTime = 0L
    private var lastSatelliteDiagTime = 0L
    private val loggedSatelliteCalcErrors = ConcurrentHashMap.newKeySet<String>()

    internal fun findGnssStatusCallback(args: Array<Any?>): Any? {
        return args.filterNotNull().firstOrNull {
            hasHookableGnssStatusCallbackMethod(it.javaClass)
        } ?: args.filterIsInstance<IInterface>().firstOrNull()
    }

    internal fun hasHookableGnssStatusCallbackMethod(type: Class<*>): Boolean {
        return type.concreteClassHierarchy().any { clazz ->
            clazz.declaredMethods.any(::isHookableGnssStatusMethod)
        }
    }

    private fun isHookableGnssStatusMethod(method: Method): Boolean {
        return method.name in gnssStatusCallbackMethods &&
                !Modifier.isAbstract(method.modifiers)
    }

    private fun Class<*>.concreteClassHierarchy(): Sequence<Class<*>> = sequence {
        var clazz: Class<*>? = this@concreteClassHierarchy
        while (clazz != null && clazz != Any::class.java) {
            if (!clazz.isInterface) yield(clazz)
            clazz = clazz.superclass
        }
    }

    private fun hookGnssStatusCallbackMethods(callback: Any, hook: XC_MethodHook): Int {
        var hooked = 0
        val seen = mutableSetOf<String>()
        callback.javaClass.concreteClassHierarchy().forEach { clazz ->
            clazz.declaredMethods
                .filter(::isHookableGnssStatusMethod)
                .forEach { method ->
                    val key = "${method.declaringClass.name}#${method.name}(${method.parameterTypes.joinToString(",") { it.name }})"
                    if (!seen.add(key)) return@forEach
                    runCatching {
                        method.isAccessible = true
                        method.onceHook(hook)
                    }.onSuccess { unhook ->
                        if (unhook != null) hooked++
                    }.onFailure {
                        Logger.warn("[LocationServiceHook] GNSS status callback hook failed: $key", it)
                    }
                }
        }
        return hooked
    }

    private fun logMissingGnssStatusCallback(callback: Any) {
        val type = callback.javaClass
        if (!loggedGnssStatusCallbackMisses.add(type.name)) return
        val methodSummary = type.concreteClassHierarchy()
            .flatMap { it.declaredMethods.asSequence() }
            .map { it.name }
            .distinct()
            .take(16)
            .joinToString(",")
        Logger.warn("[LocationServiceHook] GNSS status callback method not found: ${type.name}; methods=$methodSummary")
    }

    // ==================== 卫星星图位置结算 ====================

    private fun getSatKey(constellation: Int, prn: Int) = "${constellation}_${prn}"

    fun getOrCreateState(sat: tv.kyshu.xposed.utils.GnssSatellite): SatelliteState {
        return satelliteStates.getOrPut(getSatKey(sat.constellation, sat.prn)) {
            val elevation = Random.nextFloat(sat.type.elevationRange.start, sat.type.elevationRange.endInclusive)
            val cn0 = Random.nextFloat(sat.type.minCn0, sat.type.maxCn0)

            val carrierFreq = when (sat.constellation) {
                tv.kyshu.xposed.utils.GnssFlags.CONSTELLATION_GPS -> {
                    if (Random.nextBoolean()) GPS_L1_FREQ else GPS_L5_FREQ
                }
                tv.kyshu.xposed.utils.GnssFlags.CONSTELLATION_GLONASS -> {
                    if (Random.nextBoolean()) GLONASS_G1_FREQ else GLONASS_G2_FREQ
                }
                tv.kyshu.xposed.utils.GnssFlags.CONSTELLATION_GALILEO -> {
                    when (Random.nextInt(3)) {
                        0 -> GALILEO_E1_FREQ
                        1 -> GALILEO_E5a_FREQ
                        else -> GALILEO_E5b_FREQ
                    }
                }
                tv.kyshu.xposed.utils.GnssFlags.CONSTELLATION_QZSS -> {
                    if (Random.nextBoolean()) QZSS_L1_FREQ else QZSS_L5_FREQ
                }
                else -> { // BDS and others
                    when (Random.nextInt(3)) {
                        0 -> BDS_B1I_FREQ
                        1 -> BDS_B2I_FREQ
                        else -> BDS_B3I_FREQ
                    }
                }
            }

            SatelliteState(
                satellite = sat,
                azimuth = Random.nextFloat(0f, 360f),
                elevation = elevation,
                cn0 = cn0,
                carrierFreq = carrierFreq,
                hasEphemeris = false,
                hasAlmanac = false,
                usedInFix = elevation >= 10f && cn0 >= 28f
            )
        }
    }

    private fun ensureEphemerisLoaded() {
        val path = FakeLoc.rinexPath ?: run {
            if (loggedSatelliteCalcErrors.add("rinex.missing")) {
                Logger.info("[LocationServiceHook] RINEX path empty; GNSS star map uses deterministic fallback satellites")
            }
            return
        }
        val file = File(path)
        if (!file.exists()) {
            if (loggedSatelliteCalcErrors.add("rinex.notfound.$path")) {
                Logger.warn("[LocationServiceHook] RINEX file not found: $path; using fallback satellites")
            }
            return
        }

        if (ephemerisMap.isEmpty() || file.lastModified() > lastRinexLoadTime) {
            lastRinexLoadTime = System.currentTimeMillis()
            try {
                Logger.info("[LocationServiceHook] Loading RINEX file from $path")
                val list = RinexNavigationParser().parse(file)
                ephemerisMap.clear()
                list.forEach { eph ->
                    val constellation = when(eph.type) {
                        GnssType.GPS -> tv.kyshu.xposed.utils.GnssFlags.CONSTELLATION_GPS
                        GnssType.BDS -> tv.kyshu.xposed.utils.GnssFlags.CONSTELLATION_BEIDOU
                        GnssType.GALILEO -> tv.kyshu.xposed.utils.GnssFlags.CONSTELLATION_GALILEO
                        GnssType.QZSS -> tv.kyshu.xposed.utils.GnssFlags.CONSTELLATION_QZSS
                        GnssType.GLONASS -> tv.kyshu.xposed.utils.GnssFlags.CONSTELLATION_GLONASS
                        else -> 0
                    }
                    if (constellation != 0) {
                        ephemerisMap[getSatKey(constellation, eph.prn)] = eph
                    }
                }
                val maxToeWeek = list.maxOfOrNull { it.week } ?: -1
                Logger.info("[LocationServiceHook] Loaded ${ephemerisMap.size} ephemeris records, max GPS week=$maxToeWeek")

                val gpsEpochMs = 315964800000L
                val nowGpsSec = (System.currentTimeMillis() - gpsEpochMs) / 1000.0
                val currentWeek = (nowGpsSec / 604800.0).toInt()
                val weekDiff = currentWeek - maxToeWeek
                if (weekDiff >= 2) {
                    Logger.warn("[LocationServiceHook] Ephemeris data is $weekDiff week(s) old -- satellite positions may be inaccurate")
                }
            } catch (e: Exception) {
                Logger.error("[LocationServiceHook] Failed to load RINEX file", e)
            }
        }
    }

    fun clearSatelliteStates() {
        satelliteStates.clear()
        ephemerisMap.clear()
        lastRinexLoadTime = 0L
        lastCalcLocation = null
        lastMockGnssData = null
        lastMockGnssDataTime = 0L
        lastSatelliteCalcTime = 0L
    }

    private fun selectSatellitesForMock(ephemerisSatellites: List<tv.kyshu.xposed.utils.GnssSatellite>): List<tv.kyshu.xposed.utils.GnssSatellite> {
        val minSatellites = normalizedMinSatellites()

        // 有星历卫星：优先返回（固定顺序，非随机）
        if (ephemerisSatellites.isNotEmpty()) {
            if (ephemerisSatellites.size >= minSatellites) {
                return ephemerisSatellites.take(MAX_SATELLITES)
            }
            // 不足时按固定顺序补充无星历卫星，保持星历卫星在前
            val supplement = multiConstellationSatellites.filterNot { it in ephemerisSatellites }
            return (ephemerisSatellites + supplement).take(MAX_SATELLITES)
        }

        // 完全无星历时：返回全量卫星列表（确定性顺序，非随机子集）
        // 卫星位置由 updateSatelliteStates 用随机漂移计算
        return multiConstellationSatellites.take(MAX_SATELLITES)
    }

    fun forceUpdateSatelliteStates() {
        lastSatelliteCalcTime = 0L
        updateSatelliteStates(1000f, force = true)
    }

    private fun updateSatelliteStates(deltaSeconds: Float, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val calcCooldownMs = if (ephemerisMap.isNotEmpty()) 3000L else 1500L
        if (!force && now - lastSatelliteCalcTime < calcCooldownMs) {
            // 功耗管理：在冷却时间内仅更新 CN0 抖动，不做完整结算
            val atmosphereJitter = FakeLoc.enableAtmosphereJitter
            val jitterIntensity = FakeLoc.atmosphereJitterIntensity
            if (atmosphereJitter && jitterIntensity > 0f) {
                multiConstellationSatellites.forEach { sat ->
                    val state = satelliteStates[getSatKey(sat.constellation, sat.prn)] ?: return@forEach
                    val elRatio = (state.elevation / 90f).coerceIn(0f, 1f)
                    val absorption = (1f - elRatio) * jitterIntensity * Random.nextFloat(0.1f, 0.5f)
                    val scintillation = Random.nextFloat(-1f, 1f) * jitterIntensity
                    state.cn0 = clamp(
                        state.cn0 + scintillation - absorption * 1.5f,
                        sat.type.minCn0,
                        sat.type.maxCn0
                    )
                }
            }
            return
        }
        lastSatelliteCalcTime = now

        ensureEphemerisLoaded()

        val currentLocation = Pair(FakeLoc.latitude, FakeLoc.longitude)
        val lastLoc = lastCalcLocation
        if (lastLoc != null) {
            val dist = tv.kyshu.xposed.utils.FakeLoc.haversine(lastLoc.first, lastLoc.second, currentLocation.first, currentLocation.second)
            if (dist > 1000.0) {
                satelliteStates.clear()
            }
        }
        lastCalcLocation = currentLocation

        val gpsZeroMillis = 315964800000L
        val millisInWeek = 604800000L
        val timeSinceGpsEpoch = System.currentTimeMillis() - gpsZeroMillis + 18000
        val tow = (timeSinceGpsEpoch % millisInWeek) / 1000.0

        val atmosphereJitter = FakeLoc.enableAtmosphereJitter
        val jitterIntensity = FakeLoc.atmosphereJitterIntensity

        multiConstellationSatellites.forEach { sat ->
            val state = getOrCreateState(sat)
            val eph = ephemerisMap[getSatKey(sat.constellation, sat.prn)]

            if (eph != null) {
                try {
                    val pos = GnssMath.calculateSatellitePosition(eph, tow)
                    val topo = GnssMath.ecefToTopocentric(pos, FakeLoc.latitude, FakeLoc.longitude, FakeLoc.altitude)

                    state.azimuth = topo.azimuth.toFloat()
                    state.elevation = topo.elevation.toFloat()

                    val ratio = (state.elevation / 90f).coerceIn(0f, 1f)
                    var baseCn0 = sat.type.minCn0 + (sat.type.maxCn0 - sat.type.minCn0) * ratio

                    if (atmosphereJitter && jitterIntensity > 0f) {
                        val absorption = (1f - ratio) * jitterIntensity * Random.nextFloat(0.2f, 0.8f)
                        val scintillation = Random.nextFloat(-2f, 2f) * jitterIntensity
                        baseCn0 -= absorption * 3f
                        baseCn0 += scintillation
                    }

                    state.cn0 = (baseCn0 + Random.nextFloat(-1f, 1f)).coerceIn(sat.type.minCn0, sat.type.maxCn0)

                    state.hasEphemeris = true
                    state.hasAlmanac = true
                    state.usedInFix = state.elevation > 10 && state.cn0 > 25
                } catch (e: Exception) {
                    state.azimuth = (state.azimuth + Random.nextFloat(-2f, 2f) * deltaSeconds + 360f) % 360f
                    state.hasEphemeris = false
                    state.hasAlmanac = true
                    state.usedInFix = false
                    val key = "calc.${sat.constellation}.${sat.prn}"
                    if (loggedSatelliteCalcErrors.add(key)) {
                        Logger.warn("[LocationServiceHook] Satellite position calc failed: constellation=${sat.constellation} prn=${sat.prn}", e)
                    }
                }
            } else {
                // Legacy random drift (无星历时使用)
                state.azimuth = (state.azimuth + Random.nextFloat(-2f, 2f) * deltaSeconds + 360f) % 360f
                state.elevation = clamp(
                    state.elevation + Random.nextFloat(-1.2f, 1.2f) * deltaSeconds,
                    sat.type.elevationRange.start,
                    sat.type.elevationRange.endInclusive
                )
                var driftCn0 = state.cn0 + Random.nextFloat(-1.5f, 1.5f) * deltaSeconds

                if (atmosphereJitter && jitterIntensity > 0f) {
                    val elRatio = (state.elevation / 90f).coerceIn(0f, 1f)
                    val absorption = (1f - elRatio) * jitterIntensity * Random.nextFloat(0.1f, 0.5f)
                    driftCn0 -= absorption * 3f
                }

                state.cn0 = clamp(driftCn0, sat.type.minCn0, sat.type.maxCn0)
                state.hasEphemeris = false
                state.hasAlmanac = false
                state.usedInFix = state.hasEphemeris && state.elevation >= 15f && state.cn0 >= 30f
            }
        }

        val usedSatellites = multiConstellationSatellites.map { getOrCreateState(it) }
            .filter { it.usedInFix }
            .sortedWith(Comparator { a, b ->
                val ca = a.cn0
                val cb = b.cn0
                when {
                    ca.isNaN() && cb.isNaN() -> 0
                    ca.isNaN() -> -1   // NaN treated as smallest
                    cb.isNaN() -> 1
                    else -> cb.compareTo(ca)  // descending
                }
            })

        if (usedSatellites.size > 24) {
            usedSatellites.drop(24).forEach { it.usedInFix = false }
        }
        val diagNow = SystemClock.elapsedRealtime()
        if (force || diagNow - lastSatelliteDiagTime > 5_000L) {
            lastSatelliteDiagTime = diagNow
            val usedAfterCap = multiConstellationSatellites.map { getOrCreateState(it) }.count { it.usedInFix }
            Logger.info(
                "[LocationServiceHook] GNSS calc summary: enableMockGnss=${FakeLoc.enableMockGnss}, " +
                    "rinex=${FakeLoc.rinexPath ?: "(none)"}, ephemeris=${ephemerisMap.size}, " +
                    "satellites=${satelliteStates.size}, used=$usedAfterCap, min=${normalizedMinSatellites()}"
            )
        }
    }

    private fun normalizedMinSatellites(): Int {
        val raw = FakeLoc.minSatellites
        return when {
            raw <= 0 -> 12
            raw > 35 -> 35
            else -> raw
        }
    }

    // ==================== 操作入口 ====================

    // A random command is generated to reduce static detection of the control channel.
    operator fun invoke(classLoader: ClassLoader) {
        if (DomainGuard.isDomainDisabled(DomainGuard.Domain.LOCATION_SERVICE)) {
            Logger.warn("[LocationServiceHook] Domain LOCATION_SERVICE is disabled, skipping hook installation")
            return
        }
        val cLocationManagerService = XposedHelpers.findClassIfExists("com.android.server.location.LocationManagerService", classLoader)
        if (cLocationManagerService == null) {
            hookLocationManagerServiceV2(classLoader)
        } else {
            onService(cLocationManagerService)
        }
        //startDaemon(classLoader)
    }

    fun onService(cILocationManager: Class<*>) {
        // Got instance of ILocationManager.Stub here, you can hook it
        // Not directly Class.forName because of this thing, it can't be reflected, even if I'm system_server?!?!

        if (FakeLoc.enableDebugLog) {
            Logger.debug("ILocationManager.Stub: class = $cILocationManager")
        }

        cILocationManager.classLoader!!.let {
            BasicLocationHook(it)
            GnssHook(it)
            LocationProviderManagerHook(it)

            MiuiBlurLocationProviderHook(it)
            MiuiLocationManagerHook(it)
            MiuiTelephonyManagerHook(it)
        }

        LocationNMEAHook(cILocationManager)

        if(cILocationManager.hookAllMethods("getLastLocation", afterHook {
                // android 7.0.0 ~ 10.0.0
                // Location getLastLocation(in LocationRequest request, String packageName);
                // android 11.0.0
                // Location getLastLocation(in LocationRequest request, String packageName, String featureId);
                // android 12.0.0 ~ 15.0.0
                // @nullable Location getLastLocation(String provider, in LastLocationRequest request, String packageName, @nullable String attributionTag);
                // Why are there so... I'm really speechless

                // Virtual Coordinate: Instantly update the latest virtual coordinates
                // Roulette Move: Each request moves a certain distance
                // Route Simulation: Move according to a preset route
                //val uid = FqlUtils.getCallerUid()
                // Determine whether it is an app that needs a hook
                if (!FakeLoc.enable) return@afterHook

                // It can't be null, because I'm judging in the previous step
                val location = result as? Location ?: Location("gps")

                result = injectLocation(location)

                if(FakeLoc.enableDebugLog) {
                    Logger.debug("getLastLocation: injected! lat=${location.latitude}, lon=${location.longitude}")
                }
        }).isEmpty()) {
            Logger.error("hook getLastLocation failed")
        }

        // android 12 and later remove `requestLocationUpdates`
        cILocationManager.hookAllMethods("requestLocationUpdates", beforeHook {
            // android 7.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener, String packageName);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in Location location);
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //    void onProviderEnabled(String provider);
            //    void onProviderDisabled(String provider);
            //}
            //
            // android 7.1.1 ~ 9.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener,
            //            in PendingIntent intent, String packageName);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in Location location);
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //    void onProviderEnabled(String provider);
            //    void onProviderDisabled(String provider);
            //
            // android 10.0.0
            // oneway interface ILocationListener
            //{
            //    @UnsupportedAppUsage
            //    void onLocationChanged(in Location location);
            //    @UnsupportedAppUsage
            //    void onProviderEnabled(String provider);
            //    @UnsupportedAppUsage
            //    void onProviderDisabled(String provider);
            //    // --- deprecated ---
            //    @UnsupportedAppUsage
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //}
            //
            // android 11.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener,
            //            in PendingIntent intent, String packageName, String featureId, String listenerId);
            //
            // oneway interface ILocationListener
            //{
            //    @UnsupportedAppUsage
            //    void onLocationChanged(in Location location);
            //    @UnsupportedAppUsage
            //    void onProviderEnabled(String provider);
            //    @UnsupportedAppUsage
            //    void onProviderDisabled(String provider);
            //    // called when the listener is removed from the server side; no further callbacks are expected
            //    void onRemoved();
            //}
            // android 12 and later
            // remove this method
            val provider = kotlin.runCatching {
                XposedHelpers.callMethod(args[0], "getProvider") as? String
            }.onFailure { Logger.debug("[Reflect] getProvider callMethod failed", it) }.getOrNull() ?: "gps"

            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("requestLocationUpdates: listener is null: $method")
                return@beforeHook
            }

            if(FakeLoc.enableDebugLog) {
                Logger.debug("requestLocationUpdates: injected! $listener")
            }

            addLocationListenerInner(provider, listener)

            if (FakeLoc.disableRegisterLocationListener || FakeLoc.enable) {
                result = null
                return@beforeHook
            }

            if (FakeLoc.disableFusedLocation && provider == "fused") {
                result = null
                return@beforeHook
            }
        })
        cILocationManager.hookAllMethods("removeUpdates", afterHook {
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("removeUpdates: listener is null: $method")
                return@afterHook
            }
            if(FakeLoc.enableDebugLog) {
                Logger.debug("removeUpdates: injected! $listener")
            }

            removeLocationListenerInner(listener)
        })
        cILocationManager.hookAllMethods("registerLocationListener", beforeHook {
            // android 12 ~ android 15
            // void registerLocationListener(String provider, in LocationRequest request, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in List<Location> locations, in @nullable IRemoteCallback onCompleteCallback);
            //    void onProviderEnabledChanged(String provider, boolean enabled);
            //    void onFlushComplete(int requestCode);
            //}
            val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                kotlin.runCatching {
                    XposedHelpers.callMethod(args[1], "getProvider") as? String
                }.onFailure { Logger.debug("[Reflect] getProvider(API S+) callMethod failed", it) }.getOrNull()
            } else {
                args[0] as? String
            } ?: "gps"
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("registerLocationListener: listener is null: $method")
                return@beforeHook
            }

            if(FakeLoc.enableDebugLog) {
                Logger.debug("registerLocationListener: injected! $listener, from ${BinderUtils.getUidPackageNames()}")
            }

            addLocationListenerInner(provider, listener)

            if (FakeLoc.disableRegisterLocationListener) {
                result = null
                return@beforeHook
            }

            if (FakeLoc.disableFusedLocation && provider == "fused") {
                result = null
                return@beforeHook
            }
        })
        cILocationManager.hookAllMethods("unregisterLocationListener", afterHook {
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("unregisterLocationListener: listener is null: $method")
                return@afterHook
            }
            if(FakeLoc.enableDebugLog) {
                Logger.debug("unregisterLocationListener: injected! $listener")
            }

            removeLocationListenerInner(listener)
        })

        run {
            cILocationManager.hookAllMethods("addGnssBatchingCallback", beforeHook {
                if (hasThrowable() || args.isEmpty() || args[0] == null) return@beforeHook
                val callback = args[0] ?: return@beforeHook
                val classCallback = callback.javaClass

                if(FakeLoc.enableDebugLog) {
                    Logger.debug("addGnssBatchingCallback: injected!")
                }

                classCallback.onceHookAllMethod("onLocationBatch", beforeHook onLocationBatch@ {
                    if (args.isEmpty()) return@onLocationBatch

                    if (!FakeLoc.enable) {
                        return@onLocationBatch
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("onLocationBatch: injected!")
                    }

                    val location = (args[0] ?: return@onLocationBatch) as Location
                    args[0] = injectLocation(location)
                })
            })
        }

        cILocationManager.hookAllMethods("requestGeofence", beforeHook {
            if (FakeLoc.disableRequestGeofence && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("requestGeofence: injected!")
                }
                result = null
            }
        })
//        cILocationManager.hookAllMethods("removeGeofence", beforeHook {
//        })

        cILocationManager.hookAllMethods("getFromLocation", beforeHook {
            if (FakeLoc.disableGetFromLocation && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("getFromLocation: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("getFromLocationName", beforeHook {
            if (FakeLoc.disableGetFromLocation && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("getFromLocationName: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("addTestProvider", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("addTestProvider: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("removeTestProvider", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("removeTestProvider: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("setTestProviderLocation", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("setTestProviderLocation: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("setTestProviderEnabled", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("setTestProviderEnabled: injected!")
                }
                result = null
            }
        })

        if(XposedBridge.hookAllMethods(cILocationManager, "registerGnssStatusCallback", object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    if(param == null || param.args.isEmpty()) return

                    val callback = findGnssStatusCallback(param.args) ?: run {
                        Logger.warn("[LocationServiceHook] registerGnssStatusCallback: callback not found in ${param.method}")
                        return
                    }
                    val cIGnssStatusListener = callback.javaClass

                    if(FakeLoc.enableDebugLog) {
                        Logger.debug("registerGnssStatusCallback: injected!")
                    }
                    if (!FakeLoc.enableMockGnss) {
                        return
                    }

                    val gnssStatusHook = beforeHook {
                        // https://www.csno-tarc.cn/system/constellation

                        if (!FakeLoc.enableMockGnss) return@beforeHook

                        val now = SystemClock.elapsedRealtime()
                        val deltaMs = if (lastGnssUpdateElapsed == 0L) 1000L else (now - lastGnssUpdateElapsed)
                        lastGnssUpdateElapsed = now
                        updateSatelliteStates(deltaMs / 1000f)

                        // 筛出有星历数据的卫星（不限制 elevation，星历计算的 elevation 值保留在 state 中）
                        val ephemerisSatellites = multiConstellationSatellites.filter {
                            val state = getOrCreateState(it)
                            state.hasEphemeris
                        }

                        val selectedSatellites = selectSatellitesForMock(ephemerisSatellites)
                        val svCount = selectedSatellites.size

                        val mockGps = MockGnssData(
                            svCount = svCount,
                            svidWithFlags = IntArray(svCount),
                            cn0s = FloatArray(svCount),
                            elevations = FloatArray(svCount),
                            azimuths = FloatArray(svCount),
                            carrierFreqs = FloatArray(svCount)
                        ).apply {
                            selectedSatellites.forEachIndexed { index, sat ->
                                val state = getOrCreateState(sat)
                                val hasEphemeris = state.hasEphemeris
                                val hasAlmanac = state.hasAlmanac
                                val usedInFix = state.usedInFix
                                val hasCarrierFreq = true
                                val hasBasebandCn0 = true

                                var flags = GnssFlags.SVID_FLAGS_NONE

                                if (hasEphemeris) flags = flags or GnssFlags.SVID_FLAGS_HAS_EPHEMERIS_DATA
                                if (hasAlmanac) flags = flags or GnssFlags.SVID_FLAGS_HAS_ALMANAC_DATA
                                if (usedInFix) flags = flags or GnssFlags.SVID_FLAGS_USED_IN_FIX
                                if (hasCarrierFreq) flags = flags or GnssFlags.SVID_FLAGS_HAS_CARRIER_FREQUENCY
                                if (hasBasebandCn0) flags = flags or GnssFlags.SVID_FLAGS_HAS_BASEBAND_CN0

                                svidWithFlags[index] = (sat.prn shl GnssFlags.SVID_SHIFT_WIDTH) or
                                        ((sat.constellation and GnssFlags.CONSTELLATION_TYPE_MASK) shl GnssFlags.CONSTELLATION_TYPE_SHIFT_WIDTH) or
                                        flags

                                cn0s[index] = state.cn0
                                elevations[index] = state.elevation
                                azimuths[index] = state.azimuth
                                // Android GnssStatus.getCarrierFrequencyHz() 返回 Hz，需将 MHz 转 Hz
                                carrierFreqs[index] = state.carrierFreq * 1e6f
                            }
                        }

                        lastMockGnssData = mockGps
                        lastMockGnssDataTime = now

                        if (args[0] is Int) {
                            args[0] = svCount
                            args[1] = mockGps.svidWithFlags
                            args[2] = mockGps.cn0s
                            args[3] = mockGps.elevations
                            args[4] = mockGps.azimuths
                            if (args.size > 5) {
                                args[5] = mockGps.carrierFreqs
                            }

                            if (args.size > 6) {
                                args[6] = FloatArray(svCount) {
                                    mockGps.cn0s[it] - Random.nextFloat(2f, 5f)
                                }
                            }
                            return@beforeHook
                        }

                        if (args[0] != null && args[0].javaClass.name == "android.location.GnssStatus") {
                            runCatching {
                                val mConstructor = args[0].javaClass.declaredConstructors.firstOrNull {
                                    it.parameterTypes.size == 7
                                }.also {
                                    it?.isAccessible = true
                                }

                                if (mConstructor != null) {
                                    args[0] = mConstructor.newInstance(
                                        svCount,
                                        mockGps.svidWithFlags,
                                        mockGps.cn0s,
                                        mockGps.elevations,
                                        mockGps.azimuths,
                                        mockGps.carrierFreqs,
                                        FloatArray(svCount) {
                                            mockGps.cn0s[it] - Random.nextFloat(2f, 5f)
                                        }
                                    )
                                } else {
                                    Logger.error("onSvStatusChanged: unsupported version: ${method}, constructor not found")
                                }
                            }.onFailure {
                                XposedBridge.log(it)
                            }
                            return@beforeHook
                        }

                        Logger.error("onSvStatusChanged: unsupported version: $method")
                    }
                    if (hookGnssStatusCallbackMethods(callback, gnssStatusHook) == 0) {
                        logMissingGnssStatusCallback(callback)
                    }

                    cIGnssStatusListener.onceHookAllMethod("onNmeaReceived", beforeHook {
                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("onNmeaReceived")
                        }
                        if (FakeLoc.enableMockGnss) result = null
                    })
                }
            }).isEmpty()) {
            XposedBridge.log("[PTL] hook registerGnssStatusCallback failed")
        }
        // android 11+
        // @EnforcePermission("LOCATION_HARDWARE")
        // void startGnssBatch(long periodNanos, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
        //
        // void startGnssBatch(long periodNanos, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
        cILocationManager.hookAllMethods("startGnssBatch", beforeHook {
            if(FakeLoc.enableDebugLog) {
                Logger.debug("startGnssBatch: injected!")
            }

            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                    Logger.error("startGnssBatch: listener is null: $method")
                    return@beforeHook
                }

                addLocationListenerInner("GnssBatch", listener)
                hookILocationListener(listener)
            }
        })
        cILocationManager.hookAllMethods("stopGnssBatch", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("stopGnssBatch: injected!")
                }
            }
            //locationListeners.removeIf { it.first == "GnssBatch" }
        })

        //  void requestListenerFlush(String provider, in ILocationListener listener, int requestCode);
        cILocationManager.hookAllMethods("requestListenerFlush", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("requestListenerFlush: injected!")
                }

                val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                    Logger.error("requestListenerFlush: listener is null: $method")
                    return@beforeHook
                }

                addLocationListenerInner("gps", listener)

                if (FakeLoc.disableRegisterLocationListener || FakeLoc.enable) {
                    result = null
                }

                hookILocationListener(listener)
            }
        })

//        cILocationManager.hookAllMethods("getBestProvider", beforeHook {
//            if (FakeLoc.enable) {
//                result = "gps"
//            }
//        })
//
//        cILocationManager.hookAllMethods("getAllProviders", afterHook {
//            if(FakeLoc.enable) {
//                result = if (result is List<*>) {
//                    listOf("gps", "passive")
//                } else if (result is Array<*>) {
//                    arrayOf("gps", "passive")
//                } else {
//                    Logger.error("getAllProviders: result is not List or Array")
//                    return@afterHook
//                }
//            }
//        })
//
//        cILocationManager.hookAllMethods("getProviders", afterHook {
//            if(FakeLoc.enable) {
//                result = if (result is List<*>) {
//                    listOf("gps", "passive")
//                } else if (result is Array<*>) {
//                    arrayOf("gps", "passive")
//                } else {
//                    Logger.error("getProviders: result is not List or Array")
//                    return@afterHook
//                }
//            }
//        })
//
//        cILocationManager.hookAllMethods("hasProvider", beforeHook {
//            if (FakeLoc.enableDebugLog) {
//                Logger.debug("hasProvider: ${args[0]}")
//            }
//
//            if(FakeLoc.enable) {
//                if (args[0] == "gps") {
//                    result = true
//                } else if (args[0] == "network") {
//                    result = false
//                } else if (args[0] == "fused" && FakeLoc.disableFusedLocation) {
//                    result = false
//                }
//            }
//        })

        cILocationManager.hookAllMethods("getCurrentLocation", beforeHook {
            val callback = args[2] ?: return@beforeHook

            if (FakeLoc.enableDebugLog) {
                Logger.debug("getCurrentLocation: injected!")
            }

            if (FakeLoc.disableGetCurrentLocation) {
                result = null
                return@beforeHook
            }

            val classCallback = callback.javaClass
            classCallback.onceHookAllMethod("onLocation", beforeHook onLocation@ {
                val location = args[0] as? Location ?: return@onLocation

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("onLocation(getCurrentLocation): injected!")
                }

                args[0] = injectLocation(location)
            })
        })

        cILocationManager.hookAllMethods("sendExtraCommand", beforeHook {
            if (args.size < 3) return@beforeHook

            val provider = args[0] as String
            val command = args[1] as String
            val outResult = args[2] as? Bundle

            if (FakeLoc.disableFusedLocation && provider == "fused") {
                result = false
                return@beforeHook
            }

            // If the GPS provider is enabled, the GPS provider is disabled
            if(provider == "gps" && FakeLoc.enable) {
                result = false
                return@beforeHook
            }

            if(provider == "LOCATION_BIG_DATA") {
                result = false
                return@beforeHook
            }

            if (provider != ChannelIdentity.providerName) {
                if (FakeLoc.enableDebugLog)
                    Logger.debug("sendExtraCommand provider: $provider, command: $command, result: $result")
                return@beforeHook
            }
            if (outResult == null) return@beforeHook

            if (handleInstruction(command, outResult)) {
                result = true
            }
        })

        if(
        // boolean isProviderEnabledForUser(String provider, int userId); from android 9.0.0
            XposedBridge.hookAllMethods(
                cILocationManager,
                "isProviderEnabledForUser",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.size < 2 || param.args[0] == null) return
                        val provider = param.args[0] as String
                        var userId = param.args[1] as Int
                        if (provider == ChannelIdentity.providerName) {
                            if (userId == 0) {
                                userId = BinderUtils.getCallerUid()
                            }
                            param.result = BinderUtils.isLocationProviderEnabled(userId)
                        } else if(provider == "network") {
                            param.result = !FakeLoc.enable
                        } else if (FakeLoc.disableFusedLocation && provider == "fused") {
                            param.result = false
                            return
                        } else {
                            if (FakeLoc.enableDebugLog) {
                                 Logger.debug("isProviderEnabledForUser provider: $provider, userId: $userId")
                            }
                        }
                    }
                }).isEmpty()
        ) {
            // boolean isProviderEnabled(String provider);
            XposedBridge.hookAllMethods(
                cILocationManager,
                "isProviderEnabled",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.isEmpty() || param.args[0] == null) return
                        val provider = param.args[0] as String
                        val userId = BinderUtils.getCallerUid()
                        if (provider == ChannelIdentity.providerName && BinderUtils.isLocationProviderEnabled(userId)) {
                            param.result = true
                        } else if(provider == "network") {
                            param.result = !FakeLoc.enable
                        } else if (FakeLoc.disableFusedLocation && provider == "fused") {
                            param.result = false
                            return
                        }
                    }
                })
        }


        // F**k You! AMAP Service!
        XposedBridge.hookAllMethods(cILocationManager, "setExtraLocationControllerPackageEnabled", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (FakeLoc.enable) {
                    param.args[0] = false
                }
            }
        })

        XposedBridge.hookAllMethods(cILocationManager, "setExtraLocationControllerPackage", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (FakeLoc.enable) {
                    param.result = null
                }
            }
        })

    }

    fun hookILocationListener(listener: Any) {
        val classListener = listener.javaClass
        if (FakeLoc.enableDebugLog)
            Logger.debug("will hook ILocationListener: ${classListener.name}")

        if(XposedBridge.hookAllMethods(classListener, "onLocationChanged", object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isEmpty()) return
                    if (!FakeLoc.enable) return

                    when (param.args[0]) {
                        is Location -> {
                            val location = param.args[0] as? Location ?: run {
                                param.result = null
                                return
                            }
                            param.args[0] = injectLocation(location)
                        }

                        is List<*> -> {
                            val locations = param.args[0] as List<*>
                            param.args[0] = locations.map { injectLocation(it as Location) }
                        }
                        else -> Logger.error("onLocationChanged args is not `Location`")
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("${param.method}: injected! ${param.args[0]}")
                    }
                }
            }).isEmpty()) {
            Logger.error("hook onLocationChanged failed")
            return // If the hook fails, the listener is not added
        }
    }

//    private fun startDaemon(classLoader: ClassLoader) {
//        //val cIRemoteCallback = XposedHelpers.findClass("android.os.IRemoteCallback", classLoader)
//        thread(
//            name = "LocationUpdater",
//            isDaemon = true,
//            start = true,
//        ) {
//            while (true) {
//                kotlin.runCatching {
//                    if (!FakeLoc.enable) {
//                        Thread.sleep(3000)
//                        return@runCatching
//                    } else {
//                        Thread.sleep(FakeLoc.updateInterval)
//                    }
//
//                    if (!FakeLoc.enable) return@runCatching // Prevent the last loop from being executed
//
//                    if (FakeLoc.enableDebugLog)
//                        Logger.debug("LocationUpdater: callOnLocationChanged: ${locationListeners.size}")
//
//                    callOnLocationChanged()
//                }.onFailure {
//                    Logger.error("LocationUpdater", it)
//                }
//            }
//        }
//    }

    fun addLocationListenerInner(provider: String, listener: IInterface) {
        val binder = listener.asBinder()
        val mDeathRecipient = object: IBinder.DeathRecipient {
            override fun binderDied() { binderDied(binder) }
            override fun binderDied(who: IBinder) {
                who.unlinkToDeath(this, 0)
                removeLocationListenerByBinder(who)
            }
        }
        binder.linkToDeath(mDeathRecipient, 0)
        locationListeners.add(Triple(provider, listener, mDeathRecipient))
        hookILocationListener(listener)
    }

    fun removeLocationListenerInner(listener: IInterface) {
        removeLocationListenerByBinder(listener.asBinder())
    }

    fun removeLocationListenerByBinder(binder: IBinder) {
        val entry = locationListeners.firstOrNull { it.second.asBinder() == binder }
        if (entry != null) {
            binder.unlinkToDeath(entry.third, 0)
        }
        locationListeners.removeIf { it.second.asBinder() == binder }
    }

    fun callOnLocationChanged() {
        Logger.debugRL("callOnLocationChanged", "==> callOnLocationChanged: ${locationListeners.size}")
        locationListeners.forEach { listenerWithProvider ->
            val listener = listenerWithProvider.second
            var location = FakeLoc.lastLocation
            if (location == null) {
                location = if (listenerWithProvider.first == "GnssBatch") {
                    Location("gps")
                } else {
                    Location(listenerWithProvider.first)
                }
            }
            location = injectLocation(location)

            // Bypass check: if the caller is a known probe, skip mock injection
            val callerUid = Binder.getCallingUid()
            if (tv.kyshu.xposed.security.BypassDetector.classify(callerUid, null, "LOCATION_UPDATE") !=
                tv.kyshu.xposed.security.BypassDetector.BypassAction.PASSTHROUGH) {
                return
            }
            var called = false
            var error: Throwable? = null
            kotlin.runCatching {
                val locations = listOf(location)
                val mOnLocationChanged = XposedHelpers.findMethodBestMatch(listener.javaClass, "onLocationChanged", locations, null)
                XposedBridge.invokeOriginalMethod(mOnLocationChanged, listener, arrayOf(locations, null))
                called = true
            }.onFailure {
                if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                    return@forEach
                }
                error = it
            }

            if (!called) runCatching {
                val mOnLocationChanged = XposedHelpers.findMethodBestMatch(listener.javaClass, "onLocationChanged", location)
                XposedBridge.invokeOriginalMethod(mOnLocationChanged, listener, arrayOf(location))
                called = true
            }.onFailure {
                if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                    return@forEach
                }
                error = it
            }

            if (!called) {
                Logger.error("callOnLocationChanged failed: " + error?.stackTraceToString())
                Logger.error("The listener all methods: " + listener.javaClass.declaredMethods.joinToString { it.name })
            }
        }
        // 位置更新时同步结算卫星星图位置
        if (FakeLoc.enableMockGnss) {
            forceUpdateSatelliteStates()
        }
        Logger.debugRL("callOnLocationChanged_end", "==> callOnLocationChanged: end")
    }

    fun hookLocationManagerServiceV2(classLoader: ClassLoader) {
        // As a system_server, the hook can get all the location information here
        kotlin.runCatching {
            XposedHelpers.findClass("android.location.ILocationManager\$Stub", classLoader)
        }.onSuccess {
            fun hookOnTransactForServiceInstance(m: Method) {
                val isHooked = AtomicBoolean(false)
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param?.thisObject == null || param.args.size < 4) return

                        val thisObject = param.thisObject
                        val code = param.args[0] as? Int ?: return
                        val data = param.args[1] as? Parcel ?: return
                        val reply = param.args[2] as? Parcel ?: return
                        val flags = param.args[3] as? Int ?: return

                        if (isHooked.compareAndSet(false, true)) {
                            onService(thisObject.javaClass)
                        }

                        if (!FakeLoc.enable) {
                            return
                        }

                        if (FakeLoc.enable && code == 43) {
                            param.result = true
                        }

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("ILocationManager.Stub: onTransact(code=$code)")
                        }
                    }
                })
            }

            it.declaredMethods.forEach {
                if (it.name == "onTransact") {
                    hookOnTransactForServiceInstance(it)

                    // Hey, hey, you've found onTransact, what else are you looking for
                    // It's time to end the cycle! BaKa!
                    return@forEach
                }
            }
        }.onFailure {
            Logger.error("ILocationManager.Stub not found", it)
        }

//        // This is the intrusive hook
//        kotlin.runCatching {
//            XposedHelpers.findClass("android.location.ILocationManager\$Stub\$Proxy", cLocationManager.classLoader)
//        }.onSuccess {
//            it.declaredMethods.forEach {
//                XposedBridge.hookMethod(it, object : XC_MethodHook() {
//                    override fun beforeHookedMethod(param: MethodHookParam?) {
//                        if (param == null) return
//
//                        XposedBridge.log("[PTL] ILocationManager.Stub.Proxy: c = ${param.thisObject?.javaClass}, m = ${param.method}")
//                    }
//                })
//            }
//        }
    }

    fun handleInstruction(command: String, rely: Bundle): Boolean {
        return RemoteCommandHandler.handleInstruction(command, rely)
    }
}

private fun Random.nextFloat(min: Float, max: Float): Float {
    return nextFloat() * (max - min) + min
}

private fun clamp(value: Float, min: Float, max: Float): Float {
    // NaN breaks comparison contract; treat as minimum valid value
    if (value.isNaN()) return min
    return when {
        value < min -> min
        value > max -> max
        else -> value
    }
}
