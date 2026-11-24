package tv.kyshu.xposed.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MockDomainConfigTest {
    @Test
    fun defaultConfigIsValidAndOff() {
        val config = MockDomainConfig()

        assertTrue(config.isValid())
        assertTrue(config.switches.values.none { it.active })
    }

    @Test
    fun validatesWifiConnectedFields() {
        val config = MockDomainConfig(
            wifiConnected = WifiConnectedProfile(
                bssid = "invalid",
                macAddress = "02:00:00:00:00:01",
                ipv4Address = "192.168.1.260",
                rssiDbm = -200,
            )
        )

        val fields = config.validate().map { it.field }.toSet()
        assertEquals(setOf("bssid", "ipv4Address", "rssiDbm"), fields)
    }

    @Test
    fun validatesCellWithoutIdentityFields() {
        val config = MockDomainConfig(
            cellTowers = listOf(
                CellTowerProfile(
                    rat = CellRat.NR,
                    mcc = "460",
                    mnc = "01",
                    tacOrLac = 100,
                    cellId = 200,
                    rssiDbm = -95,
                )
            )
        )

        assertTrue(config.isValid())
    }

    @Test
    fun validatesCellRatSpecificUpperBounds() {
        val config = MockDomainConfig(
            cellTowers = listOf(
                CellTowerProfile(
                    rat = CellRat.NR,
                    mcc = "460",
                    mnc = "01",
                    tacOrLac = 16_777_215,
                    cellId = 68_719_476_735L,
                    pciOrPsc = 1007,
                    arfcn = 3_279_165,
                ),
                CellTowerProfile(
                    rat = CellRat.LTE,
                    mcc = "460",
                    mnc = "01",
                    tacOrLac = 65_535,
                    cellId = 268_435_455,
                    pciOrPsc = 503,
                    arfcn = 262_143,
                ),
                CellTowerProfile(
                    rat = CellRat.GSM,
                    mcc = "460",
                    mnc = "01",
                    tacOrLac = 65_535,
                    cellId = 65_535,
                    pciOrPsc = 63,
                    arfcn = 1_023,
                ),
            ),
        )

        assertTrue(config.isValid())
    }

    @Test
    fun validatesCellRatSpecificOutOfRangeFields() {
        val config = MockDomainConfig(
            cellTowers = listOf(
                CellTowerProfile(
                    rat = CellRat.NR,
                    mcc = "460",
                    mnc = "01",
                    tacOrLac = 16_777_216,
                    cellId = 68_719_476_736L,
                    pciOrPsc = 1008,
                    arfcn = 3_279_166,
                ),
                CellTowerProfile(
                    rat = CellRat.LTE,
                    mcc = "460",
                    mnc = "01",
                    tacOrLac = 65_536,
                    cellId = 268_435_456,
                    pciOrPsc = 504,
                    arfcn = 262_144,
                ),
            ),
        )

        val fields = config.validate().map { it.field }.toSet()

        assertEquals(
            setOf(
                "cellTowers[0].tacOrLac",
                "cellTowers[0].cellId",
                "cellTowers[0].pciOrPsc",
                "cellTowers[0].arfcn",
                "cellTowers[1].tacOrLac",
                "cellTowers[1].cellId",
                "cellTowers[1].pciOrPsc",
                "cellTowers[1].arfcn",
            ),
            fields,
        )
    }

    @Test
    fun validatedImportRejectsInvalidCellFields() {
        val json = MockDomainConfigJson.exportJson(
            MockDomainConfig(
                cellTowers = listOf(
                    CellTowerProfile(
                        rat = CellRat.NR,
                        mcc = "460",
                        mnc = "01",
                        arfcn = 3_279_166,
                    ),
                ),
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            MockDomainConfigJson.importValidatedJson(json)
        }

        assertTrue(error.message.orEmpty().contains("cellTowers[0].arfcn"))
    }

    @Test
    fun validatesCellLteNrFactoryFields() {
        val config = MockDomainConfig(
            cellTowers = listOf(
                CellTowerProfile(
                    rat = CellRat.LTE,
                    mcc = "460",
                    mnc = "01",
                    bands = listOf(0),
                    bandwidthKhz = 20_001,
                    additionalPlmns = listOf("bad"),
                    rsrpDbm = -141,
                    rsrqDb = -35,
                    rssnrDb = 31,
                    cqiTableIndex = 7,
                    cqi = 16,
                    timingAdvance = 1283,
                ),
                CellTowerProfile(
                    rat = CellRat.NR,
                    mcc = "460",
                    mnc = "01",
                    csiRsrpDbm = -157,
                    csiRsrqDb = -21,
                    csiSinrDb = 24,
                    csiCqiTableIndex = 4,
                    csiCqiReport = listOf(16),
                    ssRsrpDbm = -157,
                    ssRsrqDb = 21,
                    ssSinrDb = 41,
                    timingAdvanceMicros = 1283,
                ),
            ),
        )

        val fields = config.validate().map { it.field }.toSet()

        assertTrue(fields.contains("cellTowers[0].bands[0]"))
        assertTrue(fields.contains("cellTowers[0].bandwidthKhz"))
        assertTrue(fields.contains("cellTowers[0].additionalPlmns[0]"))
        assertTrue(fields.contains("cellTowers[0].rsrpDbm"))
        assertTrue(fields.contains("cellTowers[0].rsrqDb"))
        assertTrue(fields.contains("cellTowers[0].rssnrDb"))
        assertTrue(fields.contains("cellTowers[0].cqiTableIndex"))
        assertTrue(fields.contains("cellTowers[0].cqi"))
        assertTrue(fields.contains("cellTowers[0].timingAdvance"))
        assertTrue(fields.contains("cellTowers[1].csiRsrpDbm"))
        assertTrue(fields.contains("cellTowers[1].csiRsrqDb"))
        assertTrue(fields.contains("cellTowers[1].csiSinrDb"))
        assertTrue(fields.contains("cellTowers[1].csiCqiTableIndex"))
        assertTrue(fields.contains("cellTowers[1].csiCqiReport[0]"))
        assertTrue(fields.contains("cellTowers[1].ssRsrpDbm"))
        assertTrue(fields.contains("cellTowers[1].ssRsrqDb"))
        assertTrue(fields.contains("cellTowers[1].ssSinrDb"))
        assertTrue(fields.contains("cellTowers[1].timingAdvanceMicros"))
    }

    @Test
    fun rejectsOversizedNfcReplayPayload() {
        val payload = "AB".repeat(5)
        val config = MockDomainConfig(
            nfcReplays = listOf(
                NfcReplayProfile(
                    uidHex = "01020304",
                    ndefPayloadHex = payload,
                    maxPayloadBytes = 4,
                )
            )
        )

        assertFalse(config.isValid())
        assertEquals(MockDomain.NFC, config.validate().single().domain)
    }

    @Test
    fun validatesBleExtendedScanFields() {
        val config = MockDomainConfig(
            bleDevices = listOf(
                BleDeviceProfile(
                    address = "02:00:00:00:00:04",
                    rssiDbm = -70,
                    txPowerDbm = 200,
                    primaryPhy = 4,
                    secondaryPhy = -1,
                    advertisingSid = 16,
                    periodicAdvertisingInterval = 70_000,
                    dataStatus = 1,
                )
            )
        )

        val fields = config.validate().map { it.field }.toSet()

        assertEquals(
            setOf(
                "bleDevices[0].txPowerDbm",
                "bleDevices[0].primaryPhy",
                "bleDevices[0].secondaryPhy",
                "bleDevices[0].advertisingSid",
                "bleDevices[0].periodicAdvertisingInterval",
                "bleDevices[0].dataStatus",
            ),
            fields,
        )
    }

    @Test
    fun validatesBleUuidFields() {
        val config = MockDomainConfig(
            bleDevices = listOf(
                BleDeviceProfile(
                    address = "02:00:00:00:00:04",
                    serviceUuids = listOf("not-a-uuid"),
                    serviceSolicitationUuids = listOf("0000180f-0000-1000-8000-00805f9b34fb", "bad"),
                )
            )
        )

        val fields = config.validate().map { it.field }.toSet()

        assertEquals(
            setOf(
                "bleDevices[0].serviceUuids[0]",
                "bleDevices[0].serviceSolicitationUuids[1]",
            ),
            fields,
        )
    }

    @Test
    fun domainSwitchRequiresRuntimeModeAndNoKillSwitch() {
        val active = DomainSwitch(
            domain = MockDomain.BLE,
            runtimeEnabled = true,
            killSwitch = false,
            mode = MockMode.REPLACE_WITH_PROFILE,
        )
        val killed = active.copy(killSwitch = true)
        val passthrough = active.copy(mode = MockMode.PASSTHROUGH)

        assertTrue(active.active)
        assertFalse(killed.active)
        assertTrue(passthrough.active)
    }

    @Test
    fun domainSwitchReportsLifecycleStates() {
        val configured = DomainSwitch(
            domain = MockDomain.WLAN_SCAN,
            runtimeEnabled = false,
            mode = MockMode.REPLACE_WITH_PROFILE,
        )
        val armed = configured.copy(runtimeEnabled = true, mode = MockMode.PASSTHROUGH)
        val active = configured.copy(runtimeEnabled = true, mode = MockMode.REPLACE_WITH_PROFILE)
        val faulted = active.copy(faulted = true)
        val domainDisabled = active.copy(domainGuardDisabled = true)
        val buildDisabled = active.copy(buildTimeEnabled = false)

        assertEquals(DomainLifecycleState.CONFIGURED, configured.lifecycleState)
        assertEquals(DomainLifecycleState.ARMED, armed.lifecycleState)
        assertEquals(DomainLifecycleState.ACTIVE, active.lifecycleState)
        assertEquals(DomainLifecycleState.FAULTED, faulted.lifecycleState)
        assertEquals(DomainLifecycleState.DOMAIN_DISABLED, domainDisabled.lifecycleState)
        assertEquals(DomainLifecycleState.DISABLED, buildDisabled.lifecycleState)
    }

    @Test
    fun migrationNormalizesSchemaAndMissingSwitches() {
        val migrated = MockDomainConfig(
            schemaVersion = 1,
            switches = mapOf(
                MockDomain.BLE to DomainSwitch(
                    domain = MockDomain.NFC,
                    runtimeEnabled = true,
                    mode = MockMode.REPLACE_WITH_PROFILE,
                )
            )
        ).migrateToCurrent()

        assertEquals(MockDomainConfig.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(MockDomain.BLE, migrated.switches.getValue(MockDomain.BLE).domain)
        assertTrue(MockDomain.entries.all { migrated.switches.containsKey(it) })
    }

    @Test
    fun legalizeClampsNumericFieldsAndKeepsInvalidIdentityEvidenceVisible() {
        val legalized = MockDomainConfig(
            location = LocationProfile(
                latitude = 120.0,
                longitude = -220.0,
                speedMetersPerSecond = -1f,
                bearingDegrees = 720f,
            ),
            wifiConnected = WifiConnectedProfile(
                bssid = "not-a-mac",
                rssiDbm = -200,
                frequencyMhz = 9000,
                linkSpeedMbps = -5,
            ),
            wifiScan = WifiScanProfile(
                maxEntries = 1,
                entries = listOf(
                    WifiScanEntry("a", "02:00:00:00:00:01", -200, 0),
                    WifiScanEntry("b", "02:00:00:00:00:02", -10, 2412),
                ),
            ),
            nfcReplays = listOf(NfcReplayProfile(maxPayloadBytes = 10_000)),
        ).legalize()

        assertEquals(90.0, legalized.location.latitude, 0.0)
        assertEquals(-180.0, legalized.location.longitude, 0.0)
        assertEquals(0f, legalized.location.speedMetersPerSecond)
        assertEquals(360f, legalized.location.bearingDegrees)
        assertEquals(-127, legalized.wifiConnected.rssiDbm)
        assertEquals(8000, legalized.wifiConnected.frequencyMhz)
        assertEquals(0, legalized.wifiConnected.linkSpeedMbps)
        assertEquals(1, legalized.wifiScan.entries.size)
        assertEquals(4096, legalized.nfcReplays.single().maxPayloadBytes)
        assertEquals(listOf("bssid"), legalized.validate().map { it.field })
    }

    @Test
    fun storesEvidenceRefsByDomain() {
        val evidence = EvidenceRef(
            domain = MockDomain.NFC,
            field = "NfcService.dispatchTagEndpoint",
            source = EvidenceSource.ROM_STATIC_ANALYSIS,
            ref = ".tmp/static-probe/nfc",
        )
        val assumption = EvidenceRef(
            domain = MockDomain.NFC,
            field = "vendor.branch",
            source = EvidenceSource.MANUAL_ASSUMPTION,
            ref = "unverified",
        )
        val config = MockDomainConfig(evidenceRefs = mapOf(MockDomain.NFC to listOf(evidence, assumption)))

        assertEquals(listOf(evidence, assumption), config.evidenceFor(MockDomain.NFC))
        assertTrue(evidence.releaseEnabled)
        assertFalse(assumption.releaseEnabled)
    }

    @Test
    fun validatesReleaseEnabledManualEvidenceAssumptions() {
        val config = MockDomainConfig(
            evidenceRefs = mapOf(
                MockDomain.NFC to listOf(
                    EvidenceRef(
                        domain = MockDomain.NFC,
                        field = "vendor.branch",
                        source = EvidenceSource.MANUAL_ASSUMPTION,
                        ref = "unverified",
                        releaseEnabled = true,
                    )
                )
            )
        )

        val issue = config.validate().single()

        assertEquals(MockDomain.NFC, issue.domain)
        assertEquals("evidenceRefs[NFC][0].releaseEnabled", issue.field)
        assertFalse(config.legalize().evidenceFor(MockDomain.NFC).single().releaseEnabled)
        assertTrue(config.legalize().isValid())
    }

    @Test
    fun jsonExportUsesVersionedSnakeCaseSchema() {
        val json = JSONObject(MockDomainConfigJson.exportJson(MockDomainConfig()))

        assertEquals(MockDomainConfig.CURRENT_SCHEMA_VERSION, json.getInt("schema_version"))
        assertTrue(json.has("wifi_connected"))
        assertTrue(json.has("wifi_scan"))
        assertTrue(json.has("evidence_refs"))
    }

    @Test
    fun jsonImportExportRoundTripsDomainProfiles() {
        val config = MockDomainConfig(
            switches = mapOf(
                MockDomain.LOCATION to DomainSwitch(
                    domain = MockDomain.LOCATION,
                    runtimeEnabled = true,
                    mode = MockMode.REPLACE_WITH_PROFILE,
                ),
                MockDomain.WLAN_SCAN to DomainSwitch(
                    domain = MockDomain.WLAN_SCAN,
                    runtimeEnabled = true,
                    mode = MockMode.PASSTHROUGH,
                ),
            ),
            location = LocationProfile(
                provider = "gps",
                latitude = 31.2304,
                longitude = 121.4737,
                altitudeMeters = 12.5,
                speedMetersPerSecond = 2.5f,
                bearingDegrees = 90f,
                horizontalAccuracyMeters = 8.5f,
                preserveMockFlag = false,
            ),
            gnss = GnssProfile(
                minSatelliteCount = 18,
                includeMeasurements = true,
                includeNavigationMessages = true,
            ),
            nmea = NmeaProfile(enabled = true, maxSentenceBytes = 512),
            wifiConnected = WifiConnectedProfile(
                ssid = "LabNet",
                bssid = "02:00:00:00:00:01",
                macAddress = "02:00:00:00:00:02",
                ipv4Address = "192.168.1.2",
                rssiDbm = -50,
                linkSpeedMbps = 433,
                frequencyMhz = 5200,
                securityType = "WPA2",
            ),
            wifiScan = WifiScanProfile(
                entries = listOf(WifiScanEntry("Cafe", "02:00:00:00:00:03", -60, 2412, "[WPA2]", "ax", 1)),
                maxEntries = 4,
            ),
            cellTowers = listOf(
                CellTowerProfile(
                    rat = CellRat.NR,
                    mcc = "460",
                    mnc = "01",
                    tacOrLac = 100,
                    cellId = 200,
                    pciOrPsc = 10,
                    arfcn = 635334,
                    bands = listOf(78),
                    additionalPlmns = listOf("46001"),
                    rssiDbm = -90,
                    csiRsrpDbm = -91,
                    csiRsrqDb = -12,
                    csiSinrDb = 10,
                    csiCqiTableIndex = 1,
                    csiCqiReport = listOf(7),
                    ssRsrpDbm = -89,
                    ssRsrqDb = -10,
                    ssSinrDb = 14,
                    timingAdvanceMicros = 12,
                )
            ),
            bleDevices = listOf(
                BleDeviceProfile(
                    address = "02:00:00:00:00:04",
                    name = "Beacon",
                    rssiDbm = -70,
                    serviceUuids = listOf("0000180d-0000-1000-8000-00805f9b34fb"),
                    serviceSolicitationUuids = listOf("0000180f-0000-1000-8000-00805f9b34fb"),
                    manufacturerDataHex = "0102",
                    serviceDataHex = "AABB",
                    txPowerDbm = -12,
                    primaryPhy = 1,
                    secondaryPhy = 2,
                    advertisingSid = 7,
                    periodicAdvertisingInterval = 160,
                    dataStatus = 2,
                    legacy = false,
                    connectable = true,
                    bonded = true,
                    connected = true,
                )
            ),
            nfcReplays = listOf(
                NfcReplayProfile(
                    label = "test tag",
                    uidHex = "01020304",
                    ndefPayloadHex = "D1010C55036578616D706C652E636F6D",
                    techList = listOf("android.nfc.tech.Ndef"),
                    maxPayloadBytes = 256,
                )
            ),
            evidenceRefs = mapOf(
                MockDomain.WLAN_SCAN to listOf(
                    EvidenceRef(
                        domain = MockDomain.WLAN_SCAN,
                        field = "ScanResult.frequency",
                        source = EvidenceSource.AOSP,
                        ref = "AOSP ScanResult",
                    )
                )
            ),
        )

        val imported = MockDomainConfigJson.importJson(MockDomainConfigJson.exportJson(config))

        assertEquals(config.migrateToCurrent().legalize(), imported)
    }

    @Test
    fun jsonImportMigratesDefaultsAndLegalizesNumericFields() {
        val json = JSONObject()
            .put("schema_version", 1)
            .put("location", JSONObject().put("latitude", 120.0).put("longitude", -220.0))
            .put("gnss", JSONObject().put("min_satellite_count", 100))
            .put("wifi_connected", JSONObject().put("rssi_dbm", -200).put("frequency_mhz", 9000))

        val imported = MockDomainConfigJson.importJson(json.toString())

        assertEquals(MockDomainConfig.CURRENT_SCHEMA_VERSION, imported.schemaVersion)
        assertTrue(MockDomain.entries.all { imported.switches.containsKey(it) })
        assertEquals(90.0, imported.location.latitude, 0.0)
        assertEquals(-180.0, imported.location.longitude, 0.0)
        assertEquals(64, imported.gnss.minSatelliteCount)
        assertEquals(-127, imported.wifiConnected.rssiDbm)
        assertEquals(8000, imported.wifiConnected.frequencyMhz)
        assertTrue(imported.isValid())
    }

    @Test
    fun jsonImportAndExportDisableManualAssumptionReleaseRefs() {
        val json = JSONObject()
            .put(
                "evidence_refs",
                JSONObject().put(
                    "NFC",
                    JSONArray().put(
                        JSONObject()
                            .put("domain", "NFC")
                            .put("field", "vendor.branch")
                            .put("source", "MANUAL_ASSUMPTION")
                            .put("ref", "unverified")
                            .put("release_enabled", true)
                    )
                )
            )

        val raw = MockDomainConfigJson.importJson(json.toString(), legalize = false)
        val imported = MockDomainConfigJson.importJson(json.toString())
        val exported = JSONObject(MockDomainConfigJson.exportJson(raw))
            .getJSONObject("evidence_refs")
            .getJSONArray("NFC")
            .getJSONObject(0)

        assertEquals("evidenceRefs[NFC][0].releaseEnabled", raw.validate().single().field)
        assertFalse(imported.evidenceFor(MockDomain.NFC).single().releaseEnabled)
        assertTrue(imported.isValid())
        assertFalse(exported.getBoolean("release_enabled"))
    }
}
