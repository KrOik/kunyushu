package tv.kyshu.xposed.factory

import tv.kyshu.xposed.utils.GnssFlags
import kotlin.random.Random

data class GnssSatelliteStatus(
    val svid: Int,
    val constellation: Int,
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val carrierFrequencyMhz: Float,
    val hasEphemeris: Boolean,
    val hasAlmanac: Boolean,
    val usedInFix: Boolean,
    val hasCarrierFrequency: Boolean = true,
    val hasBasebandCn0: Boolean = true,
)

data class MockGnssData(
    val svCount: Int,
    val svidWithFlags: IntArray,
    val cn0s: FloatArray,
    val elevations: FloatArray,
    val azimuths: FloatArray,
    val carrierFreqs: FloatArray,
    val basebandCn0s: FloatArray,
)

object GnssStatusFactory {
    fun createData(
        satellites: List<GnssSatelliteStatus>,
        basebandCn0For: (index: Int, cn0: Float) -> Float = { _, cn0 -> cn0 - Random.nextFloatIn(2f, 5f) },
    ): MockGnssData {
        val svCount = satellites.size
        return MockGnssData(
            svCount = svCount,
            svidWithFlags = IntArray(svCount),
            cn0s = FloatArray(svCount),
            elevations = FloatArray(svCount),
            azimuths = FloatArray(svCount),
            carrierFreqs = FloatArray(svCount),
            basebandCn0s = FloatArray(svCount),
        ).apply {
            satellites.forEachIndexed { index, satellite ->
                svidWithFlags[index] = encodeSvidWithFlags(satellite)
                cn0s[index] = satellite.cn0
                elevations[index] = satellite.elevation
                azimuths[index] = satellite.azimuth
                carrierFreqs[index] = satellite.carrierFrequencyMhz * 1e6f
                basebandCn0s[index] = basebandCn0For(index, satellite.cn0)
            }
        }
    }

    fun applyLegacyCallbackArgs(args: Array<Any?>, data: MockGnssData) {
        args[0] = data.svCount
        args[1] = data.svidWithFlags
        args[2] = data.cn0s
        args[3] = data.elevations
        args[4] = data.azimuths
        if (args.size > 5) {
            args[5] = data.carrierFreqs
        }
        if (args.size > 6) {
            args[6] = data.basebandCn0s
        }
    }

    fun createStatusFromPlaceholder(currentStatus: Any, data: MockGnssData): Any? =
        createStatus(currentStatus.javaClass, data)

    fun createStatus(statusClass: Class<*>, data: MockGnssData): Any? =
        runCatching {
            val constructor = statusClass.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 7
            } ?: return@runCatching null
            if (!constructor.isAccessible) constructor.isAccessible = true
            constructor.newInstance(
                data.svCount,
                data.svidWithFlags,
                data.cn0s,
                data.elevations,
                data.azimuths,
                data.carrierFreqs,
                data.basebandCn0s,
            )
        }.getOrNull()

    private fun encodeSvidWithFlags(satellite: GnssSatelliteStatus): Int {
        var flags = GnssFlags.SVID_FLAGS_NONE
        if (satellite.hasEphemeris) flags = flags or GnssFlags.SVID_FLAGS_HAS_EPHEMERIS_DATA
        if (satellite.hasAlmanac) flags = flags or GnssFlags.SVID_FLAGS_HAS_ALMANAC_DATA
        if (satellite.usedInFix) flags = flags or GnssFlags.SVID_FLAGS_USED_IN_FIX
        if (satellite.hasCarrierFrequency) flags = flags or GnssFlags.SVID_FLAGS_HAS_CARRIER_FREQUENCY
        if (satellite.hasBasebandCn0) flags = flags or GnssFlags.SVID_FLAGS_HAS_BASEBAND_CN0

        return (satellite.svid shl GnssFlags.SVID_SHIFT_WIDTH) or
            ((satellite.constellation and GnssFlags.CONSTELLATION_TYPE_MASK) shl
                GnssFlags.CONSTELLATION_TYPE_SHIFT_WIDTH) or
            flags
    }

    private fun Random.nextFloatIn(min: Float, max: Float): Float =
        nextFloat() * (max - min) + min
}
