package tv.kyshu.service

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.kyshu.ui.mock.HistoricalLocation
import tv.kyshu.xposed.config.BleDeviceProfile
import tv.kyshu.xposed.config.DomainSwitch
import tv.kyshu.xposed.config.GnssProfile
import tv.kyshu.xposed.config.LocationProfile
import tv.kyshu.xposed.config.MockDomain
import tv.kyshu.xposed.config.MockDomainConfig
import tv.kyshu.xposed.config.MockDomainConfigJson
import tv.kyshu.xposed.config.MockMode
import tv.kyshu.xposed.config.NfcReplayProfile
import tv.kyshu.xposed.config.NmeaProfile
import tv.kyshu.xposed.config.WifiScanEntry
import tv.kyshu.xposed.config.WifiScanProfile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MockDomainConfigPrefsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `export persists versioned domain config json from scalar prefs`() {
        prefs()
            .edit()
            .putBoolean("enable", true)
            .putString("selectedLocation", "Tokyo,Station,35.6812,139.7671")
            .putFloat("altitude", 44.0f)
            .putFloat("speed", 1.5f)
            .putFloat("accuracy", 8.0f)
            .putInt("minSatelliteCount", 20)
            .putBoolean("enableNMEA", true)
            .putBoolean("hideBaseStation", true)
            .commit()

        val json = MockDomainConfigPrefs.exportJson(context)
        val stored = prefs().getString(MockDomainConfigPrefs.PREF_KEY, null)
        val parsed = JSONObject(json)

        assertEquals(json, stored)
        assertEquals(MockDomainConfig.CURRENT_SCHEMA_VERSION, parsed.getInt("schema_version"))
        assertEquals(35.6812, parsed.getJSONObject("location").getDouble("latitude"), 0.0)
        assertEquals(139.7671, parsed.getJSONObject("location").getDouble("longitude"), 0.0)
        assertEquals(44.0, parsed.getJSONObject("location").getDouble("altitude_meters"), 0.0)
        assertEquals(20, parsed.getJSONObject("gnss").getInt("min_satellite_count"))
        assertTrue(parsed.getJSONObject("nmea").getBoolean("enabled"))
        assertEquals("MASK_REAL", parsed.getJSONObject("switches").getJSONObject("CELL").getString("mode"))
    }

    @Test
    fun `import writes supported scalar prefs and preserves unsupported profiles on next export`() {
        val config = MockDomainConfig(
            switches = switches(
                MockDomain.LOCATION to MockMode.REPLACE_WITH_PROFILE,
                MockDomain.GNSS to MockMode.REPLACE_WITH_PROFILE,
                MockDomain.NMEA to MockMode.REPLACE_WITH_PROFILE,
                MockDomain.SENSOR to MockMode.REPLACE_WITH_PROFILE,
                MockDomain.CELL to MockMode.MASK_REAL,
            ),
            location = LocationProfile(
                latitude = 12.25,
                longitude = 45.5,
                altitudeMeters = 321.0,
                speedMetersPerSecond = 2.5f,
                horizontalAccuracyMeters = 6.5f,
                preserveMockFlag = false,
            ),
            gnss = GnssProfile(minSatelliteCount = 24),
            nmea = NmeaProfile(enabled = true),
            wifiScan = WifiScanProfile(
                entries = listOf(
                    WifiScanEntry(
                        ssid = "Lab",
                        bssid = "12:34:56:78:9A:BC",
                        rssiDbm = -51,
                        frequencyMhz = 5180,
                    ),
                ),
                maxEntries = 1,
            ),
            bleDevices = listOf(
                BleDeviceProfile(
                    address = "AA:BB:CC:DD:EE:FF",
                    name = "Beacon",
                    rssiDbm = -60,
                ),
            ),
            nfcReplays = listOf(
                NfcReplayProfile(
                    label = "Test tag",
                    uidHex = "01020304",
                    ndefPayloadHex = "D101",
                ),
            ),
        )

        val legalizedJson = MockDomainConfigPrefs.importJson(
            context,
            MockDomainConfigJson.exportJson(config),
        )

        val selected = HistoricalLocation.fromString(prefs().getString("selectedLocation", null)!!)
        assertTrue(prefs().getBoolean("enable", false))
        assertEquals(12.25, selected.lat, 0.0)
        assertEquals(45.5, selected.lon, 0.0)
        assertEquals(321.0f, prefs().getFloat("altitude", 0f), 0f)
        assertEquals(2.5f, prefs().getFloat("speed", 0f), 0f)
        assertEquals(6.5f, prefs().getFloat("accuracy", 0f), 0f)
        assertTrue(prefs().getBoolean("hideMock", false))
        assertTrue(prefs().getBoolean("enableMockGnss", false))
        assertTrue(prefs().getBoolean("enableNMEA", false))
        assertEquals(24, prefs().getInt("minSatelliteCount", 0))
        assertTrue(prefs().getBoolean("hookSensor", false))
        assertTrue(prefs().getBoolean("hideBaseStation", false))
        assertFalse(prefs().getBoolean("needDowngradeToCdma", true))

        val imported = JSONObject(legalizedJson)
        assertEquals(1, imported.getJSONArray("ble_devices").length())
        assertEquals(1, imported.getJSONArray("nfc_replays").length())
        assertEquals(1, imported.getJSONObject("wifi_scan").getJSONArray("entries").length())

        val reExported = JSONObject(MockDomainConfigPrefs.exportJson(context))
        assertEquals(1, reExported.getJSONArray("ble_devices").length())
        assertEquals(1, reExported.getJSONArray("nfc_replays").length())
        assertEquals(1, reExported.getJSONObject("wifi_scan").getJSONArray("entries").length())
    }

    @Test
    fun `invalid import does not mutate scalar prefs`() {
        prefs()
            .edit()
            .putInt("minSatelliteCount", 7)
            .commit()

        assertThrows(Exception::class.java) {
            MockDomainConfigPrefs.importJson(context, "{not-json")
        }

        assertEquals(7, prefs().getInt("minSatelliteCount", 0))
        assertFalse(prefs().contains(MockDomainConfigPrefs.PREF_KEY))
    }

    @Test
    fun `schema-invalid import does not mutate prefs`() {
        prefs()
            .edit()
            .putInt("minSatelliteCount", 9)
            .commit()
        val invalidConfig = MockDomainConfig(
            bleDevices = listOf(
                BleDeviceProfile(
                    address = "AA:BB:CC:DD:EE:FF",
                    serviceUuids = listOf("not-a-uuid"),
                )
            )
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            MockDomainConfigPrefs.importJson(context, MockDomainConfigJson.exportJson(invalidConfig))
        }

        assertTrue(error.message.orEmpty().contains("bleDevices[0].serviceUuids[0]"))
        assertEquals(9, prefs().getInt("minSatelliteCount", 0))
        assertFalse(prefs().contains(MockDomainConfigPrefs.PREF_KEY))
    }

    private fun prefs() =
        context.getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)

    private fun switches(vararg enabled: Pair<MockDomain, MockMode>): Map<MockDomain, DomainSwitch> {
        val modes = enabled.toMap()
        return MockDomain.entries.associateWith { domain ->
            val mode = modes[domain] ?: MockMode.OFF
            DomainSwitch(
                domain = domain,
                runtimeEnabled = mode != MockMode.OFF,
                mode = mode,
            )
        }
    }
}
