package tv.kyshu.xposed.config

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MockDomain {
    LOCATION,
    GNSS,
    NMEA,
    SENSOR,
    WLAN_CONNECTED,
    WLAN_SCAN,
    CELL,
    BLE,
    NFC,
}

enum class MockMode {
    OFF,
    PASSTHROUGH,
    MASK_REAL,
    REPLACE_WITH_PROFILE,
}

enum class DomainLifecycleState {
    DISABLED,
    CONFIGURED,
    ARMED,
    ACTIVE,
    FAULTED,
    DOMAIN_DISABLED,
}

enum class EvidenceSource {
    AOSP,
    ROM_STATIC_ANALYSIS,
    READ_ONLY_DUMPSYS,
    REFERENCE_PROJECT,
    MANUAL_ASSUMPTION,
}

enum class CellRat {
    GSM,
    WCDMA,
    LTE,
    NR,
    CDMA,
    TD_SCDMA,
}

data class DomainSwitch(
    val domain: MockDomain,
    val buildTimeEnabled: Boolean = true,
    val runtimeEnabled: Boolean = false,
    val killSwitch: Boolean = false,
    val domainGuardDisabled: Boolean = false,
    val faulted: Boolean = false,
    val mode: MockMode = MockMode.OFF,
) {
    val active: Boolean
        get() = lifecycleState == DomainLifecycleState.ARMED ||
            lifecycleState == DomainLifecycleState.ACTIVE

    val lifecycleState: DomainLifecycleState
        get() = when {
            !buildTimeEnabled || killSwitch || mode == MockMode.OFF -> DomainLifecycleState.DISABLED
            domainGuardDisabled -> DomainLifecycleState.DOMAIN_DISABLED
            faulted -> DomainLifecycleState.FAULTED
            !runtimeEnabled -> DomainLifecycleState.CONFIGURED
            mode == MockMode.PASSTHROUGH -> DomainLifecycleState.ARMED
            else -> DomainLifecycleState.ACTIVE
        }
}

data class EvidenceRef(
    val domain: MockDomain,
    val field: String,
    val source: EvidenceSource,
    val ref: String,
    val releaseEnabled: Boolean = source != EvidenceSource.MANUAL_ASSUMPTION,
) {
    fun legalize(): EvidenceRef =
        if (source == EvidenceSource.MANUAL_ASSUMPTION && releaseEnabled) {
            copy(releaseEnabled = false)
        } else {
            this
        }
}

data class LocationProfile(
    val provider: String = "gps",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeMeters: Double = 0.0,
    val speedMetersPerSecond: Float = 0f,
    val bearingDegrees: Float = 0f,
    val horizontalAccuracyMeters: Float = 25f,
    val preserveMockFlag: Boolean = true,
)

data class GnssProfile(
    val minSatelliteCount: Int = 12,
    val includeMeasurements: Boolean = false,
    val includeNavigationMessages: Boolean = false,
)

data class NmeaProfile(
    val enabled: Boolean = false,
    val maxSentenceBytes: Int = 256,
)

data class WifiConnectedProfile(
    val ssid: String = "",
    val bssid: String = "",
    val macAddress: String = "",
    val ipv4Address: String = "",
    val rssiDbm: Int = -65,
    val linkSpeedMbps: Int = 72,
    val frequencyMhz: Int = 2412,
    val securityType: String = "",
)

data class WifiScanEntry(
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val capabilities: String = "",
    val standard: String = "",
    val channelWidth: Int = 0,
)

data class WifiScanProfile(
    val entries: List<WifiScanEntry> = emptyList(),
    val maxEntries: Int = 32,
)

data class CellTowerProfile(
    val rat: CellRat = CellRat.LTE,
    val mcc: String = "",
    val mnc: String = "",
    val tacOrLac: Int? = null,
    val cellId: Long? = null,
    val pciOrPsc: Int? = null,
    val arfcn: Int? = null,
    val bands: List<Int> = emptyList(),
    val bandwidthKhz: Int? = null,
    val additionalPlmns: List<String> = emptyList(),
    val rssiDbm: Int = -95,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val rssnrDb: Int? = null,
    val cqiTableIndex: Int? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,
    val csiRsrpDbm: Int? = null,
    val csiRsrqDb: Int? = null,
    val csiSinrDb: Int? = null,
    val csiCqiTableIndex: Int? = null,
    val csiCqiReport: List<Int> = emptyList(),
    val ssRsrpDbm: Int? = null,
    val ssRsrqDb: Int? = null,
    val ssSinrDb: Int? = null,
    val timingAdvanceMicros: Int? = null,
    val registered: Boolean = true,
)

data class BleDeviceProfile(
    val address: String = "",
    val name: String = "",
    val rssiDbm: Int = -70,
    val serviceUuids: List<String> = emptyList(),
    val serviceSolicitationUuids: List<String> = emptyList(),
    val manufacturerDataHex: String = "",
    val serviceDataHex: String = "",
    val txPowerDbm: Int? = null,
    val primaryPhy: Int = 1,
    val secondaryPhy: Int = 0,
    val advertisingSid: Int? = null,
    val periodicAdvertisingInterval: Int? = null,
    val dataStatus: Int = 0,
    val legacy: Boolean = true,
    val connectable: Boolean = true,
    val bonded: Boolean = false,
    val connected: Boolean = false,
)

data class NfcReplayProfile(
    val label: String = "",
    val tagType: String = "NDEF",
    val uidHex: String = "",
    val ndefPayloadHex: String = "",
    val techList: List<String> = emptyList(),
    val maxPayloadBytes: Int = 4096,
)

enum class CoordinateSystem { WGS84, GCJ02, BD09 }

data class MotionNoiseConfig(
    val enabled: Boolean = true,
    val horizontalRadiusMeters: Float = 5f,
    val accuracyDriftMeters: Float = 1.5f,
    val altitudeDriftMeters: Float = 0.7f,
)

data class MockDomainConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val switches: Map<MockDomain, DomainSwitch> = MockDomain.entries.associateWith {
        DomainSwitch(domain = it)
    },
    val location: LocationProfile = LocationProfile(),
    val gnss: GnssProfile = GnssProfile(),
    val nmea: NmeaProfile = NmeaProfile(),
    val wifiConnected: WifiConnectedProfile = WifiConnectedProfile(),
    val wifiScan: WifiScanProfile = WifiScanProfile(),
    val cellTowers: List<CellTowerProfile> = emptyList(),
    val bleDevices: List<BleDeviceProfile> = emptyList(),
    val nfcReplays: List<NfcReplayProfile> = emptyList(),
    val coordinateOverrides: Map<String, CoordinateSystem> = emptyMap(),
    val motionNoise: MotionNoiseConfig = MotionNoiseConfig(),
    val evidenceRefs: Map<MockDomain, List<EvidenceRef>> = emptyMap(),
) {
    fun migrateToCurrent(): MockDomainConfig = when {
        schemaVersion >= CURRENT_SCHEMA_VERSION -> copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            switches = normalizedSwitches(switches),
        )
        schemaVersion == 1 -> copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            switches = normalizedSwitches(switches),
        )
        else -> copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            switches = normalizedSwitches(switches),
        )
    }

    fun legalize(): MockDomainConfig = migrateToCurrent().let { current ->
        current.copy(
            location = current.location.copy(
                latitude = current.location.latitude.coerceIn(-90.0, 90.0),
                longitude = current.location.longitude.coerceIn(-180.0, 180.0),
                horizontalAccuracyMeters = current.location.horizontalAccuracyMeters.coerceAtLeast(0f),
                speedMetersPerSecond = current.location.speedMetersPerSecond.coerceAtLeast(0f),
                bearingDegrees = current.location.bearingDegrees.coerceIn(0f, 360f),
            ),
            gnss = current.gnss.copy(minSatelliteCount = current.gnss.minSatelliteCount.coerceIn(0, 64)),
            nmea = current.nmea.copy(maxSentenceBytes = current.nmea.maxSentenceBytes.coerceIn(1, 1024)),
            wifiConnected = current.wifiConnected.copy(
                rssiDbm = current.wifiConnected.rssiDbm.coerceIn(-127, 0),
                frequencyMhz = current.wifiConnected.frequencyMhz.coerceIn(1, 8000),
                linkSpeedMbps = current.wifiConnected.linkSpeedMbps.coerceAtLeast(0),
            ),
            wifiScan = current.wifiScan.copy(
                maxEntries = current.wifiScan.maxEntries.coerceIn(0, 32),
                entries = current.wifiScan.entries
                    .take(current.wifiScan.maxEntries.coerceIn(0, 32))
                    .map { entry ->
                        entry.copy(
                            rssiDbm = entry.rssiDbm.coerceIn(-127, 0),
                            frequencyMhz = entry.frequencyMhz.coerceIn(1, 8000),
                        )
                    },
            ),
            cellTowers = current.cellTowers.map { tower ->
                tower.copy(
                    tacOrLac = tower.tacOrLac?.coerceAtLeast(0),
                    cellId = tower.cellId?.coerceAtLeast(0),
                    rssiDbm = tower.rssiDbm.coerceIn(-140, 0),
                )
            },
            bleDevices = current.bleDevices.map { device ->
                device.copy(
                    rssiDbm = device.rssiDbm.coerceIn(-127, 20),
                    txPowerDbm = device.txPowerDbm?.coerceIn(-127, 126),
                    primaryPhy = device.primaryPhy.coerceIn(0, 3),
                    secondaryPhy = device.secondaryPhy.coerceIn(0, 3),
                    advertisingSid = device.advertisingSid?.coerceIn(0, 15),
                    periodicAdvertisingInterval = device.periodicAdvertisingInterval?.coerceIn(0, 65535),
                    dataStatus = if (device.dataStatus == 2) 2 else 0,
                )
            },
            coordinateOverrides = current.coordinateOverrides.entries
                .filter { (packageName, _) -> PACKAGE_NAME.matches(packageName) }
                .take(256)
                .associate { it.toPair() },
            motionNoise = current.motionNoise.copy(
                horizontalRadiusMeters = current.motionNoise.horizontalRadiusMeters.coerceIn(0f, 100f),
                accuracyDriftMeters = current.motionNoise.accuracyDriftMeters.coerceIn(0f, 50f),
                altitudeDriftMeters = current.motionNoise.altitudeDriftMeters.coerceIn(0f, 50f),
            ),
            nfcReplays = current.nfcReplays.map { replay ->
                replay.copy(maxPayloadBytes = replay.maxPayloadBytes.coerceIn(1, 4096))
            },
            evidenceRefs = current.evidenceRefs.mapValues { (_, refs) ->
                refs.map { it.legalize() }
            },
        )
    }

    fun validate(): List<ConfigIssue> {
        val issues = mutableListOf<ConfigIssue>()

        if (schemaVersion !in 1..CURRENT_SCHEMA_VERSION) {
            issues += issue(MockDomain.LOCATION, "schemaVersion", "unsupported schema version")
        }
        switches.forEach { (domain, switch) ->
            if (switch.domain != domain) {
                issues += issue(domain, "switches[$domain].domain", "domain key mismatch")
            }
        }
        issues += validateLocation(location)
        issues += validateGnss(gnss)
        issues += validateNmea(nmea)
        issues += validateWifiConnected(wifiConnected)
        issues += validateWifiScan(wifiScan)
        cellTowers.forEachIndexed { index, profile -> issues += validateCell(index, profile) }
        bleDevices.forEachIndexed { index, profile -> issues += validateBle(index, profile) }
        nfcReplays.forEachIndexed { index, profile -> issues += validateNfc(index, profile) }
        coordinateOverrides.keys.forEach { packageName ->
            if (!PACKAGE_NAME.matches(packageName)) issues += issue(MockDomain.LOCATION, "coordinateOverrides[$packageName]", "invalid package name")
        }
        if (coordinateOverrides.size > 256) issues += issue(MockDomain.LOCATION, "coordinateOverrides", "too many entries")
        if (motionNoise.horizontalRadiusMeters !in 0f..100f) issues += issue(MockDomain.LOCATION, "motionNoise.horizontalRadiusMeters", "out of range")
        if (motionNoise.accuracyDriftMeters !in 0f..50f) issues += issue(MockDomain.LOCATION, "motionNoise.accuracyDriftMeters", "out of range")
        if (motionNoise.altitudeDriftMeters !in 0f..50f) issues += issue(MockDomain.LOCATION, "motionNoise.altitudeDriftMeters", "out of range")
        issues += validateEvidenceRefs(evidenceRefs)

        return issues
    }

    fun isValid(): Boolean = validate().isEmpty()

    fun lifecycleState(domain: MockDomain): DomainLifecycleState =
        normalizedSwitches(switches).getValue(domain).lifecycleState

    fun evidenceFor(domain: MockDomain): List<EvidenceRef> = evidenceRefs[domain].orEmpty()

    private fun validateLocation(profile: LocationProfile): List<ConfigIssue> = buildList {
        if (profile.latitude !in -90.0..90.0) add(issue(MockDomain.LOCATION, "latitude", "out of range"))
        if (profile.longitude !in -180.0..180.0) add(issue(MockDomain.LOCATION, "longitude", "out of range"))
        if (profile.horizontalAccuracyMeters < 0f) add(issue(MockDomain.LOCATION, "horizontalAccuracyMeters", "must be >= 0"))
        if (profile.speedMetersPerSecond < 0f) add(issue(MockDomain.LOCATION, "speedMetersPerSecond", "must be >= 0"))
        if (profile.bearingDegrees !in 0f..360f) add(issue(MockDomain.LOCATION, "bearingDegrees", "out of range"))
    }

    private fun validateGnss(profile: GnssProfile): List<ConfigIssue> = buildList {
        if (profile.minSatelliteCount !in 0..64) add(issue(MockDomain.GNSS, "minSatelliteCount", "out of range"))
    }

    private fun validateNmea(profile: NmeaProfile): List<ConfigIssue> = buildList {
        if (profile.maxSentenceBytes !in 1..1024) add(issue(MockDomain.NMEA, "maxSentenceBytes", "out of range"))
    }

    private fun validateWifiConnected(profile: WifiConnectedProfile): List<ConfigIssue> = buildList {
        if (profile.bssid.isNotBlank() && !profile.bssid.isMacAddress()) add(issue(MockDomain.WLAN_CONNECTED, "bssid", "invalid mac"))
        if (profile.macAddress.isNotBlank() && !profile.macAddress.isMacAddress()) add(issue(MockDomain.WLAN_CONNECTED, "macAddress", "invalid mac"))
        if (profile.ipv4Address.isNotBlank() && !profile.ipv4Address.isIpv4Address()) add(issue(MockDomain.WLAN_CONNECTED, "ipv4Address", "invalid ipv4"))
        if (profile.rssiDbm !in -127..0) add(issue(MockDomain.WLAN_CONNECTED, "rssiDbm", "out of range"))
        if (profile.frequencyMhz !in 1..8000) add(issue(MockDomain.WLAN_CONNECTED, "frequencyMhz", "out of range"))
        if (profile.linkSpeedMbps < 0) add(issue(MockDomain.WLAN_CONNECTED, "linkSpeedMbps", "must be >= 0"))
    }

    private fun validateWifiScan(profile: WifiScanProfile): List<ConfigIssue> = buildList {
        if (profile.maxEntries !in 0..32) add(issue(MockDomain.WLAN_SCAN, "maxEntries", "out of range"))
        if (profile.entries.size > profile.maxEntries) add(issue(MockDomain.WLAN_SCAN, "entries", "too many entries"))
        profile.entries.forEachIndexed { index, entry ->
            if (entry.bssid.isNotBlank() && !entry.bssid.isMacAddress()) add(issue(MockDomain.WLAN_SCAN, "entries[$index].bssid", "invalid mac"))
            if (entry.rssiDbm !in -127..0) add(issue(MockDomain.WLAN_SCAN, "entries[$index].rssiDbm", "out of range"))
            if (entry.frequencyMhz !in 1..8000) add(issue(MockDomain.WLAN_SCAN, "entries[$index].frequencyMhz", "out of range"))
        }
    }

    private fun validateCell(index: Int, profile: CellTowerProfile): List<ConfigIssue> = buildList {
        if (!profile.mcc.matches(Regex("\\d{3}"))) add(issue(MockDomain.CELL, "cellTowers[$index].mcc", "invalid mcc"))
        if (!profile.mnc.matches(Regex("\\d{2,3}"))) add(issue(MockDomain.CELL, "cellTowers[$index].mnc", "invalid mnc"))
        if (profile.rssiDbm !in -140..0) add(issue(MockDomain.CELL, "cellTowers[$index].rssiDbm", "out of range"))
        profile.tacOrLac?.let { value ->
            if (value !in 0..profile.rat.tacOrLacMax()) {
                add(issue(MockDomain.CELL, "cellTowers[$index].tacOrLac", "out of range for ${profile.rat}"))
            }
        }
        profile.cellId?.let { value ->
            if (value !in 0..profile.rat.cellIdMax()) {
                add(issue(MockDomain.CELL, "cellTowers[$index].cellId", "out of range for ${profile.rat}"))
            }
        }
        profile.pciOrPsc?.let { value ->
            if (value !in 0..profile.rat.pciOrPscMax()) {
                add(issue(MockDomain.CELL, "cellTowers[$index].pciOrPsc", "out of range for ${profile.rat}"))
            }
        }
        profile.arfcn?.let { value ->
            if (value !in 0..profile.rat.arfcnMax()) {
                add(issue(MockDomain.CELL, "cellTowers[$index].arfcn", "out of range for ${profile.rat}"))
            }
        }
        profile.bands.forEachIndexed { bandIndex, band ->
            if (band !in 1..1024) add(issue(MockDomain.CELL, "cellTowers[$index].bands[$bandIndex]", "out of range"))
        }
        profile.bandwidthKhz?.let {
            if (it !in 0..20_000) add(issue(MockDomain.CELL, "cellTowers[$index].bandwidthKhz", "out of range"))
        }
        profile.additionalPlmns.forEachIndexed { plmnIndex, plmn ->
            if (!plmn.matches(plmnRegex)) add(issue(MockDomain.CELL, "cellTowers[$index].additionalPlmns[$plmnIndex]", "invalid plmn"))
        }
        profile.rsrpDbm?.let {
            if (it !in profile.rat.rsrpRange()) add(issue(MockDomain.CELL, "cellTowers[$index].rsrpDbm", "out of range for ${profile.rat}"))
        }
        profile.rsrqDb?.let {
            if (it !in profile.rat.rsrqRange()) add(issue(MockDomain.CELL, "cellTowers[$index].rsrqDb", "out of range for ${profile.rat}"))
        }
        profile.rssnrDb?.let {
            if (it !in profile.rat.rssnrRange()) add(issue(MockDomain.CELL, "cellTowers[$index].rssnrDb", "out of range for ${profile.rat}"))
        }
        profile.cqiTableIndex?.let {
            if (it !in 1..6) add(issue(MockDomain.CELL, "cellTowers[$index].cqiTableIndex", "out of range"))
        }
        profile.cqi?.let {
            if (it !in 0..15) add(issue(MockDomain.CELL, "cellTowers[$index].cqi", "out of range"))
        }
        profile.timingAdvance?.let {
            if (it !in 0..1282) add(issue(MockDomain.CELL, "cellTowers[$index].timingAdvance", "out of range"))
        }
        profile.csiRsrpDbm?.let {
            if (it !in -156..-31) add(issue(MockDomain.CELL, "cellTowers[$index].csiRsrpDbm", "out of range"))
        }
        profile.csiRsrqDb?.let {
            if (it !in -20..-3) add(issue(MockDomain.CELL, "cellTowers[$index].csiRsrqDb", "out of range"))
        }
        profile.csiSinrDb?.let {
            if (it !in -23..23) add(issue(MockDomain.CELL, "cellTowers[$index].csiSinrDb", "out of range"))
        }
        profile.csiCqiTableIndex?.let {
            if (it !in 1..3) add(issue(MockDomain.CELL, "cellTowers[$index].csiCqiTableIndex", "out of range"))
        }
        profile.csiCqiReport.forEachIndexed { cqiIndex, cqi ->
            if (cqi !in 0..15) add(issue(MockDomain.CELL, "cellTowers[$index].csiCqiReport[$cqiIndex]", "out of range"))
        }
        profile.ssRsrpDbm?.let {
            if (it !in -156..-31) add(issue(MockDomain.CELL, "cellTowers[$index].ssRsrpDbm", "out of range"))
        }
        profile.ssRsrqDb?.let {
            if (it !in -43..20) add(issue(MockDomain.CELL, "cellTowers[$index].ssRsrqDb", "out of range"))
        }
        profile.ssSinrDb?.let {
            if (it !in -23..40) add(issue(MockDomain.CELL, "cellTowers[$index].ssSinrDb", "out of range"))
        }
        profile.timingAdvanceMicros?.let {
            if (it !in 0..1282) add(issue(MockDomain.CELL, "cellTowers[$index].timingAdvanceMicros", "out of range"))
        }
    }

    private fun validateBle(index: Int, profile: BleDeviceProfile): List<ConfigIssue> = buildList {
        if (profile.address.isNotBlank() && !profile.address.isMacAddress()) add(issue(MockDomain.BLE, "bleDevices[$index].address", "invalid mac"))
        if (profile.rssiDbm !in -127..20) add(issue(MockDomain.BLE, "bleDevices[$index].rssiDbm", "out of range"))
        profile.serviceUuids.forEachIndexed { uuidIndex, uuid ->
            if (!uuid.isUuid()) add(issue(MockDomain.BLE, "bleDevices[$index].serviceUuids[$uuidIndex]", "invalid uuid"))
        }
        profile.serviceSolicitationUuids.forEachIndexed { uuidIndex, uuid ->
            if (!uuid.isUuid()) {
                add(issue(MockDomain.BLE, "bleDevices[$index].serviceSolicitationUuids[$uuidIndex]", "invalid uuid"))
            }
        }
        if (!profile.manufacturerDataHex.isHexBytes()) add(issue(MockDomain.BLE, "bleDevices[$index].manufacturerDataHex", "invalid hex"))
        if (!profile.serviceDataHex.isHexBytes()) add(issue(MockDomain.BLE, "bleDevices[$index].serviceDataHex", "invalid hex"))
        profile.txPowerDbm?.let { if (it !in -127..126) add(issue(MockDomain.BLE, "bleDevices[$index].txPowerDbm", "out of range")) }
        if (profile.primaryPhy !in 0..3) add(issue(MockDomain.BLE, "bleDevices[$index].primaryPhy", "out of range"))
        if (profile.secondaryPhy !in 0..3) add(issue(MockDomain.BLE, "bleDevices[$index].secondaryPhy", "out of range"))
        profile.advertisingSid?.let { if (it !in 0..15) add(issue(MockDomain.BLE, "bleDevices[$index].advertisingSid", "out of range")) }
        profile.periodicAdvertisingInterval?.let {
            if (it !in 0..65535) add(issue(MockDomain.BLE, "bleDevices[$index].periodicAdvertisingInterval", "out of range"))
        }
        if (profile.dataStatus !in setOf(0, 2)) {
            add(issue(MockDomain.BLE, "bleDevices[$index].dataStatus", "must be DATA_COMPLETE or DATA_TRUNCATED"))
        }
    }

    private fun validateNfc(index: Int, profile: NfcReplayProfile): List<ConfigIssue> = buildList {
        if (!profile.uidHex.isHexBytes()) add(issue(MockDomain.NFC, "nfcReplays[$index].uidHex", "invalid hex"))
        if (!profile.ndefPayloadHex.isHexBytes()) add(issue(MockDomain.NFC, "nfcReplays[$index].ndefPayloadHex", "invalid hex"))
        if (profile.ndefPayloadHex.hexByteCount() > profile.maxPayloadBytes) {
            add(issue(MockDomain.NFC, "nfcReplays[$index].ndefPayloadHex", "payload too large"))
        }
        if (profile.maxPayloadBytes !in 1..4096) add(issue(MockDomain.NFC, "nfcReplays[$index].maxPayloadBytes", "out of range"))
    }

    private fun validateEvidenceRefs(evidenceRefs: Map<MockDomain, List<EvidenceRef>>): List<ConfigIssue> = buildList {
        evidenceRefs.forEach { (domain, refs) ->
            refs.forEachIndexed { index, evidence ->
                if (evidence.domain != domain) {
                    add(issue(domain, "evidenceRefs[$domain][$index].domain", "domain key mismatch"))
                }
                if (evidence.source == EvidenceSource.MANUAL_ASSUMPTION && evidence.releaseEnabled) {
                    add(issue(domain, "evidenceRefs[$domain][$index].releaseEnabled", "manual assumption cannot be release enabled"))
                }
            }
        }
    }

    private fun issue(domain: MockDomain, field: String, reason: String): ConfigIssue =
        ConfigIssue(domain = domain, field = field, reason = reason)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")

        private fun normalizedSwitches(
            switches: Map<MockDomain, DomainSwitch>,
        ): Map<MockDomain, DomainSwitch> = MockDomain.entries.associateWith { domain ->
            switches[domain]?.copy(domain = domain) ?: DomainSwitch(domain = domain)
        }
    }
}

data class ConfigIssue(
    val domain: MockDomain,
    val field: String,
    val reason: String,
)

object MockDomainConfigJson {
    fun exportJson(config: MockDomainConfig): String =
        toJsonObject(config.migrateToCurrent()).toString()

    fun importJson(json: String, legalize: Boolean = true): MockDomainConfig {
        val config = fromJsonObject(JSONObject(json)).migrateToCurrent()
        return if (legalize) config.legalize() else config
    }

    fun importValidatedJson(json: String, legalize: Boolean = true): MockDomainConfig =
        importJson(json, legalize).also { config ->
            val issues = config.validate()
            if (issues.isNotEmpty()) {
                throw IllegalArgumentException("Invalid mock domain config: ${issues.toSummary()}")
            }
        }

    fun toJsonObject(config: MockDomainConfig): JSONObject = JSONObject().apply {
        put("schema_version", config.schemaVersion)
        put("switches", JSONObject().apply {
            config.switches.forEach { (domain, switch) -> put(domain.name, switch.toJson()) }
        })
        put("location", config.location.toJson())
        put("gnss", config.gnss.toJson())
        put("nmea", config.nmea.toJson())
        put("wifi_connected", config.wifiConnected.toJson())
        put("wifi_scan", config.wifiScan.toJson())
        put("cell_towers", JSONArray().apply { config.cellTowers.forEach { put(it.toJson()) } })
        put("ble_devices", JSONArray().apply { config.bleDevices.forEach { put(it.toJson()) } })
        put("nfc_replays", JSONArray().apply { config.nfcReplays.forEach { put(it.toJson()) } })
        put("coordinate_overrides", JSONObject().apply {
            config.coordinateOverrides.forEach { (packageName, system) -> put(packageName, system.name) }
        })
        put("motion_noise", config.motionNoise.toJson())
        put("evidence_refs", JSONObject().apply {
            config.evidenceRefs.forEach { (domain, refs) ->
                put(domain.name, JSONArray().apply { refs.forEach { put(it.toJson()) } })
            }
        })
    }

    fun fromJsonObject(json: JSONObject): MockDomainConfig {
        val schemaVersion = json.optIntCompat("schema_version", "schemaVersion", 1)
        return MockDomainConfig(
            schemaVersion = schemaVersion,
            switches = parseSwitches(json.optJSONObject("switches")),
            location = parseLocation(json.optJSONObject("location")),
            gnss = parseGnss(json.optJSONObject("gnss")),
            nmea = parseNmea(json.optJSONObject("nmea")),
            wifiConnected = parseWifiConnected(json.optJSONObject("wifi_connected") ?: json.optJSONObject("wifiConnected")),
            wifiScan = parseWifiScan(json.optJSONObject("wifi_scan") ?: json.optJSONObject("wifiScan")),
            cellTowers = parseArray(json.optJSONArray("cell_towers") ?: json.optJSONArray("cellTowers"), ::parseCellTower),
            bleDevices = parseArray(json.optJSONArray("ble_devices") ?: json.optJSONArray("bleDevices"), ::parseBleDevice),
            nfcReplays = parseArray(json.optJSONArray("nfc_replays") ?: json.optJSONArray("nfcReplays"), ::parseNfcReplay),
            coordinateOverrides = parseCoordinateOverrides(json.optJSONObject("coordinate_overrides") ?: json.optJSONObject("coordinateOverrides")),
            motionNoise = parseMotionNoise(json.optJSONObject("motion_noise") ?: json.optJSONObject("motionNoise")),
            evidenceRefs = parseEvidenceRefs(json.optJSONObject("evidence_refs") ?: json.optJSONObject("evidenceRefs")),
        )
    }

    private fun DomainSwitch.toJson(): JSONObject = JSONObject().apply {
        put("domain", domain.name)
        put("build_time_enabled", buildTimeEnabled)
        put("runtime_enabled", runtimeEnabled)
        put("kill_switch", killSwitch)
        put("domain_guard_disabled", domainGuardDisabled)
        put("faulted", faulted)
        put("mode", mode.name)
    }

    private fun EvidenceRef.toJson(): JSONObject {
        val safeRef = legalize()
        return JSONObject().apply {
            put("domain", safeRef.domain.name)
            put("field", safeRef.field)
            put("source", safeRef.source.name)
            put("ref", safeRef.ref)
            put("release_enabled", safeRef.releaseEnabled)
        }
    }

    private fun LocationProfile.toJson(): JSONObject = JSONObject().apply {
        put("provider", provider)
        put("latitude", latitude)
        put("longitude", longitude)
        put("altitude_meters", altitudeMeters)
        put("speed_meters_per_second", speedMetersPerSecond.toDouble())
        put("bearing_degrees", bearingDegrees.toDouble())
        put("horizontal_accuracy_meters", horizontalAccuracyMeters.toDouble())
        put("preserve_mock_flag", preserveMockFlag)
    }

    private fun MotionNoiseConfig.toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("horizontal_radius_meters", horizontalRadiusMeters.toDouble())
        put("accuracy_drift_meters", accuracyDriftMeters.toDouble())
        put("altitude_drift_meters", altitudeDriftMeters.toDouble())
    }

    private fun GnssProfile.toJson(): JSONObject = JSONObject().apply {
        put("min_satellite_count", minSatelliteCount)
        put("include_measurements", includeMeasurements)
        put("include_navigation_messages", includeNavigationMessages)
    }

    private fun NmeaProfile.toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("max_sentence_bytes", maxSentenceBytes)
    }

    private fun WifiConnectedProfile.toJson(): JSONObject = JSONObject().apply {
        put("ssid", ssid)
        put("bssid", bssid)
        put("mac_address", macAddress)
        put("ipv4_address", ipv4Address)
        put("rssi_dbm", rssiDbm)
        put("link_speed_mbps", linkSpeedMbps)
        put("frequency_mhz", frequencyMhz)
        put("security_type", securityType)
    }

    private fun WifiScanProfile.toJson(): JSONObject = JSONObject().apply {
        put("max_entries", maxEntries)
        put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
    }

    private fun WifiScanEntry.toJson(): JSONObject = JSONObject().apply {
        put("ssid", ssid)
        put("bssid", bssid)
        put("rssi_dbm", rssiDbm)
        put("frequency_mhz", frequencyMhz)
        put("capabilities", capabilities)
        put("standard", standard)
        put("channel_width", channelWidth)
    }

    private fun CellTowerProfile.toJson(): JSONObject = JSONObject().apply {
        put("rat", rat.name)
        put("mcc", mcc)
        put("mnc", mnc)
        tacOrLac?.let { put("tac_or_lac", it) }
        cellId?.let { put("cell_id", it) }
        pciOrPsc?.let { put("pci_or_psc", it) }
        arfcn?.let { put("arfcn", it) }
        put("bands", JSONArray().apply { bands.forEach { put(it) } })
        bandwidthKhz?.let { put("bandwidth_khz", it) }
        put("additional_plmns", JSONArray().apply { additionalPlmns.forEach { put(it) } })
        put("rssi_dbm", rssiDbm)
        rsrpDbm?.let { put("rsrp_dbm", it) }
        rsrqDb?.let { put("rsrq_db", it) }
        rssnrDb?.let { put("rssnr_db", it) }
        cqiTableIndex?.let { put("cqi_table_index", it) }
        cqi?.let { put("cqi", it) }
        timingAdvance?.let { put("timing_advance", it) }
        csiRsrpDbm?.let { put("csi_rsrp_dbm", it) }
        csiRsrqDb?.let { put("csi_rsrq_db", it) }
        csiSinrDb?.let { put("csi_sinr_db", it) }
        csiCqiTableIndex?.let { put("csi_cqi_table_index", it) }
        put("csi_cqi_report", JSONArray().apply { csiCqiReport.forEach { put(it) } })
        ssRsrpDbm?.let { put("ss_rsrp_dbm", it) }
        ssRsrqDb?.let { put("ss_rsrq_db", it) }
        ssSinrDb?.let { put("ss_sinr_db", it) }
        timingAdvanceMicros?.let { put("timing_advance_micros", it) }
        put("registered", registered)
    }

    private fun BleDeviceProfile.toJson(): JSONObject = JSONObject().apply {
        put("address", address)
        put("name", name)
        put("rssi_dbm", rssiDbm)
        put("service_uuids", JSONArray().apply { serviceUuids.forEach { put(it) } })
        put("service_solicitation_uuids", JSONArray().apply { serviceSolicitationUuids.forEach { put(it) } })
        put("manufacturer_data_hex", manufacturerDataHex)
        put("service_data_hex", serviceDataHex)
        txPowerDbm?.let { put("tx_power_dbm", it) }
        put("primary_phy", primaryPhy)
        put("secondary_phy", secondaryPhy)
        advertisingSid?.let { put("advertising_sid", it) }
        periodicAdvertisingInterval?.let { put("periodic_advertising_interval", it) }
        put("data_status", dataStatus)
        put("legacy", legacy)
        put("connectable", connectable)
        put("bonded", bonded)
        put("connected", connected)
    }

    private fun NfcReplayProfile.toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("tag_type", tagType)
        put("uid_hex", uidHex)
        put("ndef_payload_hex", ndefPayloadHex)
        put("tech_list", JSONArray().apply { techList.forEach { put(it) } })
        put("max_payload_bytes", maxPayloadBytes)
    }

    private fun parseSwitches(json: JSONObject?): Map<MockDomain, DomainSwitch> {
        if (json == null) return emptyMap()
        return buildMap {
            json.keys().forEachRemaining { key ->
                val domain = enumOrNull<MockDomain>(key) ?: return@forEachRemaining
                val item = json.optJSONObject(key) ?: return@forEachRemaining
                put(
                    domain,
                    DomainSwitch(
                        domain = domain,
                        buildTimeEnabled = item.optBooleanCompat("build_time_enabled", "buildTimeEnabled", true),
                        runtimeEnabled = item.optBooleanCompat("runtime_enabled", "runtimeEnabled", false),
                        killSwitch = item.optBooleanCompat("kill_switch", "killSwitch", false),
                        domainGuardDisabled = item.optBooleanCompat("domain_guard_disabled", "domainGuardDisabled", false),
                        faulted = item.optBoolean("faulted", false),
                        mode = enumOrNull<MockMode>(item.optString("mode")) ?: MockMode.OFF,
                    )
                )
            }
        }
    }

    private fun parseLocation(json: JSONObject?): LocationProfile {
        if (json == null) return LocationProfile()
        return LocationProfile(
            provider = json.optString("provider", "gps"),
            latitude = json.optDouble("latitude", 0.0),
            longitude = json.optDouble("longitude", 0.0),
            altitudeMeters = json.optDoubleCompat("altitude_meters", "altitudeMeters", 0.0),
            speedMetersPerSecond = json.optDoubleCompat("speed_meters_per_second", "speedMetersPerSecond", 0.0).toFloat(),
            bearingDegrees = json.optDoubleCompat("bearing_degrees", "bearingDegrees", 0.0).toFloat(),
            horizontalAccuracyMeters = json.optDoubleCompat("horizontal_accuracy_meters", "horizontalAccuracyMeters", 25.0).toFloat(),
            preserveMockFlag = json.optBooleanCompat("preserve_mock_flag", "preserveMockFlag", true),
        )
    }

    private fun parseCoordinateOverrides(json: JSONObject?): Map<String, CoordinateSystem> {
        if (json == null) return emptyMap()
        return buildMap {
            json.keys().forEachRemaining { packageName ->
                enumOrNull<CoordinateSystem>(json.optString(packageName))?.let { put(packageName, it) }
            }
        }
    }

    private fun parseMotionNoise(json: JSONObject?): MotionNoiseConfig {
        if (json == null) return MotionNoiseConfig()
        return MotionNoiseConfig(
            enabled = json.optBoolean("enabled", true),
            horizontalRadiusMeters = json.optDoubleCompat("horizontal_radius_meters", "horizontalRadiusMeters", 5.0).toFloat(),
            accuracyDriftMeters = json.optDoubleCompat("accuracy_drift_meters", "accuracyDriftMeters", 1.5).toFloat(),
            altitudeDriftMeters = json.optDoubleCompat("altitude_drift_meters", "altitudeDriftMeters", 0.7).toFloat(),
        )
    }

    private fun parseGnss(json: JSONObject?): GnssProfile {
        if (json == null) return GnssProfile()
        return GnssProfile(
            minSatelliteCount = json.optIntCompat("min_satellite_count", "minSatelliteCount", 12),
            includeMeasurements = json.optBooleanCompat("include_measurements", "includeMeasurements", false),
            includeNavigationMessages = json.optBooleanCompat("include_navigation_messages", "includeNavigationMessages", false),
        )
    }

    private fun parseNmea(json: JSONObject?): NmeaProfile {
        if (json == null) return NmeaProfile()
        return NmeaProfile(
            enabled = json.optBoolean("enabled", false),
            maxSentenceBytes = json.optIntCompat("max_sentence_bytes", "maxSentenceBytes", 256),
        )
    }

    private fun parseWifiConnected(json: JSONObject?): WifiConnectedProfile {
        if (json == null) return WifiConnectedProfile()
        return WifiConnectedProfile(
            ssid = json.optString("ssid", ""),
            bssid = json.optString("bssid", ""),
            macAddress = json.optStringCompat("mac_address", "macAddress", ""),
            ipv4Address = json.optStringCompat("ipv4_address", "ipv4Address", ""),
            rssiDbm = json.optIntCompat("rssi_dbm", "rssiDbm", -65),
            linkSpeedMbps = json.optIntCompat("link_speed_mbps", "linkSpeedMbps", 72),
            frequencyMhz = json.optIntCompat("frequency_mhz", "frequencyMhz", 2412),
            securityType = json.optStringCompat("security_type", "securityType", ""),
        )
    }

    private fun parseWifiScan(json: JSONObject?): WifiScanProfile {
        if (json == null) return WifiScanProfile()
        return WifiScanProfile(
            entries = parseArray(json.optJSONArray("entries"), ::parseWifiScanEntry),
            maxEntries = json.optIntCompat("max_entries", "maxEntries", 32),
        )
    }

    private fun parseWifiScanEntry(json: JSONObject): WifiScanEntry = WifiScanEntry(
        ssid = json.optString("ssid", ""),
        bssid = json.optString("bssid", ""),
        rssiDbm = json.optIntCompat("rssi_dbm", "rssiDbm", -65),
        frequencyMhz = json.optIntCompat("frequency_mhz", "frequencyMhz", 2412),
        capabilities = json.optString("capabilities", ""),
        standard = json.optString("standard", ""),
        channelWidth = json.optIntCompat("channel_width", "channelWidth", 0),
    )

    private fun parseCellTower(json: JSONObject): CellTowerProfile = CellTowerProfile(
        rat = enumOrNull<CellRat>(json.optString("rat")) ?: CellRat.LTE,
        mcc = json.optString("mcc", ""),
        mnc = json.optString("mnc", ""),
        tacOrLac = json.optNullableInt("tac_or_lac", "tacOrLac"),
        cellId = json.optNullableLong("cell_id", "cellId"),
        pciOrPsc = json.optNullableInt("pci_or_psc", "pciOrPsc"),
        arfcn = json.optNullableInt("arfcn", "arfcn"),
        bands = parseIntArray(json.optJSONArray("bands")),
        bandwidthKhz = json.optNullableInt("bandwidth_khz", "bandwidthKhz"),
        additionalPlmns = parseStringArray(json.optJSONArray("additional_plmns") ?: json.optJSONArray("additionalPlmns")),
        rssiDbm = json.optIntCompat("rssi_dbm", "rssiDbm", -95),
        rsrpDbm = json.optNullableInt("rsrp_dbm", "rsrpDbm"),
        rsrqDb = json.optNullableInt("rsrq_db", "rsrqDb"),
        rssnrDb = json.optNullableInt("rssnr_db", "rssnrDb"),
        cqiTableIndex = json.optNullableInt("cqi_table_index", "cqiTableIndex"),
        cqi = json.optNullableInt("cqi", "cqi"),
        timingAdvance = json.optNullableInt("timing_advance", "timingAdvance"),
        csiRsrpDbm = json.optNullableInt("csi_rsrp_dbm", "csiRsrpDbm"),
        csiRsrqDb = json.optNullableInt("csi_rsrq_db", "csiRsrqDb"),
        csiSinrDb = json.optNullableInt("csi_sinr_db", "csiSinrDb"),
        csiCqiTableIndex = json.optNullableInt("csi_cqi_table_index", "csiCqiTableIndex"),
        csiCqiReport = parseIntArray(json.optJSONArray("csi_cqi_report") ?: json.optJSONArray("csiCqiReport")),
        ssRsrpDbm = json.optNullableInt("ss_rsrp_dbm", "ssRsrpDbm"),
        ssRsrqDb = json.optNullableInt("ss_rsrq_db", "ssRsrqDb"),
        ssSinrDb = json.optNullableInt("ss_sinr_db", "ssSinrDb"),
        timingAdvanceMicros = json.optNullableInt("timing_advance_micros", "timingAdvanceMicros"),
        registered = json.optBoolean("registered", true),
    )

    private fun parseBleDevice(json: JSONObject): BleDeviceProfile = BleDeviceProfile(
        address = json.optString("address", ""),
        name = json.optString("name", ""),
        rssiDbm = json.optIntCompat("rssi_dbm", "rssiDbm", -70),
        serviceUuids = parseStringArray(json.optJSONArray("service_uuids") ?: json.optJSONArray("serviceUuids")),
        serviceSolicitationUuids = parseStringArray(
            json.optJSONArray("service_solicitation_uuids") ?: json.optJSONArray("serviceSolicitationUuids")
        ),
        manufacturerDataHex = json.optStringCompat("manufacturer_data_hex", "manufacturerDataHex", ""),
        serviceDataHex = json.optStringCompat("service_data_hex", "serviceDataHex", ""),
        txPowerDbm = json.optNullableInt("tx_power_dbm", "txPowerDbm"),
        primaryPhy = json.optIntCompat("primary_phy", "primaryPhy", 1),
        secondaryPhy = json.optIntCompat("secondary_phy", "secondaryPhy", 0),
        advertisingSid = json.optNullableInt("advertising_sid", "advertisingSid"),
        periodicAdvertisingInterval = json.optNullableInt("periodic_advertising_interval", "periodicAdvertisingInterval"),
        dataStatus = json.optIntCompat("data_status", "dataStatus", 0),
        legacy = json.optBoolean("legacy", true),
        connectable = json.optBoolean("connectable", true),
        bonded = json.optBoolean("bonded", false),
        connected = json.optBoolean("connected", false),
    )

    private fun parseNfcReplay(json: JSONObject): NfcReplayProfile = NfcReplayProfile(
        label = json.optString("label", ""),
        tagType = json.optStringCompat("tag_type", "tagType", "NDEF"),
        uidHex = json.optStringCompat("uid_hex", "uidHex", ""),
        ndefPayloadHex = json.optStringCompat("ndef_payload_hex", "ndefPayloadHex", ""),
        techList = parseStringArray(json.optJSONArray("tech_list") ?: json.optJSONArray("techList")),
        maxPayloadBytes = json.optIntCompat("max_payload_bytes", "maxPayloadBytes", 4096),
    )

    private fun parseEvidenceRefs(json: JSONObject?): Map<MockDomain, List<EvidenceRef>> {
        if (json == null) return emptyMap()
        return buildMap {
            json.keys().forEachRemaining { key ->
                val domain = enumOrNull<MockDomain>(key) ?: return@forEachRemaining
                val refs = parseArray(json.optJSONArray(key)) { item -> parseEvidenceRef(domain, item) }
                if (refs.isNotEmpty()) put(domain, refs)
            }
        }
    }

    private fun parseEvidenceRef(defaultDomain: MockDomain, json: JSONObject): EvidenceRef {
        val source = enumOrNull<EvidenceSource>(json.optString("source")) ?: EvidenceSource.MANUAL_ASSUMPTION
        return EvidenceRef(
            domain = enumOrNull<MockDomain>(json.optString("domain")) ?: defaultDomain,
            field = json.optString("field", ""),
            source = source,
            ref = json.optString("ref", ""),
            releaseEnabled = json.optBooleanCompat("release_enabled", "releaseEnabled", source != EvidenceSource.MANUAL_ASSUMPTION),
        )
    }

    private fun <T> parseArray(array: JSONArray?, parser: (JSONObject) -> T): List<T> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(parser(item))
            }
        }
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) add(array.optString(index))
        }
    }

    private fun parseIntArray(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) add(array.optInt(index))
        }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private fun JSONObject.optStringCompat(snake: String, camel: String, default: String): String =
        when {
            has(snake) -> optString(snake, default)
            has(camel) -> optString(camel, default)
            else -> default
        }

    private fun JSONObject.optIntCompat(snake: String, camel: String, default: Int): Int =
        when {
            has(snake) -> optInt(snake, default)
            has(camel) -> optInt(camel, default)
            else -> default
        }

    private fun JSONObject.optDoubleCompat(snake: String, camel: String, default: Double): Double =
        when {
            has(snake) -> optDouble(snake, default)
            has(camel) -> optDouble(camel, default)
            else -> default
        }

    private fun JSONObject.optBooleanCompat(snake: String, camel: String, default: Boolean): Boolean =
        when {
            has(snake) -> optBoolean(snake, default)
            has(camel) -> optBoolean(camel, default)
            else -> default
        }

    private fun JSONObject.optNullableInt(snake: String, camel: String): Int? =
        when {
            has(snake) && !isNull(snake) -> optInt(snake)
            has(camel) && !isNull(camel) -> optInt(camel)
            else -> null
        }

    private fun JSONObject.optNullableLong(snake: String, camel: String): Long? =
        when {
            has(snake) && !isNull(snake) -> optLong(snake)
            has(camel) && !isNull(camel) -> optLong(camel)
            else -> null
        }

    private fun List<ConfigIssue>.toSummary(): String =
        take(4).joinToString(separator = "; ") { issue ->
            "${issue.domain}.${issue.field}: ${issue.reason}"
        } + if (size > 4) "; +${size - 4} more" else ""
}

private val macAddressRegex = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
private val hexRegex = Regex("^[0-9A-Fa-f]*$")
private val plmnRegex = Regex("^\\d{5,6}$")

private fun CellRat.tacOrLacMax(): Int = when (this) {
    CellRat.NR -> 16_777_215
    CellRat.GSM,
    CellRat.WCDMA,
    CellRat.LTE,
    CellRat.CDMA,
    CellRat.TD_SCDMA
    -> 65_535
}

private fun CellRat.cellIdMax(): Long = when (this) {
    CellRat.NR -> 68_719_476_735L
    CellRat.WCDMA,
    CellRat.LTE,
    CellRat.TD_SCDMA
    -> 268_435_455L
    CellRat.GSM,
    CellRat.CDMA
    -> 65_535L
}

private fun CellRat.pciOrPscMax(): Int = when (this) {
    CellRat.GSM -> 63
    CellRat.WCDMA -> 511
    CellRat.TD_SCDMA -> 127
    CellRat.LTE -> 503
    CellRat.NR -> 1007
    CellRat.CDMA -> 65_535
}

private fun CellRat.arfcnMax(): Int = when (this) {
    CellRat.GSM -> 1_023
    CellRat.WCDMA,
    CellRat.TD_SCDMA
    -> 16_383
    CellRat.LTE -> 262_143
    CellRat.NR -> 3_279_165
    CellRat.CDMA -> 2_047
}

private fun CellRat.rsrpRange(): IntRange = when (this) {
    CellRat.NR -> -156..-31
    else -> -140..-43
}

private fun CellRat.rsrqRange(): IntRange = when (this) {
    CellRat.NR -> -43..20
    else -> -34..3
}

private fun CellRat.rssnrRange(): IntRange = when (this) {
    CellRat.NR -> -23..40
    else -> -20..30
}

private fun String.isMacAddress(): Boolean = macAddressRegex.matches(this)

private fun String.isIpv4Address(): Boolean {
    val parts = split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
    }
}

private fun String.isHexBytes(): Boolean = length % 2 == 0 && hexRegex.matches(this)

private fun String.isUuid(): Boolean = runCatching { UUID.fromString(this) }.isSuccess

private fun String.hexByteCount(): Int = length / 2
