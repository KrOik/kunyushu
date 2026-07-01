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
        
        // Handle GSV injection
        if (nmea.contains("GSV")) {
            val parts = nmea.split(",")
            if (parts.size >= 3) {
                val msgNum = parts[2].toIntOrNull()
                if (msgNum != null && msgNum > 1) {
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
            
            return runCatching { generateGSV(constellation) }.getOrNull()
        }

        // Generate realistic GGA/RMC using NmeaGenerator
        if (nmea.startsWith("\$GPGGA") || nmea.startsWith("\$GNGGA")) {
             val usedSatellitesCount = FakeLoc.minSatellites +
                 kotlin.random.Random.nextInt(0, 8)
             return runCatching {
                 tv.kyshu.xposed.utils.NmeaGenerator.generateGGA(
                     FakeLoc.latitude,
                     FakeLoc.longitude,
                     FakeLoc.altitude,
                     usedSatellitesCount.coerceAtMost(35),
                     FakeLoc.accuracy
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
