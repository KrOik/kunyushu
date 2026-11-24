package tv.kyshu.xposed.factory

import android.os.Bundle
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoGsm
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthWcdma
import android.telephony.NeighboringCellInfo
import tv.kyshu.xposed.config.CellRat
import tv.kyshu.xposed.config.CellTowerProfile
import kotlin.random.Random

object CellInfoFactory {
    private const val UNAVAILABLE = Int.MAX_VALUE
    private const val UNAVAILABLE_LONG = Long.MAX_VALUE

    fun createCdmaIdentity(latitude: Double, longitude: Double): CellIdentityCdma =
        CellIdentityCdma::class.java.declaredConstructor(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            String::class.java,
            String::class.java,
        ).newInstance(
            Int.MAX_VALUE,
            Int.MAX_VALUE,
            Int.MAX_VALUE,
            (longitude * 14400.0).toInt(),
            (latitude * 14400.0).toInt(),
            null,
            null,
        )

    fun createCdmaInfo(
        latitude: Double,
        longitude: Double,
        timestampNanos: Long = System.nanoTime(),
    ): CellInfoCdma =
        runCatching {
            CellInfoCdma::class.java.declaredConstructor().newInstance().also {
                it.invokeMethod("setRegistered", true)
                it.invokeMethod("setTimeStamp", timestampNanos)
                it.invokeMethod("setCellConnectionStatus", 0)
            }
        }.getOrElse {
            CellInfoCdma::class.java.declaredConstructor(
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!,
                CellIdentityCdma::class.java,
                CellSignalStrengthCdma::class.java,
            ).newInstance(
                0,
                true,
                timestampNanos,
                createCdmaIdentity(latitude, longitude),
                CellSignalStrengthCdma::class.java.declaredConstructor().newInstance(),
            )
        }

    fun createCdmaInfoList(latitude: Double, longitude: Double): List<CellInfo> =
        listOf(createCdmaInfo(latitude, longitude))

    fun createFromProfiles(
        profiles: List<CellTowerProfile>,
        timestampNanos: Long = System.nanoTime(),
    ): List<CellInfo> =
        profiles.mapNotNull { profile -> createFromProfileOrNull(profile, timestampNanos) }

    /** Profile replay is transactional: one unsupported tower rejects the whole callback. */
    fun createFromProfilesStrict(
        profiles: List<CellTowerProfile>,
        timestampNanos: Long = System.nanoTime(),
    ): List<CellInfo> = profiles.mapIndexed { index, profile ->
        createFromProfileOrNull(profile, timestampNanos)
            ?: throw IllegalStateException("CellInfo construction failed at index $index")
    }

    fun createFromProfileOrNull(
        profile: CellTowerProfile,
        timestampNanos: Long = System.nanoTime(),
    ): CellInfo? =
        when (profile.rat) {
            CellRat.LTE -> runCatching { createLteInfo(profile, timestampNanos) }.getOrNull()
            CellRat.NR -> runCatching { createNrInfo(profile, timestampNanos) }.getOrNull()
            CellRat.CDMA -> runCatching { createCdmaInfo(0.0, 0.0, timestampNanos) }.getOrNull()
            CellRat.GSM -> runCatching { createGsmInfo(profile, timestampNanos) }.getOrNull()
            CellRat.WCDMA -> runCatching { createWcdmaInfo(profile, timestampNanos) }.getOrNull()
            CellRat.TD_SCDMA -> null
        }

    fun createLteIdentity(profile: CellTowerProfile): CellIdentityLte =
        CellIdentityLte::class.java.declaredConstructor(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            IntArray::class.java,
            Int::class.javaPrimitiveType!!,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Collection::class.java,
            Class.forName("android.telephony.ClosedSubscriberGroupInfo"),
        ).newInstance(
            profile.cellId?.toInt() ?: UNAVAILABLE,
            profile.pciOrPsc ?: UNAVAILABLE,
            profile.tacOrLac ?: UNAVAILABLE,
            profile.arfcn ?: UNAVAILABLE,
            profile.bands.toIntArray(),
            profile.bandwidthKhz ?: UNAVAILABLE,
            profile.mcc.ifBlank { null },
            profile.mnc.ifBlank { null },
            null,
            null,
            profile.additionalPlmns,
            null,
        )

    fun createNrIdentity(profile: CellTowerProfile): CellIdentityNr =
        CellIdentityNr::class.java.declaredConstructor(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            IntArray::class.java,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType!!,
            String::class.java,
            String::class.java,
            Collection::class.java,
        ).newInstance(
            profile.pciOrPsc ?: UNAVAILABLE,
            profile.tacOrLac ?: UNAVAILABLE,
            profile.arfcn ?: UNAVAILABLE,
            profile.bands.toIntArray(),
            profile.mcc.ifBlank { null },
            profile.mnc.ifBlank { null },
            profile.cellId ?: UNAVAILABLE_LONG,
            null,
            null,
            profile.additionalPlmns,
        )

    fun createGsmIdentity(profile: CellTowerProfile): CellIdentityGsm =
        CellIdentityGsm::class.java.declaredConstructor(
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            String::class.java, String::class.java, String::class.java, String::class.java,
            Collection::class.java,
        ).newInstance(
            profile.tacOrLac ?: UNAVAILABLE, profile.cellId?.toInt() ?: UNAVAILABLE,
            profile.arfcn ?: UNAVAILABLE, profile.pciOrPsc ?: UNAVAILABLE,
            profile.mcc.ifBlank { null }, profile.mnc.ifBlank { null }, null, null,
            profile.additionalPlmns,
        )

    fun createWcdmaIdentity(profile: CellTowerProfile): CellIdentityWcdma =
        CellIdentityWcdma::class.java.declaredConstructor(
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            String::class.java, String::class.java, String::class.java, String::class.java,
            Collection::class.java, Class.forName("android.telephony.ClosedSubscriberGroupInfo"),
        ).newInstance(
            profile.tacOrLac ?: UNAVAILABLE, profile.cellId?.toInt() ?: UNAVAILABLE,
            profile.pciOrPsc ?: UNAVAILABLE, profile.arfcn ?: UNAVAILABLE,
            profile.mcc.ifBlank { null }, profile.mnc.ifBlank { null }, null, null,
            profile.additionalPlmns, null,
        )

    fun createLteSignalStrength(profile: CellTowerProfile): CellSignalStrengthLte =
        CellSignalStrengthLte::class.java.declaredConstructor(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ).newInstance(
            profile.rssiDbm.takeIf { it in -113..-51 } ?: UNAVAILABLE,
            profile.rsrpDbm ?: profile.rssiDbm,
            profile.rsrqDb ?: -10,
            profile.rssnrDb ?: 10,
            profile.cqiTableIndex ?: UNAVAILABLE,
            profile.cqi ?: UNAVAILABLE,
            profile.timingAdvance ?: UNAVAILABLE,
        )

    fun createNrSignalStrength(profile: CellTowerProfile): CellSignalStrengthNr {
        val cqiReport = profile.csiCqiReport.map { it.toByte() }
        val csiRsrp = profile.csiRsrpDbm ?: profile.rsrpDbm ?: profile.rssiDbm
        val csiRsrq = profile.csiRsrqDb ?: profile.rsrqDb ?: -10
        val csiSinr = profile.csiSinrDb ?: profile.rssnrDb ?: 10
        val cqiTableIndex = profile.csiCqiTableIndex ?: UNAVAILABLE
        val ssRsrp = profile.ssRsrpDbm ?: profile.rsrpDbm ?: profile.rssiDbm
        val ssRsrq = profile.ssRsrqDb ?: profile.rsrqDb ?: -10
        val ssSinr = profile.ssSinrDb ?: profile.rssnrDb ?: 10
        return runCatching {
            CellSignalStrengthNr::class.java.declaredConstructor(
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                List::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ).newInstance(
                csiRsrp,
                csiRsrq,
                csiSinr,
                cqiTableIndex,
                cqiReport,
                ssRsrp,
                ssRsrq,
                ssSinr,
                profile.timingAdvanceMicros ?: profile.timingAdvance ?: UNAVAILABLE,
            )
        }.getOrElse {
            CellSignalStrengthNr::class.java.declaredConstructor(
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                List::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ).newInstance(
                csiRsrp,
                csiRsrq,
                csiSinr,
                cqiTableIndex,
                cqiReport,
                ssRsrp,
                ssRsrq,
                ssSinr,
            )
        }
    }

    fun createCellLocationBundle(latitude: Double, longitude: Double): Bundle =
        Bundle().apply {
            putInt("cid", Random.nextInt(100000, 999999))
            putInt("lac", Random.nextInt(1000, 65535))
            putInt("psc", 0)
            putInt("baseStationLatitude", (latitude * 14400.0).toInt())
            putInt("baseStationLongitude", (longitude * 14400.0).toInt())
            putBoolean("empty", false)
            putBoolean("emptyParcel", false)
            putInt("mFlags", 1536)
            putBoolean("parcelled", false)
            putInt("baseStationId", Random.nextInt(10000, 99999))
            putInt("systemId", Random.nextInt(100, 1000))
            putInt("networkId", Random.nextInt(10000, 65536))
            putInt("size", 0)
        }

    fun createNeighboringCellInfoList(): List<NeighboringCellInfo> =
        runCatching {
            listOf(
                NeighboringCellInfo::class.java.declaredConstructor().newInstance().also {
                    it.setIntField("mRssi", -46)
                    it.setIntField("mCid", -1)
                    it.setIntField("mLac", -1)
                    it.setIntField("mPsc", -1)
                    it.setIntField("mNetworkType", 3)
                }
            )
        }.getOrDefault(emptyList())

    private fun <T> Class<T>.declaredConstructor(vararg parameterTypes: Class<*>): java.lang.reflect.Constructor<T> =
        getDeclaredConstructor(*parameterTypes).also {
            if (!it.isAccessible) it.isAccessible = true
        }

    private fun createLteInfo(profile: CellTowerProfile, timestampNanos: Long): CellInfoLte =
        CellInfoLte::class.java.declaredConstructor().newInstance().also {
            it.invokeMethod("setRegistered", profile.registered)
            it.invokeMethod("setTimeStamp", timestampNanos)
            it.invokeMethod(
                "setCellConnectionStatus",
                if (profile.registered) CellInfo.CONNECTION_PRIMARY_SERVING else CellInfo.CONNECTION_NONE,
            )
            it.invokeMethod("setCellIdentity", createLteIdentity(profile))
            it.invokeMethod("setCellSignalStrength", createLteSignalStrength(profile))
        }

    private fun createNrInfo(profile: CellTowerProfile, timestampNanos: Long): CellInfoNr =
        CellInfoNr::class.java.declaredConstructor(
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            CellIdentityNr::class.java,
            CellSignalStrengthNr::class.java,
        ).newInstance(
            if (profile.registered) CellInfo.CONNECTION_PRIMARY_SERVING else CellInfo.CONNECTION_NONE,
            profile.registered,
            timestampNanos,
            createNrIdentity(profile),
            createNrSignalStrength(profile),
        )

    private fun createGsmInfo(profile: CellTowerProfile, timestampNanos: Long): CellInfoGsm =
        CellInfoGsm::class.java.declaredConstructor().newInstance().also {
            it.invokeMethod("setRegistered", profile.registered)
            it.invokeMethod("setTimeStamp", timestampNanos)
            it.invokeMethod("setCellConnectionStatus", connectionStatus(profile))
            it.invokeMethod("setCellIdentity", createGsmIdentity(profile))
            val strength = CellSignalStrengthGsm::class.java.declaredConstructor(
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            ).newInstance(profile.rssiDbm, UNAVAILABLE, profile.timingAdvance ?: UNAVAILABLE)
            it.invokeMethod("setCellSignalStrength", strength)
        }

    private fun createWcdmaInfo(profile: CellTowerProfile, timestampNanos: Long): CellInfoWcdma =
        CellInfoWcdma::class.java.declaredConstructor().newInstance().also {
            it.invokeMethod("setRegistered", profile.registered)
            it.invokeMethod("setTimeStamp", timestampNanos)
            it.invokeMethod("setCellConnectionStatus", connectionStatus(profile))
            it.invokeMethod("setCellIdentity", createWcdmaIdentity(profile))
            val strength = CellSignalStrengthWcdma::class.java.declaredConstructor(
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            ).newInstance(profile.rssiDbm, UNAVAILABLE, profile.rsrpDbm ?: UNAVAILABLE, profile.rsrqDb ?: UNAVAILABLE)
            it.invokeMethod("setCellSignalStrength", strength)
        }

    private fun connectionStatus(profile: CellTowerProfile) =
        if (profile.registered) CellInfo.CONNECTION_PRIMARY_SERVING else CellInfo.CONNECTION_NONE

    private fun Any.invokeMethod(name: String, value: Any) {
        val method = javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 }
            ?: javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == 1 }
            ?: return
        if (!method.isAccessible) method.isAccessible = true
        method.invoke(this, value)
    }

    private fun Any.setIntField(name: String, value: Int) {
        val field = javaClass.getDeclaredField(name)
        if (!field.isAccessible) field.isAccessible = true
        field.setInt(this, value)
    }
}
