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

import java.util.Locale
import java.util.Random
import kotlin.math.floor

/** Produces a repeatable radio fingerprint for a small geographic cell. */
internal object RadioFingerprintGenerator {
    private val vendors = listOf(
        Vendor("TP-LINK", intArrayOf(0x50, 0xC7, 0xBF)),
        Vendor("HUAWEI", intArrayOf(0x48, 0x46, 0xFB)),
        Vendor("Xiaomi", intArrayOf(0x64, 0xCC, 0x2E)),
        Vendor("ZTE", intArrayOf(0x34, 0x4B, 0x50)),
        Vendor("Cisco", intArrayOf(0x00, 0x1B, 0x54)),
        Vendor("NETGEAR", intArrayOf(0xA0, 0x40, 0xA0)),
    )
    private val frequenciesMhz = intArrayOf(
        2412, 2437, 2462, 5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805,
    )

    fun generate(latitude: Double, longitude: Double, count: Int): List<GeneratedWifiProfile> {
        val random = Random(cellSeed(latitude, longitude))
        return List(count.coerceIn(0, 64)) { index ->
            val vendor = vendors[(random.nextInt(vendors.size) + index) % vendors.size]
            val suffix = IntArray(3) { random.nextInt(256) }
            val suffixLabel = "%02X%02X".format(Locale.US, suffix[1], suffix[2])
            GeneratedWifiProfile(
                ssid = "${vendor.name}_$suffixLabel",
                bssid = (vendor.oui + suffix).joinToString(":") { "%02x".format(Locale.US, it) },
                rssiDbm = -35 - random.nextInt(51),
                frequencyMhz = frequenciesMhz[random.nextInt(frequenciesMhz.size)],
                capabilities = "[WPA2-PSK-CCMP][ESS]",
            )
        }
    }

    private fun cellSeed(latitude: Double, longitude: Double): Long {
        val latCell = floor(latitude.coerceIn(-90.0, 90.0) * CELLS_PER_DEGREE).toLong()
        val lonCell = floor(longitude.coerceIn(-180.0, 180.0) * CELLS_PER_DEGREE).toLong()
        var hash = 0x4B1D5A77E2C91A3L
        hash = (hash xor latCell) * -7046029254386353131L
        hash = (hash xor lonCell) * -7046029254386353131L
        return hash
    }

    private data class Vendor(val name: String, val oui: IntArray)

    private const val CELLS_PER_DEGREE = 2_000.0
}

internal data class GeneratedWifiProfile(
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val capabilities: String,
)
