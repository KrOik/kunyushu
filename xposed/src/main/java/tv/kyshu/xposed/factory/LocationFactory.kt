package tv.kyshu.xposed.factory

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import tv.kyshu.xposed.utils.FakeLoc
import kotlin.random.Random

object LocationFactory {
    private const val INJECTED_EXTRA = "_inj"
    private val LEGACY_INJECTED_EXTRA = asciiName(0x5f, 0x70, 0x6f, 0x72, 0x74, 0x61, 0x6c, 0x5f, 0x69, 0x6e, 0x6a, 0x65, 0x63, 0x74, 0x65, 0x64)
    private val dynamics = CorrelatedLocationModel()

    fun isInjected(location: Location): Boolean =
        location.extras?.getBoolean(INJECTED_EXTRA, false) == true ||
            location.extras?.getBoolean(LEGACY_INJECTED_EXTRA, false) == true

    fun createInjectedLocation(originLocation: Location): Location {
        val location = Location(originLocation).apply {
            if (provider == null) provider = LocationManager.GPS_PROVIDER
        }
        val locationData = FakeLoc.locationData
        val baseAccuracy = if (locationData.accuracy != 0.0f) locationData.accuracy else originLocation.accuracy
        // Zero is a stationary profile, not a request to inherit the real speed.
        val baseSpeed = locationData.speed
        require(baseSpeed.isFinite() && baseSpeed >= 0.0) { "Invalid speed" }
        require(locationData.altitude.isFinite()) { "Invalid altitude" }
        require(locationData.bearing.isFinite()) { "Invalid bearing" }
        require(baseAccuracy.isFinite() && baseAccuracy >= 0f) { "Invalid accuracy" }
        val sampledAtNanos = SystemClock.elapsedRealtimeNanos()
        val motionNoise = locationData.motionNoise
        val sample = dynamics.sample(
            LocationDynamicsInput(
                nowMs = sampledAtNanos / 1_000_000L,
                baseLatitude = locationData.latitude,
                baseLongitude = locationData.longitude,
                baseAltitudeMeters = locationData.altitude,
                horizontalAccuracyMeters = baseAccuracy.toDouble(),
                baseSpeedMetersPerSecond = baseSpeed,
                speedAmplitudeMetersPerSecond = locationData.speedAmplitude,
                altitudeAmplitudeMeters = if (locationData.enableAltitudeJitter) {
                    motionNoise.altitudeDriftMeters.toDouble().coerceAtLeast(locationData.altitudeJitter.toDouble())
                } else {
                    0.0
                },
                noiseEnabled = motionNoise.enabled,
                positionRadiusMeters = motionNoise.horizontalRadiusMeters.toDouble(),
                accuracyDriftMeters = motionNoise.accuracyDriftMeters.toDouble(),
            )
        )
        location.latitude = sample.latitude
        location.longitude = sample.longitude
        location.altitude = sample.altitudeMeters
        location.accuracy = sample.horizontalAccuracyMeters.toFloat()
        location.speed = if (baseSpeed == 0.0) 0f else sample.speedMetersPerSecond.toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && originLocation.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond = (locationData.speedAmplitude / 3.0)
                .coerceIn(0.1, 20.0)
                .toFloat()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && originLocation.hasVerticalAccuracy()) {
            location.verticalAccuracyMeters = (sample.horizontalAccuracyMeters * 1.5)
                .coerceIn(1.0, 100.0)
                .toFloat()
        }

        val modBearing = normalizedBearing(locationData.bearing)
        location.bearing = modBearing.toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (originLocation.hasBearingAccuracy()) {
                location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                    .takeIf { it.isFinite() && it > 0.0f }
                    ?.coerceIn(1.0f, 45.0f)
                    ?: 3.0f
            }
        }

        if (location.extras == null) {
            location.extras = Bundle()
        }
        location.extras?.putBoolean(INJECTED_EXTRA, true)
        location.extras?.putDouble("latlon", location.latitude + location.longitude)
        location.extras?.putInt("satellites", Random.nextInt(8, 45))
        location.extras?.putInt("maxCn0", Random.nextInt(30, 50))
        location.extras?.putInt("meanCn0", Random.nextInt(20, 30))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // No geoid model at the simulated coordinates: do not invent an MSL value
            // or carry one from the real coordinates into the replacement.
            location.removeMslAltitude()
            location.removeMslAltitudeAccuracy()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock = !locationData.hideMock
        }

        // This is a newly generated sample, including when derived from a cached fix.
        location.time = System.currentTimeMillis().coerceAtLeast(1L)
        location.elapsedRealtimeNanos = sampledAtNanos.coerceAtLeast(1L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            location.removeElapsedRealtimeUncertaintyNanos()
        }

        return location
    }

    internal fun resetDynamicsForTest() = dynamics.reset()

    private fun normalizedBearing(value: Double): Double {
        var bearing = value % 360.0 + 0.0
        if (bearing < 0) bearing += 360.0
        return bearing
    }

    private fun asciiName(vararg bytes: Int): String {
        return ByteArray(bytes.size) { index -> bytes[index].toByte() }.toString(Charsets.UTF_8)
    }
}
