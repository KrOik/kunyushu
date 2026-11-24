package tv.kyshu.xposed

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.kyshu.xposed.config.BleDeviceProfile
import tv.kyshu.xposed.config.DomainSwitch
import tv.kyshu.xposed.config.GnssProfile
import tv.kyshu.xposed.config.LocationProfile
import tv.kyshu.xposed.config.MockDomain
import tv.kyshu.xposed.config.MockDomainConfig
import tv.kyshu.xposed.config.MockDomainConfigJson
import tv.kyshu.xposed.config.MockMode
import tv.kyshu.xposed.config.MockWifiData
import tv.kyshu.xposed.config.NmeaProfile
import tv.kyshu.xposed.utils.FakeLoc

@RunWith(RobolectricTestRunner::class)
class MockCommandContractValidationTest {
    @Before
    fun setUp() {
        FakeLoc.enable = false
        FakeLoc.latitude = 0.0
        FakeLoc.longitude = 0.0
        FakeLoc.altitude = 80.0
        FakeLoc.speed = 1.35
        FakeLoc.accuracy = 25f
        FakeLoc.bearing = 0.0
        FakeLoc.locationData.hasBearings = false
        FakeLoc.locationData.hideMock = false
        FakeLoc.enableMockGnss = false
        FakeLoc.enableNMEA = false
        FakeLoc.minSatellites = 12
        FakeLoc.hideBaseStation = false
        FakeLoc.needDowngradeToCdma = false
        FakeLoc.enableMockWifi = false
        FakeLoc.disableWifiScan = false
        FakeLoc.hookWifi = false
        FakeLoc.mockWifiList = emptyList()
        FakeLoc.enableBleMock = false
        FakeLoc.bleDevices = emptyList()
    }

    @Test
    fun `latitude validator rejects out of range values`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockCommandContract.requireLatitude(120.0, "lat")
        }
    }

    @Test
    fun `altitude validator rejects non finite values`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockCommandContract.requireAltitude(Double.NaN, "altitude")
        }
    }

    @Test
    fun `bearing validator accepts valid heading`() {
        assertEquals(180.0, MockCommandContract.requireBearing(180.0, "bearing"), 0.0)
    }

    @Test
    fun `default build applies wifi mock config`() {
        FakeLoc.enableMockWifi = true
        FakeLoc.disableWifiScan = true
        FakeLoc.hookWifi = true
        FakeLoc.mockWifiList = listOf(MockWifiData("ssid", "02:00:00:00:00:00", -50, 2412, 0, "", 0))

        MockCommandContract.applyPutConfig(Bundle().apply {
            putBoolean(MockCommandContract.Keys.ENABLE_MOCK_WIFI, true)
            putBoolean(MockCommandContract.Keys.DISABLE_WIFI_SCAN, true)
            putBoolean(MockCommandContract.Keys.HOOK_WIFI, true)
        })

        assertTrue(FakeLoc.enableMockWifi)
        assertTrue(FakeLoc.disableWifiScan)
        assertTrue(FakeLoc.hookWifi)
        assertEquals(1, FakeLoc.mockWifiList.size)
    }

    @Test
    fun `put config imports versioned domain config into supported runtime state`() {
        val json = MockDomainConfigJson.exportJson(
            MockDomainConfig(
                switches = mapOf(
                    MockDomain.LOCATION to DomainSwitch(
                        domain = MockDomain.LOCATION,
                        runtimeEnabled = true,
                        mode = MockMode.REPLACE_WITH_PROFILE,
                    ),
                    MockDomain.GNSS to DomainSwitch(
                        domain = MockDomain.GNSS,
                        runtimeEnabled = true,
                        mode = MockMode.REPLACE_WITH_PROFILE,
                    ),
                    MockDomain.NMEA to DomainSwitch(
                        domain = MockDomain.NMEA,
                        runtimeEnabled = true,
                        mode = MockMode.REPLACE_WITH_PROFILE,
                    ),
                    MockDomain.CELL to DomainSwitch(
                        domain = MockDomain.CELL,
                        runtimeEnabled = true,
                        mode = MockMode.MASK_REAL,
                    ),
                ),
                location = LocationProfile(
                    latitude = 31.2304,
                    longitude = 121.4737,
                    altitudeMeters = 18.5,
                    speedMetersPerSecond = 2.25f,
                    bearingDegrees = 90f,
                    horizontalAccuracyMeters = 8.5f,
                    preserveMockFlag = false,
                ),
                gnss = GnssProfile(minSatelliteCount = 18),
                nmea = NmeaProfile(enabled = true),
            ),
        )

        MockCommandContract.applyPutConfig(Bundle().apply {
            putString(MockCommandContract.Keys.MOCK_DOMAIN_CONFIG_JSON, json)
        })

        assertTrue(FakeLoc.enable)
        assertEquals(31.2304, FakeLoc.latitude, 0.0)
        assertEquals(121.4737, FakeLoc.longitude, 0.0)
        assertEquals(18.5, FakeLoc.altitude, 0.0)
        assertEquals(2.25, FakeLoc.speed, 0.0)
        assertEquals(8.5f, FakeLoc.accuracy)
        assertEquals(90.0, FakeLoc.bearing, 0.0)
        assertTrue(FakeLoc.hasBearings)
        assertTrue(FakeLoc.locationData.hideMock)
        assertTrue(FakeLoc.enableMockGnss)
        assertTrue(FakeLoc.enableNMEA)
        assertEquals(18, FakeLoc.minSatellites)
        assertTrue(FakeLoc.hideBaseStation)
        assertFalse(FakeLoc.needDowngradeToCdma)
    }

    @Test
    fun `legacy put config keys override imported domain config fields`() {
        val json = MockDomainConfigJson.exportJson(
            MockDomainConfig(
                switches = mapOf(
                    MockDomain.LOCATION to DomainSwitch(
                        domain = MockDomain.LOCATION,
                        runtimeEnabled = true,
                        mode = MockMode.REPLACE_WITH_PROFILE,
                    ),
                ),
                location = LocationProfile(latitude = 31.0, longitude = 121.0),
            ),
        )

        MockCommandContract.applyPutConfig(Bundle().apply {
            putString(MockCommandContract.Keys.MOCK_DOMAIN_CONFIG_JSON, json)
            putBoolean(MockCommandContract.Keys.ENABLE, false)
            putDouble(MockCommandContract.Keys.LATITUDE, 40.0)
            putDouble(MockCommandContract.Keys.LONGITUDE, 116.0)
        })

        assertFalse(FakeLoc.enable)
        assertEquals(40.0, FakeLoc.latitude, 0.0)
        assertEquals(116.0, FakeLoc.longitude, 0.0)
    }

    @Test
    fun `invalid domain config json rejects without mutating runtime state`() {
        FakeLoc.latitude = 10.0

        assertThrows(IllegalArgumentException::class.java) {
            MockCommandContract.applyPutConfig(Bundle().apply {
                putString(MockCommandContract.Keys.MOCK_DOMAIN_CONFIG_JSON, "{")
            })
        }

        assertEquals(10.0, FakeLoc.latitude, 0.0)
        assertFalse(FakeLoc.enable)
    }

    @Test
    fun `schema invalid domain config json rejects without mutating runtime state`() {
        FakeLoc.latitude = 10.0
        FakeLoc.enableBleMock = false
        FakeLoc.bleDevices = emptyList()
        val json = MockDomainConfigJson.exportJson(
            MockDomainConfig(
                bleDevices = listOf(
                    BleDeviceProfile(
                        address = "AA:BB:CC:DD:EE:FF",
                        serviceUuids = listOf("not-a-uuid"),
                    ),
                ),
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            MockCommandContract.applyPutConfig(Bundle().apply {
                putString(MockCommandContract.Keys.MOCK_DOMAIN_CONFIG_JSON, json)
            })
        }

        val errorText = "${error.message} ${error.cause?.message}"
        assertTrue(errorText.contains(MockCommandContract.Keys.MOCK_DOMAIN_CONFIG_JSON))
        assertTrue(errorText.contains("bleDevices[0].serviceUuids[0]"))
        assertEquals(10.0, FakeLoc.latitude, 0.0)
        assertFalse(FakeLoc.enableBleMock)
        assertEquals(emptyList<BleDeviceProfile>(), FakeLoc.bleDevices)
    }

    @Test
    fun `default build starts wifi mock lifecycle`() {
        val state = object : HookRuntimeStateStore {
            override var forceDisable = false
            override var enable = false
            override var enableMockGnss = false
            override var enableMockWifi = false
            override var latitude = 0.0
            override var longitude = 0.0
            override var altitude = 0.0
            override var speed = 0.0
            override var accuracy = 0f
            override var bearing = 0.0
            override var hasBearings = false
        }
        val reply = Bundle()

        val handled = HookLifecycleController(state).handle("start_wifi_mock", reply)

        assertTrue(handled)
        assertTrue(state.enableMockWifi)
    }

    @Test
    fun `domain command names are recognized`() {
        assertEquals(true, MockCommandContract.isDomainCommand(MockCommandContract.Commands.LOCATION_UPDATE))
        assertEquals(true, MockCommandContract.isDomainCommand(MockCommandContract.Commands.NFC_REPLAY))
        assertFalse(MockCommandContract.isDomainCommand(MockCommandContract.Commands.PUT_CONFIG))
    }

    @Test
    fun `domain location update validates coordinate bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockCommandContract.validateDomainCommand(
                MockCommandContract.Commands.LOCATION_UPDATE,
                Bundle().apply {
                    putDouble(MockCommandContract.Keys.LATITUDE, 91.0)
                    putDouble(MockCommandContract.Keys.LONGITUDE, 0.0)
                },
            )
        }
    }

    @Test
    fun `domain gnss update validates satellite count`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockCommandContract.validateDomainCommand(
                MockCommandContract.Commands.GNSS_UPDATE_STATUS,
                Bundle().apply { putInt(MockCommandContract.Keys.MIN_SATELLITES, 100) },
            )
        }
    }

    @Test
    fun `domain wifi connected update validates mac fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockCommandContract.validateDomainCommand(
                MockCommandContract.Commands.WIFI_UPDATE_CONNECTED,
                Bundle().apply { putString(MockCommandContract.Keys.MOCK_BSSID, "not-a-mac") },
            )
        }
    }

    @Test
    fun `domain nfc replay rejects oversized payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockCommandContract.validateDomainCommand(
                MockCommandContract.Commands.NFC_REPLAY,
                Bundle().apply { putString(MockCommandContract.Keys.PAYLOAD_HEX, "AB".repeat(4097)) },
            )
        }
    }

    @Test
    fun `domain command bundle cap rejects large json`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockCommandContract.validateDomainCommand(
                MockCommandContract.Commands.BLE_UPDATE,
                Bundle().apply { putString(MockCommandContract.Keys.PAYLOAD_JSON, "x".repeat(40_000)) },
            )
        }
    }
}
