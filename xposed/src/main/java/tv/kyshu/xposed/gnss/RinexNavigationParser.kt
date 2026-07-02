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

package tv.kyshu.xposed.gnss

import tv.kyshu.xposed.utils.Logger
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

class RinexNavigationParser {
    fun parse(file: File): List<Ephemeris> {
        val ephemerisList = ArrayList<Ephemeris>()
        if (!file.exists()) {
            Logger.warn("[RinexParser] file not found: ${file.absolutePath}")
            return ephemerisList
        }

        Logger.debug("[RinexParser] starting parse of ${file.absolutePath}")
        var parsedCount = 0

        BufferedReader(FileReader(file)).use { reader ->
            var line = reader.readLine()
            var isHeader = true
            var version = 3.0

            while (line != null) {
                if (isHeader) {
                    if (line.contains("RINEX VERSION / TYPE")) {
                        version = line.substring(0, 9).trim().toDoubleOrNull() ?: 3.0
                        Logger.debug("[RinexParser] version=$version")
                    }
                    if (line.contains("END OF HEADER")) {
                        isHeader = false
                        Logger.debug("[RinexParser] header parsed")
                    }
                    line = reader.readLine()
                    continue
                }

                if (line.isBlank()) {
                    line = reader.readLine()
                    continue
                }

                var type: GnssType? = null
                var prn = 0
                val l1Offset: Int
                val otherOffset: Int

                if (version >= 3.0) {
                    val satId = line.substring(0, 3)
                    type = when (satId[0]) {
                        'G' -> GnssType.GPS
                        'C' -> GnssType.BDS
                        'E' -> GnssType.GALILEO
                        'J' -> GnssType.QZSS
                        'R' -> GnssType.GLONASS
                        else -> null
                    }
                    if (type != null) {
                        prn = satId.substring(1).trim().toInt()
                    }
                    l1Offset = 23
                    otherOffset = 4
                } else {
                    // RINEX 2
                    try {
                        val prnStr = line.substring(0, 2).trim()
                        val prnInt = prnStr.toIntOrNull()
                        if (prnInt != null && prnInt in 1..32) {
                            type = GnssType.GPS
                            prn = prnInt
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                    l1Offset = 22
                    otherOffset = 3
                }

                if (type != null) {
                    try {
                        // Line 1: PRN / EPOCH / SV CLK
                        val a0 = parseDouble(line, l1Offset, 19)
                        val a1 = parseDouble(line, l1Offset + 19, 19)
                        val a2 = parseDouble(line, l1Offset + 38, 19)

                        // Line 2: BROADCAST ORBIT 1
                        val l2 = reader.readLine() ?: break
                        val iode = parseDouble(l2, otherOffset, 19)
                        val crs = parseDouble(l2, otherOffset + 19, 19)
                        val deltaN = parseDouble(l2, otherOffset + 38, 19)
                        val m0 = parseDouble(l2, otherOffset + 57, 19)

                        // Line 3: BROADCAST ORBIT 2
                        val l3 = reader.readLine() ?: break
                        val cuc = parseDouble(l3, otherOffset, 19)
                        val e = parseDouble(l3, otherOffset + 19, 19)
                        val cus = parseDouble(l3, otherOffset + 38, 19)
                        val sqrtA = parseDouble(l3, otherOffset + 57, 19)

                        // Line 4: BROADCAST ORBIT 3
                        val l4 = reader.readLine() ?: break
                        val toe = parseDouble(l4, otherOffset, 19)
                        val cic = parseDouble(l4, otherOffset + 19, 19)
                        val omega0 = parseDouble(l4, otherOffset + 38, 19)
                        val cis = parseDouble(l4, otherOffset + 57, 19)

                        // Line 5: BROADCAST ORBIT 4
                        val l5 = reader.readLine() ?: break
                        val i0 = parseDouble(l5, otherOffset, 19)
                        val crc = parseDouble(l5, otherOffset + 19, 19)
                        val omega = parseDouble(l5, otherOffset + 38, 19)
                        val omegaDot = parseDouble(l5, otherOffset + 57, 19)

                        // Line 6: BROADCAST ORBIT 5
                        val l6 = reader.readLine() ?: break
                        val iDot = parseDouble(l6, otherOffset, 19)
                        val codesL2 = parseDouble(l6, otherOffset + 19, 19)
                        val gpsWeek = parseDouble(l6, otherOffset + 38, 19)
                        val l2PFlag = parseDouble(l6, otherOffset + 57, 19)

                        // Line 7: SV Accuracy, SV Health, TGD, IODC
                        val l7 = reader.readLine() ?: break

                        // Line 8: Transmission time, Fit Interval
                        val l8 = reader.readLine() ?: break

                        val eph = Ephemeris(
                            type = type!!,
                            prn = prn,
                            week = gpsWeek.toInt(),
                            toc = 0.0,
                            toe = toe,
                            a0 = a0, a1 = a1, a2 = a2,
                            iode = iode, crs = crs, deltaN = deltaN, M0 = m0,
                            cuc = cuc, e = e, cus = cus, sqrtA = sqrtA,
                            cic = cic, Omega0 = omega0, cis = cis, i0 = i0,
                            crc = crc, omega = omega, OmegaDot = omegaDot, iDot = iDot
                        )
                        ephemerisList.add(eph)
                        parsedCount++
                    } catch (e: Exception) {
                        Logger.warn("[RinexParser] failed to parse record line", e)
                    }
                } else {
                    // Skip this record (read 7 more lines for format)
                    for (i in 0 until 7) {
                        reader.readLine()
                    }
                }

                line = reader.readLine()
            }
        }
        Logger.debug("[RinexParser] parsing completed, records=${ephemerisList.size}")
        return ephemerisList
    }

    private fun parseDouble(line: String, start: Int, length: Int): Double {
        if (start >= line.length) return 0.0
        val end = (start + length).coerceAtMost(line.length)
        // RINEX uses 'D' or 'E' for exponent
        val sub = line.substring(start, end).replace('D', 'E').trim()
        if (sub.isEmpty()) return 0.0
        return sub.toDoubleOrNull() ?: 0.0
    }
}
