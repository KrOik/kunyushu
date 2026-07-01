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
import tv.kyshu.xposed.hooks.LocationServiceHook
import java.util.Locale

object LocationNMEAHook: BaseLocationHook() {

    // GSV 多句替换缓存: 有序列表, 每次收到 GSV 第 1 句时全量生成并缓存
    private val gsvSentenceCache = mutableListOf<String>()
    private var gsvSequenceTotal: Int = 0
    private var gsvSequenceSent: Int = 0
    operator fun invoke(classILocationManager: Class<*>) {
        hookGnssNmea(classILocationManager)

        val doNothingMethod = object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (param == null || param.args.isEmpty()) return

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("doNothingMethod: ${param.method.name}")
                }

                if (FakeLoc.enable && !FakeLoc.enableNMEA && !FakeLoc.enableAGPS) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("${param.method.name}: disable")
                    }
                    param.result = null
                }
            }
        }

        XposedBridge.hookAllMethods(classILocationManager, "addGnssMeasurementsListener", doNothingMethod)
        XposedBridge.hookAllMethods(classILocationManager, "removeGnssMeasurementsListener", doNothingMethod)
        XposedBridge.hookAllMethods(classILocationManager, "addGnssNavigationMessageListener", doNothingMethod)
        XposedBridge.hookAllMethods(classILocationManager, "removeGnssNavigationMessageListener", doNothingMethod)
        XposedBridge.hookAllMethods(classILocationManager, "addGnssAntennaInfoListener", doNothingMethod)
        XposedBridge.hookAllMethods(classILocationManager, "removeGnssAntennaInfoListener", doNothingMethod)
    }

    private fun hookGnssNmea(classILocationManager: Class<*>) {
        val hookedGnssCallback = Collections.synchronizedSet(HashSet<String>())
        val unhooks = classILocationManager.declaredMethods.filter {
            it.name == "registerGnssNmeaCallback" && it.parameterTypes.size > 1
        }.map { method ->
            method.onceHookBefore {
                val cIGnssNmeaCallback = (args[0] ?: return@onceHookBefore).javaClass

                if (hookedGnssCallback.contains(cIGnssNmeaCallback.name)) return@onceHookBefore
                hookedGnssCallback.add(cIGnssNmeaCallback.name)

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("registerGnssNmeaCallback: $cIGnssNmeaCallback")
                }

                cIGnssNmeaCallback.onceHookMethodBefore("onNmeaReceived", Long::class.java, String::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore

                    if (!FakeLoc.enableNMEA && !FakeLoc.enableAGPS) {
                        result = null
                        return@onceHookMethodBefore
                    }

                    val nmea = args[1] as? String ?: return@onceHookMethodBefore
                    args[1] = injectNMEA(nmea) ?: nmea
                }
            }
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("found ${unhooks.size} registerGnssNmeaCallback")
        }
    }

    override fun injectNMEA(nmea: String): String? {
        if (!FakeLoc.enable) return null
        
        // Handle GSV injection with multi-sentence caching
        if (nmea.contains("GSV")) {
            val parts = nmea.split(",")
            if (parts.size >= 3) {
                val msgNum = parts[2].toIntOrNull()
                if (msgNum != null && msgNum > 1) {
                    // Return cached sentence for multi-sentence GSV
                    if (gsvSequenceSent < gsvSequenceTotal && gsvSentenceCache.isNotEmpty()) {
                        val cached = gsvSentenceCache.getOrNull(gsvSequenceSent)
                        gsvSequenceSent++
                        if (gsvSequenceSent >= gsvSequenceTotal) {
                            // Last sentence served, clear cache
                            gsvSentenceCache.clear()
                            gsvSequenceTotal = 0
                        }
                        return cached
                    }
                    return null
                }
            }

            val constellation = when {
                nmea.startsWith("\$GPGSV") -> GnssFlags.CONSTELLATION_GPS
                nmea.startsWith("\$GLGSV") -> GnssFlags.CONSTELLATION_GLONASS
                nmea.startsWith("\$GAGSV") -> GnssFlags.CONSTELLATION_GALILEO
                nmea.startsWith("\$GBGSV") || nmea.startsWith("\$BDGSV") -> GnssFlags.CONSTELLATION_BEIDOU
                nmea.startsWith("\$GQGSV") -> GnssFlags.CONSTELLATION_QZSS
                else -> return null
            }

            // Generate complete GSV sequence, split into individual sentences, cache them
            val fullSequence = runCatching { generateGSV(constellation) }.getOrNull() ?: return null
            val sentences = fullSequence.split("\r\n")
            gsvSentenceCache.clear()
            gsvSentenceCache.addAll(sentences)
            gsvSequenceTotal = sentences.size
            gsvSequenceSent = 1 // 第 0 句即将返回

            return sentences.getOrNull(0)
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
        }

        // Generate VTG: course + speed
        if (nmea.startsWith("\$GPVTG") || nmea.startsWith("\$GNVTG")) {
            return runCatching {
                tv.kyshu.xposed.utils.NmeaGenerator.generateVTG(
                    FakeLoc.bearing,
                    FakeLoc.speed
                )
            }.getOrNull()
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
        }

        return super.injectNMEA(nmea)
    }

    private fun generateGSV(constellation: Int): String? {
        // Filter satellites by constellation and generate random state for each
        data class SatState(val prn: Int, val elevation: Float, val azimuth: Float, val cn0: Float)
        
        val satellites = satelliteList.filter { 
            it.constellation == constellation 
        }.mapNotNull { sat ->
            // Simulate 70% probability of being visible/used in fix
            if (kotlin.random.Random.nextFloat() > 0.3f) {
                SatState(
                    prn = sat.prn,
                    elevation = kotlin.random.Random.nextFloat() * 90f,
                    azimuth = kotlin.random.Random.nextFloat() * 360f,
                    cn0 = 20f + kotlin.random.Random.nextFloat() * 25f
                )
            } else null
        }.sortedByDescending { it.prn }

        val sb = StringBuilder()
        val satsPerMsg = 4
        val totalMsgs = (satellites.size + satsPerMsg - 1) / satsPerMsg
        
        val talker = when (constellation) {
            GnssFlags.CONSTELLATION_GPS -> "GP"
            GnssFlags.CONSTELLATION_GLONASS -> "GL"
            GnssFlags.CONSTELLATION_GALILEO -> "GA"
            GnssFlags.CONSTELLATION_BEIDOU -> "BD"
            GnssFlags.CONSTELLATION_QZSS -> "GQ"
            else -> "GP"
        }

        for (i in 0 until totalMsgs) {
            val msgNum = i + 1
            val lineSb = StringBuilder()
            lineSb.append("$talker" + "GSV,")
            lineSb.append("$totalMsgs,")
            lineSb.append("$msgNum,")
            lineSb.append("${satellites.size}")

            val startIdx = i * satsPerMsg
            val endIdx = minOf(startIdx + satsPerMsg, satellites.size)
            
            for (j in startIdx until endIdx) {
                val sat = satellites[j]
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
