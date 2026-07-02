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

enum class GnssType {
    GPS, BDS, GLONASS, GALILEO, QZSS
}

data class Ephemeris(
    val type: GnssType,
    val prn: Int,
    val week: Int,      // GPS Week
    val toc: Double,    // Time of Clock (seconds within week)
    val toe: Double,    // Time of Ephemeris (seconds within week)

    // Clock corrections
    val a0: Double, // Bias (s)
    val a1: Double, // Drift (s/s)
    val a2: Double, // Drift Rate (s/s^2)

    // Orbit parameters
    val iode: Double,   // Issue of Data, Ephemeris
    val crs: Double,    // Amplitude of the Sine Harmonic Correction Term to the Orbit Radius (m)
    val deltaN: Double, // Mean Motion Difference from Computed Value (rad/s)
    val M0: Double,     // Mean Anomaly at Reference Time (rad)
    val cuc: Double,    // Amplitude of the Cosine Harmonic Correction Term to the Argument of Latitude (rad)
    val e: Double,      // Eccentricity
    val cus: Double,    // Amplitude of the Sine Harmonic Correction Term to the Argument of Latitude (rad)
    val sqrtA: Double,  // Square Root of the Semi-Major Axis (sqrt(m))
    val cic: Double,    // Amplitude of the Cosine Harmonic Correction Term to the Angle of Inclination (rad)
    val Omega0: Double, // Longitude of Ascending Node of Orbit Plane at Weekly Epoch (rad)
    val cis: Double,    // Amplitude of the Sine Harmonic Correction Term to the Angle of Inclination (rad)
    val i0: Double,     // Inclination Angle at Reference Time (rad)
    val crc: Double,    // Amplitude of the Cosine Harmonic Correction Term to the Orbit Radius (m)
    val omega: Double,  // Argument of Perigee (rad)
    val OmegaDot: Double, // Rate of Right Ascension (rad/s)
    val iDot: Double    // Rate of Inclination Angle (rad/s)
)

data class EcefPosition(
    val x: Double,
    val y: Double,
    val z: Double
)

data class TopocentricPosition(
    val azimuth: Double,   // Degrees
    val elevation: Double, // Degrees
    val distance: Double   // Meters
)
