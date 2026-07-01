package tv.kyshu.xposed.utils

// 载噪比范围，考虑不同轨道类型
private const val GEO_MIN_CN0 = 30.0f  // GEO卫星信号较强
private const val GEO_MAX_CN0 = 45.0f
private const val IGSO_MIN_CN0 = 25.0f
private const val IGSO_MAX_CN0 = 42.0f
private const val MEO_MIN_CN0 = 20.0f  // MEO卫星信号相对较弱
private const val MEO_MAX_CN0 = 40.0f

object GnssFlags {
    // 基本标志位
    const val SVID_FLAGS_NONE = 0
    const val SVID_FLAGS_HAS_EPHEMERIS_DATA = (1 shl 0)
    const val SVID_FLAGS_HAS_ALMANAC_DATA = (1 shl 1)
    const val SVID_FLAGS_USED_IN_FIX = (1 shl 2)
    const val SVID_FLAGS_HAS_CARRIER_FREQUENCY = (1 shl 3)
    const val SVID_FLAGS_HAS_BASEBAND_CN0 = (1 shl 4)

    // 位移宽度
    const val SVID_SHIFT_WIDTH = 12
    const val CONSTELLATION_TYPE_SHIFT_WIDTH = 8
    const val CONSTELLATION_TYPE_MASK = 0xf

    // 星座类型（与 Android GnssStatus.CONSTELLATION_ 常量对应）
    const val CONSTELLATION_GPS = 1
    const val CONSTELLATION_SBAS = 2
    const val CONSTELLATION_GLONASS = 3
    const val CONSTELLATION_QZSS = 4
    const val CONSTELLATION_BEIDOU = 5
    const val CONSTELLATION_GALILEO = 6
    const val CONSTELLATION_IRNSS = 7
}

sealed class OrbitType(val minCn0: Float, val maxCn0: Float, val elevationRange: ClosedRange<Float>) {
    object GEO : OrbitType(GEO_MIN_CN0, GEO_MAX_CN0, 35f..50f)
    object IGSO : OrbitType(IGSO_MIN_CN0, IGSO_MAX_CN0, 20f..60f)
    object MEO : OrbitType(MEO_MIN_CN0, MEO_MAX_CN0, 0f..90f)
}

data class GnssSatellite(
    val prn: Int,
    val type: OrbitType,
    val constellation: Int
)

val satelliteList: List<GnssSatellite> = buildList {
    // BDS Satellites
    add(GnssSatellite(1, OrbitType.GEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(2, OrbitType.GEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(3, OrbitType.GEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(4, OrbitType.GEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(5, OrbitType.GEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(6, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(7, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(8, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(9, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(10, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(11, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(12, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(13, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(14, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(16, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(19, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(20, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(21, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(22, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(23, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(24, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(25, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(26, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(27, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(28, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(29, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(30, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(31, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(32, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(33, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(34, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(35, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(36, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(37, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(38, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(39, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(40, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(41, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(42, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(43, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(44, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(45, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(46, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(56, OrbitType.IGSO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(57, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(58, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(59, OrbitType.GEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(60, OrbitType.GEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(61, OrbitType.GEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(62, OrbitType.GEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(48, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(50, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(47, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))
    add(GnssSatellite(49, OrbitType.MEO, GnssFlags.CONSTELLATION_BEIDOU))

    // GPS Satellites (1-32)
    for (i in 1..32) {
        add(GnssSatellite(i, OrbitType.MEO, GnssFlags.CONSTELLATION_GPS))
    }

    // GLONASS, Galileo and QZSS keep NMEA/GnssStatus constellation claims aligned.
    for (i in 1..24) {
        add(GnssSatellite(i, OrbitType.MEO, GnssFlags.CONSTELLATION_GLONASS))
    }
    for (i in 1..36) {
        add(GnssSatellite(i, OrbitType.MEO, GnssFlags.CONSTELLATION_GALILEO))
    }
    for (i in 193..200) {
        add(GnssSatellite(i, OrbitType.IGSO, GnssFlags.CONSTELLATION_QZSS))
    }
    // IRNSS (Indian Regional Navigation Satellite System, PRN 201-214)
    for (i in 201..214) {
        add(GnssSatellite(i, OrbitType.IGSO, GnssFlags.CONSTELLATION_IRNSS))
    }
    // SBAS (geostationary WAAS/EGNOS/GAGAN, PRN 120-158)
    for (i in 120..158) {
        add(GnssSatellite(i, OrbitType.GEO, GnssFlags.CONSTELLATION_SBAS))
    }
}
