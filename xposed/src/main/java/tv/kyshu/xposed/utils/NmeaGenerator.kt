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

package tv.kyshu.xposed.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

object NmeaGenerator {
    private val timeFormat = SimpleDateFormat("HHmmss.SS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val dateFormat = SimpleDateFormat("ddMMyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun generateGGA(
        lat: Double,
        lon: Double,
        alt: Double,
        satellitesCount: Int,
        accuracy: Float,
        fixQuality: Int = 1
    ): String {
        val now = Date()
        val timeStr = timeFormat.format(now)
        
        val latDeg = floor(abs(lat)).toInt()
        val latMin = (abs(lat) - latDeg) * 60.0
        val latStr = String.format(Locale.US, "%02d%07.4f", latDeg, latMin)
        val latDir = if (lat >= 0) "N" else "S"

        val lonDeg = floor(abs(lon)).toInt()
        val lonMin = (abs(lon) - lonDeg) * 60.0
        val lonStr = String.format(Locale.US, "%03d%07.4f", lonDeg, lonMin)
        val lonDir = if (lon >= 0) "E" else "W"

        
        // Approximate HDOP from accuracy (assuming 5m UERE)
        // Ensure HDOP is within realistic bounds (0.5 - 5.0 usually)
        val hdop = (accuracy / 5.0f).coerceIn(0.5f, 20.0f)
        val hdopStr = String.format(Locale.US, "%.1f", hdop)

        val altStr = String.format(Locale.US, "%.1f", alt)
        val geoidSep = "0.0" // Simply 0 for now

        // $GPGGA,hhmmss.ss,llll.ll,a,yyyyy.yy,a,x,xx,x.x,x.x,M,x.x,M,x.x,xxxx*hh
        val content = "GPGGA,$timeStr,$latStr,$latDir,$lonStr,$lonDir,$fixQuality," +
                String.format(Locale.US, "%02d", satellitesCount) + ",$hdopStr,$altStr,M,$geoidSep,M,,"

        return "\$$content*${calculateChecksum(content)}"
    }

    fun generateRMC(
        lat: Double,
        lon: Double,
        speedMPS: Double,
        bearing: Double
    ): String {
        val now = Date()
        val timeStr = timeFormat.format(now)
        val dateStr = dateFormat.format(now)

        val latDeg = floor(abs(lat)).toInt()
        val latMin = (abs(lat) - latDeg) * 60.0
        val latStr = String.format(Locale.US, "%02d%07.4f", latDeg, latMin)
        val latDir = if (lat >= 0) "N" else "S"

        val lonDeg = floor(abs(lon)).toInt()
        val lonMin = (abs(lon) - lonDeg) * 60.0
        val lonStr = String.format(Locale.US, "%03d%07.4f", lonDeg, lonMin)
        val lonDir = if (lon >= 0) "E" else "W"

        // Speed in knots (1 m/s = 1.94384 knots)
        val speedKnots = speedMPS * 1.94384
        val speedStr = String.format(Locale.US, "%.1f", speedKnots)
        
        val courseStr = String.format(Locale.US, "%.1f", bearing)
        
        // $GPRMC,hhmmss.ss,A,llll.ll,a,yyyyy.yy,a,x.x,x.x,ddmmyy,,,a*hh
        val content = "GPRMC,$timeStr,A,$latStr,$latDir,$lonStr,$lonDir,$speedStr,$courseStr,$dateStr,,,"

        return "\$$content*${calculateChecksum(content)}"
    }

    fun generateGSA(
        fixType: Int = 3,
        satelliteIds: List<Int> = emptyList(),
        pdop: Double = 1.5,
        hdop: Double = 0.8,
        vdop: Double = 1.2
    ): String {
        val sb = StringBuilder("GPGSA,A,$fixType")
        // Pad exactly 12 satellite slots (blank if unused)
        for (i in 0 until 12) {
            if (i < satelliteIds.size) {
                sb.append(String.format(Locale.US, ",%02d", satelliteIds[i]))
            } else {
                sb.append(",")
            }
        }
        sb.append(String.format(Locale.US, ",%.1f,%.1f,%.1f", pdop, hdop, vdop))
        val content = sb.toString()
        return "\$$content*${calculateChecksum(content)}"
    }

    fun generateGNS(
        talker: String = "GN",
        lat: Double,
        lon: Double,
        alt: Double,
        satellitesCount: Int,
        accuracy: Float,
        mode: String = "AN"
    ): String {
        val now = Date()
        val timeStr = timeFormat.format(now)

        val latDeg = floor(abs(lat)).toInt()
        val latMin = (abs(lat) - latDeg) * 60.0
        val latStr = String.format(Locale.US, "%02d%07.4f", latDeg, latMin)
        val latDir = if (lat >= 0) "N" else "S"

        val lonDeg = floor(abs(lon)).toInt()
        val lonMin = (abs(lon) - lonDeg) * 60.0
        val lonStr = String.format(Locale.US, "%03d%07.4f", lonDeg, lonMin)
        val lonDir = if (lon >= 0) "E" else "W"

        val hdop = (accuracy / 5.0f).coerceIn(0.5f, 20.0f)
        val content = String.format(
            Locale.US,
            "%sGNS,%s,%s,%s,%s,%s,%s,%02d,%.1f,%.1f,0.0,,",
            talker.take(2).uppercase(Locale.US),
            timeStr,
            latStr,
            latDir,
            lonStr,
            lonDir,
            mode,
            satellitesCount.coerceIn(0, 99),
            hdop,
            alt
        )
        return "\$$content*${calculateChecksum(content)}"
    }

    fun generateVTG(
        bearing: Double,
        speedMPS: Double
    ): String {
        val speedKnots = speedMPS * 1.94384
        val speedKph = speedMPS * 3.6
        // $GPVTG,{course},T,,M,{knots},N,{kph},K*checksum
        val content = String.format(Locale.US,
            "GPVTG,%.1f,T,,M,%.1f,N,%.1f,K",
            bearing, speedKnots, speedKph
        )
        return "\$$content*${calculateChecksum(content)}"
    }

    fun generateGST(
        lat: Double,
        lon: Double,
        accuracy: Float
    ): String {
        val now = Date()
        val timeStr = timeFormat.format(now)
        // Pseudorange residual RMS derived from accuracy estimate
        val rms = (accuracy / 2.0f).coerceAtLeast(0.5f)
        // $GPGST,hhmmss.ss,{rms},{semi-major},{semi-minor},{orient},{latErr},{lonErr},{altErr}*cs
        val content = String.format(Locale.US,
            "GPGST,$timeStr,%.2f,%.2f,%.2f,%.1f,%.2f,%.2f,%.2f",
            rms, rms * 1.2, rms * 0.8, 0.0,
            accuracy / 3.0f, accuracy / 3.0f, accuracy / 2.0f
        )
        return "\$$content*${calculateChecksum(content)}"
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
