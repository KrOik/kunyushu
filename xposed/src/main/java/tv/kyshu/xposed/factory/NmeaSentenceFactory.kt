package tv.kyshu.xposed.factory

import moe.microbios.nmea.NMEA
import moe.microbios.nmea.NmeaValue
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import kotlin.math.abs

object NmeaSentenceFactory {
    fun rewrite(sentence: String): String? {
        runCatching {
            val nmea = NMEA.valueOf(sentence)
            return when (val value = nmea.value) {
                is NmeaValue.DTM -> null
                is NmeaValue.GGA -> rewriteFixSentence(value.latitude, value.longitude, value.fixQuality == 0) {
                    applyCoordinates(value)
                    value.toNmeaString()
                }
                is NmeaValue.GNS -> rewriteFixSentence(value.latitude, value.longitude, value.mode == "N") {
                    applyCoordinates(value)
                    value.toNmeaString()
                }
                is NmeaValue.GSA -> null
                is NmeaValue.GSV -> null
                is NmeaValue.RMC -> rewriteFixSentence(value.latitude, value.longitude, value.status == "V") {
                    applyCoordinates(value)
                    value.trackAngle = normalizedBearing(FakeLoc.bearing)
                    value.toNmeaString()
                }
                is NmeaValue.VTG -> {
                    val bearing = normalizedBearing(FakeLoc.bearing)
                    value.trueTrack = bearing
                    if (value.magneticTrack != null) {
                        value.magneticTrack = bearing
                    }
                    value.toNmeaString()
                }
            }
        }.onFailure {
            Logger.error("NMEA parse failed: ${it.message}")
            return null
        }
        return null
    }

    private fun rewriteFixSentence(
        latitude: Double?,
        longitude: Double?,
        invalidFix: Boolean,
        block: () -> String,
    ): String? {
        if (latitude == null || longitude == null || invalidFix) {
            return null
        }
        return block()
    }

    private fun applyCoordinates(value: NmeaValue.GGA) {
        value.latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
        value.longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"
        value.latitude = nmeaCoordinate(FakeLoc.latitude)
        value.longitude = nmeaCoordinate(FakeLoc.longitude)
    }

    private fun applyCoordinates(value: NmeaValue.GNS) {
        value.latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
        value.longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"
        value.latitude = nmeaCoordinate(FakeLoc.latitude)
        value.longitude = nmeaCoordinate(FakeLoc.longitude)
    }

    private fun applyCoordinates(value: NmeaValue.RMC) {
        value.latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
        value.longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"
        value.latitude = nmeaCoordinate(FakeLoc.latitude)
        value.longitude = nmeaCoordinate(FakeLoc.longitude)
    }

    private fun nmeaCoordinate(coordinate: Double): Double {
        val absolute = abs(coordinate)
        val degrees = absolute.toInt()
        val minutes = (absolute - degrees) * 60
        return degrees * 100.0 + minutes
    }

    private fun normalizedBearing(value: Double): Double {
        var bearing = value % 360.0 + 0.0
        if (bearing < 0) bearing += 360.0
        return bearing
    }
}
