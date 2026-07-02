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

import kotlin.math.*

object GnssMath {
    // WGS-84 Constants
    private const val MU = 3.986005e14 // Earth's gravitational constant (m^3/s^2)
    private const val OMEGA_E_DOT = 7.2921151467e-5 // Earth's rotation rate (rad/s)
    private const val C = 2.99792458e8 // Speed of light (m/s)

    // WGS-84 ellipsoid parameters
    private const val SEMI_MAJOR_AXIS = 6378137.0 // m
    private const val FLATTENING = 1.0 / 298.257223563
    private const val E2 = (2.0 - FLATTENING) * FLATTENING // first eccentricity squared

    fun calculateSatellitePosition(eph: Ephemeris, transmitTime: Double): EcefPosition {
        // transmitTime is in seconds of GPS week

        // 1. Time from ephemeris reference epoch
        var tk = transmitTime - eph.toe
        // Handle week crossover
        if (tk > 302400.0) tk -= 604800.0
        else if (tk < -302400.0) tk += 604800.0

        // 2. Mean anomaly
        val Mk = eph.M0 + (sqrt(MU) / (eph.sqrtA.pow(3)) + eph.deltaN) * tk

        // 3. Eccentric anomaly (Kepler's Equation Iteration)
        var Ek = Mk
        var delta: Double
        for (i in 0..10) {
            delta = Ek - eph.e * sin(Ek) - Mk
            Ek -= delta / (1.0 - eph.e * cos(Ek))
            if (abs(delta) < 1e-12) break
        }

        // 4. True anomaly
        val sinNu = sqrt(1.0 - eph.e * eph.e) * sin(Ek) / (1.0 - eph.e * cos(Ek))
        val cosNu = (cos(Ek) - eph.e) / (1.0 - eph.e * cos(Ek))
        val nu = atan2(sinNu, cosNu)

        // 5. Argument of latitude
        val phi = nu + eph.omega

        // 6. Argument of latitude correction
        val du = eph.cus * sin(2 * phi) + eph.cuc * cos(2 * phi)
        val dr = eph.crs * sin(2 * phi) + eph.crc * cos(2 * phi)
        val di = eph.cis * sin(2 * phi) + eph.cic * cos(2 * phi)

        // 7. Corrected argument of latitude, radius, inclination
        val u = phi + du
        val r = eph.sqrtA.pow(2) * (1.0 - eph.e * cos(Ek)) + dr
        val i = eph.i0 + eph.iDot * tk + di

        // 8. Positions in orbital plane
        val xPrime = r * cos(u)
        val yPrime = r * sin(u)

        // 9. Corrected longitude of ascending node
        val omega = eph.Omega0 + (eph.OmegaDot - OMEGA_E_DOT) * tk - OMEGA_E_DOT * eph.toe

        // 10. Earth-fixed coordinates
        val x = xPrime * cos(omega) - yPrime * cos(i) * sin(omega)
        val y = xPrime * sin(omega) + yPrime * cos(i) * cos(omega)
        val z = yPrime * sin(i)

        return EcefPosition(x, y, z)
    }

    fun ecefToTopocentric(
        satPos: EcefPosition,
        observerLat: Double,
        observerLon: Double,
        observerAlt: Double
    ): TopocentricPosition {
        val (observerX, observerY, observerZ) = geodeticToEcef(observerLat, observerLon, observerAlt)

        // Vector from observer to satellite
        val dx = satPos.x - observerX
        val dy = satPos.y - observerY
        val dz = satPos.z - observerZ

        // Convert to topocentric coordinates (ENU)
        val latRad = Math.toRadians(observerLat)
        val lonRad = Math.toRadians(observerLon)

        // East, North, Up components
        val east = -sin(lonRad) * dx + cos(lonRad) * dy
        val north = -sin(latRad) * cos(lonRad) * dx - sin(latRad) * sin(lonRad) * dy + cos(latRad) * dz
        val up = cos(latRad) * cos(lonRad) * dx + cos(latRad) * sin(lonRad) * dy + sin(latRad) * dz

        val distance = sqrt(east * east + north * north + up * up)
        if (distance < 1.0) return TopocentricPosition(0.0, 0.0, distance)

        val azimuth = (Math.toDegrees(atan2(east, north)) + 360) % 360
        val elevation = Math.toDegrees(asin(up / distance))

        return TopocentricPosition(azimuth, elevation, distance)
    }

    private fun geodeticToEcef(lat: Double, lon: Double, alt: Double): EcefPosition {
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)

        val N = SEMI_MAJOR_AXIS / sqrt(1.0 - E2 * sinLat * sinLat)
        val x = (N + alt) * cosLat * cos(lonRad)
        val y = (N + alt) * cosLat * sin(lonRad)
        val z = (N * (1.0 - E2) + alt) * sinLat

        return EcefPosition(x, y, z)
    }
}
