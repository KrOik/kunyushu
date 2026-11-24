package tv.kyshu.xposed.factory

import android.location.Location
import android.location.LocationManager
import android.location.GnssStatus
import android.net.wifi.WifiInfo
import android.bluetooth.le.ScanResult
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoGsm
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.ParcelUuid
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.kyshu.xposed.config.BleDeviceProfile
import tv.kyshu.xposed.config.CellRat
import tv.kyshu.xposed.config.CellTowerProfile
import tv.kyshu.xposed.config.MockWifiData
import tv.kyshu.xposed.config.NfcReplayProfile
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.GnssFlags

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MockAndroidFactoryTest {
    @After
    fun tearDown() {
        resetFakeLoc()
    }

    @Test
    fun `LocationFactory builds injected location with configured fields`() {
        resetFakeLoc()
        FakeLoc.latitude = 31.2304
        FakeLoc.longitude = 121.4737
        FakeLoc.altitude = 12.5
        FakeLoc.accuracy = 0f
        FakeLoc.speed = 2.0
        FakeLoc.speedAmplitude = 0.0
        FakeLoc.bearing = 90.0
        FakeLoc.enableAltitudeJitter = false

        val origin = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 30.0
            longitude = 120.0
            accuracy = 8.5f
            speed = 1f
            time = 1_700_000_000_000L
            elapsedRealtimeNanos = 1_000_000L
        }

        val injected = LocationFactory.createInjectedLocation(origin)

        assertEquals(31.2304, injected.latitude, 0.0)
        assertEquals(121.4737, injected.longitude, 0.0)
        assertEquals(12.5, injected.altitude, 0.0)
        assertEquals(8.5f, injected.accuracy)
        assertEquals(2.0f, injected.speed)
        assertEquals(90.0f, injected.bearing)
        assertTrue(injected.time > origin.time)
        assertTrue(injected.elapsedRealtimeNanos > 0)
        assertTrue(LocationFactory.isInjected(injected))
    }

    @Test
    fun `WifiScanResultFactory maps configured scan fields`() {
        val scanResult = WifiScanResultFactory.fromMockWifiOrNull(
            MockWifiData(
                ssid = "LabNet",
                bssid = "02:00:00:00:00:03",
                rssi = -57,
                frequency = 5200,
                channelWidth = 0,
                capabilities = "[WPA2]",
                standard = 0,
            )
        )

        assertNotNull(scanResult)
        assertEquals("LabNet", scanResult!!.SSID)
        assertEquals("02:00:00:00:00:03", scanResult.BSSID)
        assertEquals(-57, scanResult.level)
        assertEquals(5200, scanResult.frequency)
    }

    @Test
    fun `WifiInfoFactory maps configured connected identity and signal`() {
        resetFakeLoc()
        FakeLoc.mockSSID = "OfficeNet"
        FakeLoc.mockBSSID = "02:00:00:00:00:05"
        FakeLoc.mockMac = "02:00:00:00:00:06"
        FakeLoc.mockIp = "192.168.10.20"
        FakeLoc.currentRssiOffset = 6

        val result = WifiInfoFactory.createConnectedInfo(newWifiInfoForTest())

        assertNotNull(result)
        assertEquals("OfficeNet", result!!.ssid)
        assertEquals("02:00:00:00:00:05", result.bssid)
        assertEquals("02:00:00:00:00:06", result.mac)
        assertEquals(-59, result.rssiDbm)
    }

    @Test
    fun `CellInfoFactory maps CDMA identity and cell location coordinates`() {
        val latitude = 31.2304
        val longitude = 121.4737

        val identity = CellInfoFactory.createCdmaIdentity(latitude, longitude)
        val bundle = CellInfoFactory.createCellLocationBundle(latitude, longitude)
        val cellInfo = CellInfoFactory.createCdmaInfo(latitude, longitude)

        assertEquals((latitude * 14400.0).toInt(), identity.latitude)
        assertEquals((longitude * 14400.0).toInt(), identity.longitude)
        assertEquals((latitude * 14400.0).toInt(), bundle.getInt("baseStationLatitude"))
        assertEquals((longitude * 14400.0).toInt(), bundle.getInt("baseStationLongitude"))
        assertNotNull(cellInfo)
    }

    @Test
    fun `CellInfoFactory builds neighboring cell fallback`() {
        val neighbors = CellInfoFactory.createNeighboringCellInfoList()

        assertEquals(1, neighbors.size)
        assertEquals(-46, neighbors.single().rssi)
        assertEquals(-1, neighbors.single().cid)
    }

    @Test
    fun `CellInfoFactory builds LTE profile with identity and signal fields`() {
        val profile = CellTowerProfile(
            rat = CellRat.LTE,
            mcc = "460",
            mnc = "01",
            tacOrLac = 321,
            cellId = 12345678,
            pciOrPsc = 42,
            arfcn = 1850,
            bands = listOf(3, 41),
            bandwidthKhz = 20000,
            additionalPlmns = listOf("46011"),
            rssiDbm = -75,
            rsrpDbm = -95,
            rsrqDb = -11,
            rssnrDb = 12,
            cqiTableIndex = 2,
            cqi = 9,
            timingAdvance = 8,
            registered = true,
        )

        val cellInfo = CellInfoFactory.createFromProfileOrNull(profile, timestampNanos = 123_000_000L)

        assertTrue(cellInfo is CellInfoLte)
        cellInfo as CellInfoLte
        assertTrue(cellInfo.isRegistered)
        assertEquals(CellInfo.CONNECTION_PRIMARY_SERVING, cellInfo.cellConnectionStatus)
        assertEquals(123L, cellInfo.timestampMillis)
        assertEquals("460", cellInfo.cellIdentity.mccString)
        assertEquals("01", cellInfo.cellIdentity.mncString)
        assertEquals(12345678, cellInfo.cellIdentity.ci)
        assertEquals(42, cellInfo.cellIdentity.pci)
        assertEquals(321, cellInfo.cellIdentity.tac)
        assertEquals(1850, cellInfo.cellIdentity.earfcn)
        assertArrayEquals(intArrayOf(3, 41), cellInfo.cellIdentity.bands)
        assertEquals(20000, cellInfo.cellIdentity.bandwidth)
        assertTrue(cellInfo.cellIdentity.additionalPlmns.contains("46011"))
        assertEquals(-75, cellInfo.cellSignalStrength.rssi)
        assertEquals(-95, cellInfo.cellSignalStrength.rsrp)
        assertEquals(-11, cellInfo.cellSignalStrength.rsrq)
        assertEquals(12, cellInfo.cellSignalStrength.rssnr)
        assertEquals(2, cellInfo.cellSignalStrength.cqiTableIndex)
        assertEquals(9, cellInfo.cellSignalStrength.cqi)
        assertEquals(8, cellInfo.cellSignalStrength.timingAdvance)
    }

    @Test
    fun `CellInfoFactory preserves GSM and WCDMA radio profiles`() {
        val gsm = CellInfoFactory.createFromProfileOrNull(CellTowerProfile(
            rat = CellRat.GSM, mcc = "460", mnc = "00", tacOrLac = 10,
            cellId = 20, pciOrPsc = 3, arfcn = 40, rssiDbm = -79, registered = true,
        ))
        val wcdma = CellInfoFactory.createFromProfileOrNull(CellTowerProfile(
            rat = CellRat.WCDMA, mcc = "460", mnc = "01", tacOrLac = 11,
            cellId = 21, pciOrPsc = 4, arfcn = 10564, rssiDbm = -85,
        ))
        assertTrue(gsm is CellInfoGsm)
        assertTrue(wcdma is CellInfoWcdma)
        assertEquals(20, (gsm as CellInfoGsm).cellIdentity.cid)
        assertEquals(21, (wcdma as CellInfoWcdma).cellIdentity.cid)
    }

    @Test
    @Config(sdk = [35])
    fun `CellInfoFactory builds NR profile with identity and signal fields`() {
        val profile = CellTowerProfile(
            rat = CellRat.NR,
            mcc = "460",
            mnc = "01",
            tacOrLac = 654321,
            cellId = 68_719_476_700L,
            pciOrPsc = 777,
            arfcn = 635334,
            bands = listOf(78),
            additionalPlmns = listOf("46001"),
            rssiDbm = -88,
            csiRsrpDbm = -91,
            csiRsrqDb = -12,
            csiSinrDb = 9,
            csiCqiTableIndex = 1,
            csiCqiReport = listOf(7, 8),
            ssRsrpDbm = -89,
            ssRsrqDb = -10,
            ssSinrDb = 14,
            timingAdvanceMicros = 12,
            registered = false,
        )

        val cellInfo = CellInfoFactory.createFromProfileOrNull(profile, timestampNanos = 456_000_000L)

        assertTrue(cellInfo is CellInfoNr)
        cellInfo as CellInfoNr
        assertFalse(cellInfo.isRegistered)
        assertEquals(CellInfo.CONNECTION_NONE, cellInfo.cellConnectionStatus)
        assertEquals(456L, cellInfo.timestampMillis)
        val identity = cellInfo.cellIdentity as CellIdentityNr
        assertEquals("460", identity.mccString)
        assertEquals("01", identity.mncString)
        assertEquals(68_719_476_700L, identity.nci)
        assertEquals(777, identity.pci)
        assertEquals(654321, identity.tac)
        assertEquals(635334, identity.nrarfcn)
        assertArrayEquals(intArrayOf(78), identity.bands)
        assertTrue(identity.additionalPlmns.contains("46001"))
        val signal = cellInfo.cellSignalStrength as CellSignalStrengthNr
        assertEquals(-91, signal.csiRsrp)
        assertEquals(-12, signal.csiRsrq)
        assertEquals(9, signal.csiSinr)
        assertEquals(1, signal.csiCqiTableIndex)
        assertEquals(listOf(7, 8), signal.csiCqiReport)
        assertEquals(-89, signal.ssRsrp)
        assertEquals(-10, signal.ssRsrq)
        assertEquals(14, signal.ssSinr)
        assertEquals(12, signal.timingAdvanceMicros)
    }

    @Test
    fun `GnssStatusFactory maps satellite fields and callback shapes`() {
        val data = GnssStatusFactory.createData(
            listOf(
                GnssSatelliteStatus(
                    svid = 14,
                    constellation = GnssFlags.CONSTELLATION_BEIDOU,
                    cn0 = 42.5f,
                    elevation = 33.0f,
                    azimuth = 120.0f,
                    carrierFrequencyMhz = 1575.420f,
                    hasEphemeris = true,
                    hasAlmanac = true,
                    usedInFix = false,
                )
            ),
            basebandCn0For = { _, cn0 -> cn0 - 3.0f },
        )

        val legacyArgs = arrayOfNulls<Any?>(7).also { it[0] = 0 }
        GnssStatusFactory.applyLegacyCallbackArgs(legacyArgs, data)
        val status = GnssStatusFactory.createStatus(GnssStatus::class.java, data) as GnssStatus

        assertEquals(1, data.svCount)
        assertSame(data.svidWithFlags, legacyArgs[1])
        assertEquals(1, status.satelliteCount)
        assertEquals(14, status.getSvid(0))
        assertEquals(GnssFlags.CONSTELLATION_BEIDOU, status.getConstellationType(0))
        assertEquals(42.5f, status.getCn0DbHz(0))
        assertEquals(33.0f, status.getElevationDegrees(0))
        assertEquals(120.0f, status.getAzimuthDegrees(0))
        assertEquals(1_575_420_000f, status.getCarrierFrequencyHz(0), 1.0f)
        assertEquals(39.5f, status.getBasebandCn0DbHz(0))
        assertTrue(status.hasEphemerisData(0))
        assertTrue(status.hasAlmanacData(0))
        assertTrue(status.hasCarrierFrequencyHz(0))
        assertTrue(status.hasBasebandCn0DbHz(0))
        assertFalse(status.usedInFix(0))
    }

    @Test
    fun `BleScanResultFactory spoofs existing scan identity and rssi`() {
        val scanResult = ScanResult(
            BleScanResultFactory.createBluetoothDevice("C0:00:00:00:00:01"),
            null,
            -80,
            1L,
        )

        val spoofed = BleScanResultFactory.spoofExisting(
            scanResult,
            address = "C0:11:22:33:44:55",
            rssi = -58,
        )

        assertTrue(spoofed)
        assertEquals("C0:11:22:33:44:55", scanResult.getDevice().getAddress())
        assertEquals(-58, scanResult.getRssi())
    }

    @Test
    fun `BleScanResultFactory builds profile backed scan record`() {
        val serviceUuid = "0000180D-0000-1000-8000-00805F9B34FB"
        val solicitationUuid = "0000180F-0000-1000-8000-00805F9B34FB"
        val scanResult = BleScanResultFactory.fromProfileOrNull(
            BleDeviceProfile(
                address = "C0:AA:BB:CC:DD:EE",
                name = "PortalBand",
                rssiDbm = -63,
                serviceUuids = listOf(serviceUuid),
                serviceSolicitationUuids = listOf(solicitationUuid),
                manufacturerDataHex = "4C000215",
                serviceDataHex = "0102",
                txPowerDbm = -12,
                primaryPhy = 1,
                secondaryPhy = 2,
                advertisingSid = 7,
                periodicAdvertisingInterval = 160,
                dataStatus = ScanResult.DATA_TRUNCATED,
                legacy = false,
                connectable = true,
            ),
            timestampNanos = 1234L,
        )

        assertNotNull(scanResult)
        assertEquals("C0:AA:BB:CC:DD:EE", scanResult!!.device.address)
        assertEquals(-63, scanResult.rssi)
        assertEquals(1234L, scanResult.timestampNanos)
        assertFalse(scanResult.isLegacy)
        assertTrue(scanResult.isConnectable)
        assertEquals(ScanResult.DATA_TRUNCATED, scanResult.dataStatus)
        assertEquals(1, scanResult.primaryPhy)
        assertEquals(2, scanResult.secondaryPhy)
        assertEquals(7, scanResult.advertisingSid)
        assertEquals(-12, scanResult.txPower)
        assertEquals(160, scanResult.periodicAdvertisingInterval)
        val record = scanResult.scanRecord
        assertNotNull(record)
        assertEquals("PortalBand", record!!.deviceName)
        assertTrue(record.serviceUuids.contains(ParcelUuid.fromString(serviceUuid)))
        assertTrue(record.serviceSolicitationUuids.contains(ParcelUuid.fromString(solicitationUuid)))
        assertEquals(-12, record.txPowerLevel)
        assertArrayEquals(byteArrayOf(0x02, 0x15), record.getManufacturerSpecificData(0x004C))
        assertArrayEquals(byteArrayOf(0x01, 0x02), record.getServiceData(ParcelUuid.fromString(serviceUuid)))
    }

    @Test
    fun `NfcTagFactory builds mock NDEF tag from profile`() {
        val payload = NdefMessage(arrayOf(NdefRecord.createTextRecord("en", "portal"))).toByteArray()
        val tag = NfcTagFactory.createTag(
            NfcReplayProfile(
                uidHex = "01020304",
                ndefPayloadHex = payload.toHex(),
                techList = listOf("android.nfc.tech.Ndef"),
                maxPayloadBytes = 128,
            )
        )

        assertNotNull(tag)
        assertEquals("01020304", tag!!.id.toHex())
        assertEquals(listOf("android.nfc.tech.Ndef"), tag.techList.toList())

        val extras = NfcTagFactory.getTechExtras(tag, NfcTagFactory.TECH_NDEF)
        assertNotNull(extras)
        val cached = extras!!.getParcelable<NdefMessage>(NfcTagFactory.NDEF_EXTRA_MESSAGE_KEY)
        assertNotNull(cached)
        assertEquals("portal", cached!!.records.single().toTextPayload())
        assertEquals(128, extras.getInt(NfcTagFactory.NDEF_EXTRA_MAX_LENGTH_KEY))
    }

    @Test
    fun `NfcTagFactory rejects invalid or oversized payloads`() {
        assertNull(
            NfcTagFactory.createTag(
                NfcReplayProfile(uidHex = "01020304", ndefPayloadHex = "ABC", maxPayloadBytes = 128)
            )
        )
        assertNull(
            NfcTagFactory.createTag(
                NfcReplayProfile(uidHex = "01020304", ndefPayloadHex = "00".repeat(129), maxPayloadBytes = 128)
            )
        )
    }

    @Test
    fun `WifiScanResultFactory prefers explicit list over random count`() {
        resetFakeLoc()
        FakeLoc.mockWifiScanCount = 5
        FakeLoc.mockWifiList = listOf(
            MockWifiData(
                ssid = "FixedNet",
                bssid = "02:00:00:00:00:04",
                rssi = -61,
                frequency = 2412,
                channelWidth = 0,
                capabilities = "[WPA2]",
                standard = 0,
            )
        )

        val scanResults = WifiScanResultFactory.createFromCurrentConfig(maxEntries = 32)

        assertEquals(1, scanResults.size)
        assertEquals("FixedNet", scanResults.single().SSID)
    }

    private fun resetFakeLoc() {
        LocationFactory.resetDynamicsForTest()
        FakeLoc.latitude = 0.0
        FakeLoc.longitude = 0.0
        FakeLoc.altitude = 80.0
        FakeLoc.accuracy = 25.0f
        FakeLoc.speed = 1.35
        FakeLoc.speedAmplitude = 1.0
        FakeLoc.bearing = 0.0
        FakeLoc.enableAltitudeJitter = true
        FakeLoc.altitudeJitter = 0.7f
        FakeLoc.hideMock = true
        FakeLoc.mockWifiScanCount = 0
        FakeLoc.mockWifiList = emptyList()
        FakeLoc.mockSSID = "KunYuShu-Wifi"
        FakeLoc.mockBSSID = "02:00:00:00:00:00"
        FakeLoc.mockMac = "02:00:00:00:00:00"
        FakeLoc.mockIp = "192.168.1.123"
        FakeLoc.currentRssiOffset = 0
    }

    private fun newWifiInfoForTest(): WifiInfo {
        val constructor = WifiInfo::class.java.getDeclaredConstructor()
        if (!constructor.isAccessible) constructor.isAccessible = true
        return constructor.newInstance()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun NdefRecord.toTextPayload(): String {
        val bytes = payload
        val languageLength = bytes.first().toInt() and 0x3F
        return bytes.copyOfRange(1 + languageLength, bytes.size).toString(Charsets.UTF_8)
    }
}
