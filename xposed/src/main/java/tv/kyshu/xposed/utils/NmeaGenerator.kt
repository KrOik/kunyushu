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
        accuracy: Float
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

        val fixQuality = 1 // GPS fix
        
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

    private fun calculateChecksum(sentence: String): String {
        var sum = 0
        for (char in sentence) {
            if (char == '$' || char == '*') continue
            sum = sum xor char.code
        }
        return String.format(Locale.US, "%02X", sum)
    }
}
