/*
 * Copyright (C) 2025 The KunYuShu Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tv.kyshu.xposed.hooks.nmea

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import tv.kyshu.xposed.BaseLocationHook
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.onceHookAllMethod
import tv.kyshu.xposed.utils.onceHookBefore
import tv.kyshu.xposed.utils.onceHookMethodBefore
import tv.kyshu.xposed.utils.GnssSatellite
import java.util.Collections
import kotlin.math.roundToInt
import tv.kyshu.xposed.utils.satelliteList
import tv.kyshu.xposed.utils.GnssFlags
import tv.kyshu.xposed.utils.OrbitType
import tv.kyshu.xposed.hooks.LocationServiceHook
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import java.lang.reflect.Modifier

object LocationNMEAHook: BaseLocationHook() {

    // GSV 多句替换缓存按星座隔离，避免 GPS/Galileo/BeiDou 多句流互相串台。
    private data class GsvSequence(val sentences: List<String>, var nextIndex: Int)
    private val gsvSequences = mutableMapOf<Int, GsvSequence>()
    private val gsvCacheLock = Any()
    private val diagnosticKeys = ConcurrentHashMap.newKeySet<String>()
    private val skippedNmeaCallbackIds = ConcurrentHashMap.newKeySet<Int>()
    private val internalNmeaCallbackMarkers = listOf(
        "DeepThinkerDelegate",
        "com.android.server.display.ambience.",
    )

    private fun logOnce(key: String, message: String) {
        if (diagnosticKeys.add(key)) {
            Logger.info(message)
        }
    }

    internal fun shouldSkipNmeaCallback(callback: Any): Boolean {
        if (matchesInternalNmeaCallback(callback.javaClass.name)) return true
        return callback.javaClass.concreteClassHierarchy().any { clazz ->
            clazz.declaredFields.any { field ->
                if (Modifier.isStatic(field.modifiers)) return@any false
                if (matchesInternalNmeaCallback(field.type.name)) return@any true
                runCatching {
                    if (!field.isAccessible) field.isAccessible = true
                    val value = field.get(callback) ?: return@runCatching false
                    matchesInternalNmeaCallback(value.javaClass.name)
                }.getOrDefault(false)
            }
        }
    }

    private fun matchesInternalNmeaCallback(className: String): Boolean {
        return internalNmeaCallbackMarkers.any { className.contains(it) }
    }

    internal fun shouldSkipNmeaRegistration(callback: Any, args: Array<Any?>): Boolean {
        if (shouldSkipNmeaCallback(callback)) return true
        val stringArgs = args.filterIsInstance<String>()
        if (stringArgs.any(::matchesInternalNmeaCallback)) return true
        return stringArgs.any { it == "android" || it.startsWith("com.android.server.") }
    }

    internal fun rememberSkippedNmeaRegistration(callback: Any, args: Array<Any?>): Boolean {
        val shouldSkip = shouldSkipNmeaRegistration(callback, args)
        if (shouldSkip) {
            skippedNmeaCallbackIds.add(callbackId(callback))
        }
        return shouldSkip
    }

    internal fun shouldSkipNmeaCallbackInstance(callback: Any): Boolean {
        return skippedNmeaCallbackIds.contains(callbackId(callback)) || shouldSkipNmeaCallback(callback)
    }

    private fun callbackId(callback: Any): Int = System.identityHashCode(callback)

    private fun Class<*>.concreteClassHierarchy(): Sequence<Class<*>> = sequence {
        var clazz: Class<*>? = this@concreteClassHierarchy
        while (clazz != null && clazz != Any::class.java) {
            if (!clazz.isInterface) yield(clazz)
            clazz = clazz.superclass
        }
    }

    operator fun invoke(classILocationManager: Class<*>) {
        Logger.info("[LocationNMEAHook] install start on ${classILocationManager.name}")
        hookGnssNmea(classILocationManager)

        val doNothingMethod = object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (param == null || param.args.isEmpty()) return

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("doNothingMethod: ${param.method.name}")
                }

                if (FakeLoc.enable && !FakeLoc.enableNMEA && !FakeLoc.enableAGPS) {
                    logOnce("gnss.raw.disabled.${param.method.name}",
                        "[LocationNMEAHook] ${param.method.name} blocked: enableNMEA=false enableAGPS=false")
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("${param.method.name}: disable")
                    }
                    param.result = null
                }
            }
        }

        listOf(
            "addGnssMeasurementsListener",
            "removeGnssMeasurementsListener",
            "addGnssNavigationMessageListener",
            "removeGnssNavigationMessageListener",
            "addGnssAntennaInfoListener",
            "removeGnssAntennaInfoListener",
        ).forEach { methodName ->
            val count = runCatching { XposedBridge.hookAllMethods(classILocationManager, methodName, doNothingMethod).size }
                .onFailure { Logger.warn("[LocationNMEAHook] $methodName hook failed", it) }
                .getOrDefault(0)
            if (count == 0) {
                Logger.warn("[LocationNMEAHook] $methodName not found on ${classILocationManager.name}")
            } else {
                Logger.info("[LocationNMEAHook] hooked $count overload(s): ${classILocationManager.name}#$methodName")
            }
        }
    }

    private fun hookGnssNmea(classILocationManager: Class<*>) {
        val hookedGnssCallback = Collections.synchronizedSet(HashSet<String>())
        val candidateMethods = classILocationManager.declaredMethods.filter {
            it.name == "registerGnssNmeaCallback" && it.parameterTypes.size > 1
        }
        if (candidateMethods.isEmpty()) {
            Logger.warn("[LocationNMEAHook] registerGnssNmeaCallback not found on ${classILocationManager.name}")
        } else {
            Logger.info("[LocationNMEAHook] found ${candidateMethods.size} registerGnssNmeaCallback candidate(s): " +
                candidateMethods.joinToString { it.parameterTypes.joinToString(prefix = "(", postfix = ")") { type -> type.simpleName } })
        }
        val unhooks = candidateMethods.map { method ->
            method.onceHookBefore {
                val callback = args.firstOrNull { arg ->
                    arg != null && arg.javaClass.declaredMethods.any { it.name == "onNmeaReceived" }
                } ?: args.firstOrNull()
                val cIGnssNmeaCallback = (callback ?: run {
                    Logger.warn("[LocationNMEAHook] registerGnssNmeaCallback callback missing: method=$method args=${args.map { it?.javaClass?.name }}")
                    return@onceHookBefore
                }).javaClass

                Logger.info("[LocationNMEAHook] registerGnssNmeaCallback hit: callback=${cIGnssNmeaCallback.name}, args=${args.map { it?.javaClass?.simpleName }}")
                if (rememberSkippedNmeaRegistration(callback, args)) {
                    logOnce("nmea.callback.skip.${cIGnssNmeaCallback.name}",
                        "[LocationNMEAHook] registerGnssNmeaCallback skipped for internal ambience callback=${cIGnssNmeaCallback.name}")
                }

                if (hookedGnssCallback.contains(cIGnssNmeaCallback.name)) return@onceHookBefore
                hookedGnssCallback.add(cIGnssNmeaCallback.name)

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("registerGnssNmeaCallback: $cIGnssNmeaCallback")
                }

                cIGnssNmeaCallback.onceHookMethodBefore("onNmeaReceived", Long::class.java, String::class.java) {
                    if (shouldSkipNmeaCallbackInstance(thisObject)) {
                        logOnce("nmea.runtime.skip.${thisObject.javaClass.name}",
                            "[LocationNMEAHook] onNmeaReceived skipped for internal ambience callback=${thisObject.javaClass.name}")
                        return@onceHookMethodBefore
                    }
                    if (!FakeLoc.enable) {
                        logOnce("nmea.runtime.disabled",
                            "[LocationNMEAHook] onNmeaReceived skipped: FakeLoc.enable=false")
                        return@onceHookMethodBefore
                    }

                    if (!FakeLoc.enableNMEA && !FakeLoc.enableAGPS) {
                        logOnce("nmea.runtime.blocked",
                            "[LocationNMEAHook] onNmeaReceived blocked: enableNMEA=false enableAGPS=false")
                        result = null
                        return@onceHookMethodBefore
                    }

                    val nmea = args[1] as? String ?: return@onceHookMethodBefore
                    val injected = injectNMEA(nmea)
                    if (injected != null) {
                        logOnce("nmea.runtime.injected.${nmea.take(6)}",
                            "[LocationNMEAHook] NMEA injected: ${nmea.take(6)} -> ${injected.take(6)}, len=${nmea.length}->${injected.length}")
                        args[1] = injected
                    } else {
                        logOnce("nmea.runtime.pass.${nmea.take(6)}",
                            "[LocationNMEAHook] NMEA passed through: ${nmea.take(16)}")
                    }
                }
            }
        }

        Logger.info("[LocationNMEAHook] installed ${unhooks.size} registerGnssNmeaCallback hook(s)")
    }

    override fun injectNMEA(nmea: String): String? {
        if (!FakeLoc.enable) {
            logOnce("injectNMEA.disabled", "[LocationNMEAHook] injectNMEA skipped: FakeLoc.enable=false")
            return null
        }

        val original = nmea.trimEnd('\r', '\n')
        if (!isSingleNmeaSentence(original)) {
            logOnce("injectNMEA.invalid.input.${original.take(8)}",
                "[LocationNMEAHook] injectNMEA skipped invalid input: ${original.take(96)}")
            return null
        }
        
        // Handle GSV injection with multi-sentence caching
        if (original.contains("GSV")) {
            synchronized(gsvCacheLock) {
                val gsvIdentity = parseGsvIdentity(original) ?: return null
                val (constellation, talker, msgNum) = gsvIdentity

                if (msgNum > 1) {
                    val sequence = gsvSequences[constellation] ?: return null
                    val cached = sequence.sentences.getOrNull(sequence.nextIndex) ?: run {
                        gsvSequences.remove(constellation)
                        return null
                    }
                    sequence.nextIndex++
                    if (sequence.nextIndex >= sequence.sentences.size) {
                        gsvSequences.remove(constellation)
                    }
                    return finalizeNmeaForCallback(cached, nmea)
                }

                // Generate complete GSV sequence, split into individual sentences, cache them.
                val fullSequence = runCatching { generateGSV(constellation, talker) }
                    .onFailure { Logger.warn("[LocationNMEAHook] generateGSV failed constellation=$constellation", it) }
                    .getOrNull() ?: return null
                val sentences = fullSequence.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                if (sentences.isEmpty()) return null
                gsvSequences[constellation] = GsvSequence(sentences, nextIndex = 1)
                logOnce("gsv.generated.$constellation",
                    "[LocationNMEAHook] generated GSV constellation=$constellation sentences=${sentences.size}")

                return finalizeNmeaForCallback(sentences[0], nmea)
            }
        }

        // Generate realistic GGA with probabilistic fix quality
        if (nmea.startsWith("\$GPGGA") || nmea.startsWith("\$GNGGA")) {
            // Parse original fix quality (7th field, 0-index=6)
            val originalFixQuality = nmea.split(",").getOrNull(6)?.toIntOrNull() ?: 1
            // Probabilistic fix quality: 70% → 1 (standard), 10% → 2 (differential), 20% → keep original
            val rand = kotlin.random.Random.nextFloat()
            val fixQuality = when {
                rand < 0.70f -> 1
                rand < 0.80f -> 2
                else -> originalFixQuality
            }

            val usedSatellitesCount = FakeLoc.minSatellites +
                kotlin.random.Random.nextInt(0, 8)
            return runCatching {
                tv.kyshu.xposed.utils.NmeaGenerator.generateGGA(
                    FakeLoc.latitude,
                    FakeLoc.longitude,
                    FakeLoc.altitude,
                    usedSatellitesCount.coerceAtMost(35),
                    FakeLoc.accuracy,
                    fixQuality
                )
            }.getOrNull()
                ?.let { finalizeNmeaForCallback(it, nmea) }
        }

        if (nmea.startsWith("\$GPRMC") || nmea.startsWith("\$GNRMC")) {
             return runCatching {
                 tv.kyshu.xposed.utils.NmeaGenerator.generateRMC(
                     FakeLoc.latitude,
                     FakeLoc.longitude,
                     FakeLoc.speed,
                     FakeLoc.bearing
	                 )
	             }.getOrNull()
                 ?.let { finalizeNmeaForCallback(it, nmea) }
        }

        // Generate GSA: mode+fix type + PRN of used satellites + PDOP/HDOP/VDOP
        if (nmea.startsWith("\$GPGSA") || nmea.startsWith("\$GNGSA")) {
            val usedCount = FakeLoc.minSatellites + kotlin.random.Random.nextInt(0, 8)
            val satelliteIds = (1..usedCount.coerceIn(4, 24)).map { kotlin.random.Random.nextInt(1, 33) }
            return runCatching {
                tv.kyshu.xposed.utils.NmeaGenerator.generateGSA(
                    fixType = if (usedCount >= 4) 3 else if (usedCount >= 3) 2 else 1,
                    satelliteIds = satelliteIds.take(12),
                    pdop = 1.0 + kotlin.random.Random.nextDouble() * 2.0,
                    hdop = 0.5 + kotlin.random.Random.nextDouble() * 1.5,
                    vdop = 0.5 + kotlin.random.Random.nextDouble() * 2.0
                )
            }.getOrNull()
                ?.let { finalizeNmeaForCallback(it, nmea) }
        }

        // Generate VTG: course + speed
        if (nmea.startsWith("\$GPVTG") || nmea.startsWith("\$GNVTG")) {
            return runCatching {
                tv.kyshu.xposed.utils.NmeaGenerator.generateVTG(
                    FakeLoc.bearing,
                    FakeLoc.speed
                )
            }.getOrNull()
                ?.let { finalizeNmeaForCallback(it, nmea) }
        }

        // Generate GST: pseudorange residual statistics
        if (nmea.startsWith("\$GPGST") || nmea.startsWith("\$GNGST")) {
            return runCatching {
                tv.kyshu.xposed.utils.NmeaGenerator.generateGST(
                    FakeLoc.latitude,
                    FakeLoc.longitude,
                    FakeLoc.accuracy
                )
            }.getOrNull()
                ?.let { finalizeNmeaForCallback(it, nmea) }
        }

        return super.injectNMEA(nmea)
    }

    private data class GsvIdentity(val constellation: Int, val talker: String, val msgNum: Int)

    private fun parseGsvIdentity(nmea: String): GsvIdentity? {
        val talker = nmea.removePrefix("$").take(2)
        val constellation = when {
            nmea.startsWith("\$GPGSV") -> GnssFlags.CONSTELLATION_GPS
            nmea.startsWith("\$GLGSV") -> GnssFlags.CONSTELLATION_GLONASS
            nmea.startsWith("\$GAGSV") -> GnssFlags.CONSTELLATION_GALILEO
            nmea.startsWith("\$GBGSV") || nmea.startsWith("\$BDGSV") -> GnssFlags.CONSTELLATION_BEIDOU
            nmea.startsWith("\$GQGSV") -> GnssFlags.CONSTELLATION_QZSS
            else -> return null
        }
        val parts = nmea.split(",")
        val msgNum = parts.getOrNull(2)?.substringBefore("*")?.toIntOrNull() ?: run {
            logOnce("gsv.invalid.msgnum.${nmea.take(8)}",
                "[LocationNMEAHook] GSV skipped invalid message number: ${nmea.take(96)}")
            return null
        }
        return GsvIdentity(constellation, talker, msgNum)
    }

    private fun isSingleNmeaSentence(sentence: String): Boolean {
        if (!sentence.startsWith("$")) return false
        if (sentence.drop(1).contains("$")) return false
        if (sentence.any { it == '\r' || it == '\n' }) return false
        val star = sentence.indexOf('*')
        if (star <= 1 || star + 3 != sentence.length) return false
        val checksum = sentence.substring(star + 1)
        if (!checksum.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return false
        val content = sentence.substring(1, star)
        return calculateChecksum(content).equals(checksum, ignoreCase = true)
    }

    private fun finalizeNmeaForCallback(sentence: String, original: String): String? {
        val body = sentence.trimEnd('\r', '\n')
        if (!isSingleNmeaSentence(body)) {
            Logger.error("[LocationNMEAHook] generated invalid NMEA sentence, skipped: ${body.take(96)}")
            return null
        }
        val terminator = when {
            original.endsWith("\r\n") -> "\r\n"
            original.endsWith("\n") -> "\n"
            original.endsWith("\r") -> "\r"
            else -> "\r\n"
        }
        return body + terminator
    }

    private fun generateGSV(constellation: Int, talkerOverride: String? = null): String? {
        data class SatState(val prn: Int, val elevation: Float, val azimuth: Float, val cn0: Float)

        val observerLat = FakeLoc.latitude.toFloat()

        val satellites = satelliteList.filter {
            it.constellation == constellation
        }.mapNotNull { sat ->
            // 根据轨道类型生成可见性概率和仰角范围
            val (minElev, maxElev, visibleProb) = when (sat.type) {
                is OrbitType.GEO -> {
                    val latAbs = kotlin.math.abs(observerLat)
                    // 高纬度看不到GEO
                    if (latAbs > 70f) return@mapNotNull null
                    Triple(0f, 30f, 0.5f)
                }
                is OrbitType.IGSO -> Triple(20f, 75f, 0.7f)
                is OrbitType.MEO -> Triple(15f, 85f, 0.8f)
            }

            // 可见性概率过滤
            if (kotlin.random.Random.nextFloat() > visibleProb) return@mapNotNull null

            // 方位角基于卫星类型和模拟位置生成
            val azimuth = when (sat.type) {
                is OrbitType.GEO -> {
                    // GEO卫星大致在南方（北半球）或北方（南半球）
                    if (observerLat >= 0f) 150f + kotlin.random.Random.nextFloat() * 60f // 南向
                    else 330f + kotlin.random.Random.nextFloat() * 60f // 北向
                }
                else -> kotlin.random.Random.nextFloat() * 360f // 均匀分布
            }

            // 仰角在范围内随机但偏向中间值 (triangular分布)
            val elevation = minElev + (maxElev - minElev) * (0.3f + kotlin.random.Random.nextFloat() * 0.4f)

            // 信噪比基于轨道类型
            val cn0 = sat.type.minCn0 + kotlin.random.Random.nextFloat() * (sat.type.maxCn0 - sat.type.minCn0)

            SatState(
                prn = sat.prn,
                elevation = elevation.coerceIn(0f, 90f),
                azimuth = azimuth.coerceIn(0f, 360f),
                cn0 = cn0.coerceIn(10f, 55f)
            )
        }.sortedByDescending { it.prn }

        // 确保至少有一些卫星
        val effectiveSats = if (satellites.isEmpty()) {
            // 保底：生成4颗随机卫星
            (1..4).map {
                SatState(
                    prn = kotlin.random.Random.nextInt(1, 32),
                    elevation = 20f + kotlin.random.Random.nextFloat() * 50f,
                    azimuth = kotlin.random.Random.nextFloat() * 360f,
                    cn0 = 25f + kotlin.random.Random.nextFloat() * 20f
                )
            }
        } else satellites

        val sb = StringBuilder()
        val satsPerMsg = 4
        val totalMsgs = (effectiveSats.size + satsPerMsg - 1) / satsPerMsg

        val talker = talkerOverride ?: when (constellation) {
            GnssFlags.CONSTELLATION_GPS -> "GP"
            GnssFlags.CONSTELLATION_GLONASS -> "GL"
            GnssFlags.CONSTELLATION_GALILEO -> "GA"
            GnssFlags.CONSTELLATION_BEIDOU -> "GB"
            GnssFlags.CONSTELLATION_QZSS -> "GQ"
            else -> "GP"
        }

        for (i in 0 until totalMsgs) {
            val msgNum = i + 1
            val lineSb = StringBuilder()
            lineSb.append("$talker" + "GSV,")
            lineSb.append("$totalMsgs,")
            lineSb.append("$msgNum,")
            lineSb.append("${effectiveSats.size}")

            val startIdx = i * satsPerMsg
            val endIdx = minOf(startIdx + satsPerMsg, effectiveSats.size)

            for (j in startIdx until endIdx) {
                val sat = effectiveSats[j]
                lineSb.append(",")

                var prn = sat.prn

                if (constellation == GnssFlags.CONSTELLATION_QZSS) {
                    if (prn > 192) prn -= 192
                }

                lineSb.append(String.format(Locale.US, "%02d", prn))
                lineSb.append(",")
                lineSb.append(String.format(Locale.US, "%02d", sat.elevation.roundToInt()))
                lineSb.append(",")
                lineSb.append(String.format(Locale.US, "%03d", sat.azimuth.roundToInt()))
                lineSb.append(",")
                val snr = sat.cn0.roundToInt()
                if (snr > 0) {
                    lineSb.append(String.format(Locale.US, "%02d", snr))
                }
            }

            val content = lineSb.toString()
            val checksum = calculateChecksum(content)
            sb.append("\$")
            sb.append(content)
            sb.append("*")
            sb.append(checksum)

            if (i < totalMsgs - 1) {
                sb.append("\r\n")
            }
        }

        return sb.toString()
    }

    private fun calculateChecksum(sentence: String): String {
        var sum = 0
        for (char in sentence) {
            if (char == '$' || char == '*') continue
            sum = sum xor char.code
        }
        return String.format(Locale.US, "%02X", sum)
    }
}
