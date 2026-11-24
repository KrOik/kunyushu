/*
 * Copyright (C) 2025 The KunYuShu Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package tv.kyshu.xposed.factory

import java.util.Random
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A time-correlated, bounded noise source for successive location samples. */
internal class CorrelatedLocationModel(
    private val random: Random = Random(),
) {
    private var initialized = false
    private var lastSampleAtMs = 0L
    private var lastBaseLatitude = 0.0
    private var lastBaseLongitude = 0.0
    private var northMeters = 0.0
    private var eastMeters = 0.0
    private var altitudeOffsetMeters = 0.0
    private var accuracyOffsetMeters = 0.0
    private var speedOffsetMetersPerSecond = 0.0

    @Synchronized
    fun sample(input: LocationDynamicsInput): LocationDynamicsSample {
        require(input.baseLatitude.isFinite() && input.baseLatitude in -90.0..90.0)
        require(input.baseLongitude.isFinite() && input.baseLongitude in -180.0..180.0)

        if (!input.noiseEnabled) {
            reset()
            return LocationDynamicsSample(
                latitude = input.baseLatitude,
                longitude = input.baseLongitude,
                altitudeMeters = input.baseAltitudeMeters,
                horizontalAccuracyMeters = input.horizontalAccuracyMeters.coerceAtLeast(MIN_ACCURACY_METERS),
                speedMetersPerSecond = input.baseSpeedMetersPerSecond.coerceAtLeast(0.0),
            )
        }

        val discontinuity = initialized && (
            input.nowMs < lastSampleAtMs ||
                input.nowMs - lastSampleAtMs > RESET_AFTER_MS ||
                distanceMeters(
                    lastBaseLatitude,
                    lastBaseLongitude,
                    input.baseLatitude,
                    input.baseLongitude,
                ) > RESET_AFTER_JUMP_METERS
            )
        if (!initialized || discontinuity) resetState(input)

        val safeAccuracy = input.horizontalAccuracyMeters
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: MIN_ACCURACY_METERS
        val safeAltitudeAmplitude = input.altitudeAmplitudeMeters
            .takeIf { it.isFinite() && it > 0.0 }
            ?: 0.0
        val safeSpeedAmplitude = input.speedAmplitudeMetersPerSecond
            .takeIf { it.isFinite() && it > 0.0 }
            ?: 0.0
        val dtSeconds = ((input.nowMs - lastSampleAtMs) / 1000.0).coerceIn(0.0, MAX_STEP_SECONDS)
        if (dtSeconds >= MIN_EVOLUTION_SECONDS) {
            val positionRadius = input.positionRadiusMeters?.takeIf { it.isFinite() && it >= 0.0 }
                ?: (safeAccuracy / 15.0)
                .coerceAtMost(MAX_POSITION_RADIUS_METERS)
            val positionStdDev = positionRadius / 3.0
            northMeters = evolve(northMeters, positionStdDev, POSITION_REVERSION, dtSeconds)
            eastMeters = evolve(eastMeters, positionStdDev, POSITION_REVERSION, dtSeconds)
            val radius = hypot(northMeters, eastMeters)
            if (radius > positionRadius && radius > 0.0) {
                val scale = positionRadius / radius
                northMeters *= scale
                eastMeters *= scale
            }

            altitudeOffsetMeters = evolve(
                altitudeOffsetMeters,
                safeAltitudeAmplitude / 3.0,
                ALTITUDE_REVERSION,
                dtSeconds,
            ).coerceIn(-safeAltitudeAmplitude, safeAltitudeAmplitude)

            accuracyOffsetMeters = evolve(
                accuracyOffsetMeters,
                input.accuracyDriftMeters?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: max(0.1, safeAccuracy * 0.04),
                ACCURACY_REVERSION,
                dtSeconds,
            )

            speedOffsetMetersPerSecond = evolve(
                speedOffsetMetersPerSecond,
                safeSpeedAmplitude / 3.0,
                SPEED_REVERSION,
                dtSeconds,
            ).coerceIn(-safeSpeedAmplitude, safeSpeedAmplitude)
        }

        lastSampleAtMs = input.nowMs
        lastBaseLatitude = input.baseLatitude
        lastBaseLongitude = input.baseLongitude

        val latitude = input.baseLatitude + Math.toDegrees(northMeters / EARTH_RADIUS_METERS)
        val longitudeScale = cos(Math.toRadians(input.baseLatitude)).coerceAtLeast(MIN_LONGITUDE_SCALE)
        val longitude = input.baseLongitude +
            Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * longitudeScale))
        val baseAccuracy = safeAccuracy.coerceAtLeast(MIN_ACCURACY_METERS)
        val baseSpeed = input.baseSpeedMetersPerSecond.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

        return LocationDynamicsSample(
            latitude = latitude.coerceIn(-90.0, 90.0),
            longitude = normalizeLongitude(longitude),
            altitudeMeters = input.baseAltitudeMeters + altitudeOffsetMeters,
            horizontalAccuracyMeters = (baseAccuracy + accuracyOffsetMeters)
                .coerceIn(MIN_ACCURACY_METERS, max(MIN_ACCURACY_METERS, baseAccuracy * 1.5)),
            speedMetersPerSecond = (baseSpeed + speedOffsetMetersPerSecond)
                .coerceAtLeast(0.0),
        )
    }

    @Synchronized
    fun reset() {
        initialized = false
        lastSampleAtMs = 0L
        northMeters = 0.0
        eastMeters = 0.0
        altitudeOffsetMeters = 0.0
        accuracyOffsetMeters = 0.0
        speedOffsetMetersPerSecond = 0.0
    }

    private fun resetState(input: LocationDynamicsInput) {
        initialized = true
        lastSampleAtMs = input.nowMs
        lastBaseLatitude = input.baseLatitude
        lastBaseLongitude = input.baseLongitude
        northMeters = 0.0
        eastMeters = 0.0
        altitudeOffsetMeters = 0.0
        accuracyOffsetMeters = 0.0
        speedOffsetMetersPerSecond = 0.0
    }

    private fun evolve(current: Double, stationaryStdDev: Double, reversion: Double, dt: Double): Double {
        if (stationaryStdDev <= 0.0 || dt <= 0.0) return 0.0
        val persistence = exp(-reversion * dt)
        val innovationScale = stationaryStdDev * sqrt((1.0 - persistence * persistence).coerceAtLeast(0.0))
        return current * persistence + innovationScale * random.nextGaussian()
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = phi2 - phi1
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2.0) * sin(deltaPhi / 2.0) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2.0) * sin(deltaLambda / 2.0)
        return 2.0 * EARTH_RADIUS_METERS * kotlin.math.atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
    }

    private fun normalizeLongitude(value: Double): Double = if (value in -180.0..180.0) {
        value
    } else {
        ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_378_137.0
        const val MIN_LONGITUDE_SCALE = 1e-6
        const val MIN_ACCURACY_METERS = 0.5
        const val MAX_POSITION_RADIUS_METERS = 8.0
        const val RESET_AFTER_MS = 30_000L
        const val RESET_AFTER_JUMP_METERS = 50.0
        const val MAX_STEP_SECONDS = 5.0
        const val MIN_EVOLUTION_SECONDS = 0.05
        const val POSITION_REVERSION = 0.18
        const val ALTITUDE_REVERSION = 0.08
        const val ACCURACY_REVERSION = 0.04
        const val SPEED_REVERSION = 0.5
    }
}

internal data class LocationDynamicsInput(
    val nowMs: Long,
    val baseLatitude: Double,
    val baseLongitude: Double,
    val baseAltitudeMeters: Double,
    val horizontalAccuracyMeters: Double,
    val baseSpeedMetersPerSecond: Double,
    val speedAmplitudeMetersPerSecond: Double,
    val altitudeAmplitudeMeters: Double,
    val noiseEnabled: Boolean = true,
    val positionRadiusMeters: Double? = null,
    val accuracyDriftMeters: Double? = null,
)

internal data class LocationDynamicsSample(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val horizontalAccuracyMeters: Double,
    val speedMetersPerSecond: Double,
)
