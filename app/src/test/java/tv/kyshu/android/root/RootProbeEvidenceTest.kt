package tv.kyshu.android.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RootProbeEvidenceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `artifact keeps field shape and drops raw probe output`() {
        val command = allowedPlan(listOf("dumpsys", "wifi"))
        val rawOutput = "mBSSID=12:34:56:78:9A:BC mIpAddress=192.168.1.23"

        val artifact = RootProbeEvidence.createArtifact(
            command = command,
            deviceContext = safeDeviceContext(),
            observations = listOf(
                RootProbeEvidence.FieldObservation(
                    path = "WifiInfo.mWifiStandard",
                    type = "int",
                    source = "dumpsys.wifi",
                    mockable = true,
                    vendorExtension = false,
                    notes = "field shape only",
                ),
            ),
            rawOutput = rawOutput,
        )

        assertEquals(1, artifact.schemaVersion)
        assertEquals(listOf("dumpsys", "wifi"), artifact.commandArgv)
        assertEquals("Collect field shape", artifact.purpose)
        assertEquals(1, artifact.observations.size)
        assertEquals("WifiInfo.mWifiStandard", artifact.observations.single().path)
        assertEquals(rawOutput.toByteArray(Charsets.UTF_8).size, artifact.rawOutputBytesDropped)
        assertFalse(artifact.rawOutputRetained)
    }

    @Test
    fun `sensitive device context labels and field paths are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            safeDeviceContext().copy(model = "serial ABCDEF123456")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootProbeEvidence.FieldObservation(
                path = "TelephonyManager.getImei",
                type = "String",
                source = "dumpsys.phone",
                mockable = false,
                vendorExtension = false,
            )
        }
    }

    @Test
    fun `failure details are redacted before artifact storage`() {
        val failure = RootProbeEvidence.failure(
            reason = "probe_failed",
            detail = "IMEI=490154203237518 serialno=ABCDEF123456 BSSID=12:34:56:78:9A:BC",
        )

        assertFalse(failure.detail.contains("490154203237518"))
        assertFalse(failure.detail.contains("ABCDEF123456"))
        assertFalse(failure.detail.contains("12:34:56:78:9A:BC"))
        assertTrue(failure.detail.contains("<identifier>") || failure.detail.contains("<redacted"))
    }

    @Test
    fun `json round trip preserves sanitized artifact and omits raw output`() {
        val artifact = sampleArtifact(rawOutput = "IMEI=490154203237518 BSSID=12:34:56:78:9A:BC")

        val json = RootProbeEvidence.toJsonString(artifact)
        val parsed = RootProbeEvidence.fromJsonString(json)

        assertFalse(json.contains("490154203237518"))
        assertFalse(json.contains("12:34:56:78:9A:BC"))
        assertFalse(json.contains("raw_output\""))
        assertEquals(artifact.commandArgv, parsed.commandArgv)
        assertEquals(artifact.rawOutputBytesDropped, parsed.rawOutputBytesDropped)
        assertFalse(parsed.rawOutputRetained)
        assertEquals("WifiInfo.mWifiStandard", parsed.observations.single().path)
    }

    @Test
    fun `artifact write and read use app local json files`() {
        val dir = temp.newFolder("root-probe")
        val artifact = sampleArtifact(rawOutput = "mBSSID=12:34:56:78:9A:BC")

        val file = RootProbeEvidence.writeArtifact(dir, artifact, timestampMs = 1234L)
        val parsed = RootProbeEvidence.readArtifact(file)

        assertEquals("root_probe_1234.json", file.name)
        assertTrue(file.isFile)
        assertEquals(dir.canonicalFile, file.parentFile!!.canonicalFile)
        assertEquals(artifact.commandArgv, parsed.commandArgv)
        assertEquals(artifact.observations, parsed.observations)
    }

    @Test
    fun `json import rejects retained raw output and sensitive field names`() {
        val validJson = RootProbeEvidence.toJsonString(sampleArtifact())
        val withRawOutput = validJson.replace(
            "\"raw_output_retained\": false",
            "\"raw_output_retained\": false,\n  \"raw_output\": \"secret\"",
        )
        val withSensitiveField = validJson.replace("WifiInfo.mWifiStandard", "TelephonyManager.getImei")

        assertThrows(IllegalArgumentException::class.java) {
            RootProbeEvidence.fromJsonString(withRawOutput)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootProbeEvidence.fromJsonString(withSensitiveField)
        }
    }

    private fun allowedPlan(argv: List<String>): RootProbeCommandPolicy.PlannedProbeCommand {
        val decision = RootProbeCommandPolicy.dryRun(
            RootProbeCommandPolicy.ProbeCommandRequest(
                argv = argv,
                purpose = "Collect field shape",
                expectedFields = listOf("field names", "types"),
            ),
        )
        assertTrue(decision.reason, decision.allowed)
        return decision.plan!!
    }

    private fun safeDeviceContext(): RootProbeEvidence.DeviceContext {
        return RootProbeEvidence.DeviceContext(
            sdk = 35,
            rom = "OPlus Android 15",
            manufacturer = "OPPO",
            brand = "OPPO",
            model = "test-device-family",
            moduleScope = "system-framework",
            targetProcess = "system_server",
            permissions = mapOf(
                "ACCESS_FINE_LOCATION" to true,
                "BLUETOOTH_SCAN" to true,
            ),
            featureFlags = mapOf(
                "ENABLE_WIFI_MOCK" to false,
                "ENABLE_CELL_MOCK" to false,
            ),
        )
    }

    private fun sampleArtifact(rawOutput: String? = null): RootProbeEvidence.Artifact {
        return RootProbeEvidence.createArtifact(
            command = allowedPlan(listOf("dumpsys", "wifi")),
            deviceContext = safeDeviceContext(),
            observations = listOf(
                RootProbeEvidence.FieldObservation(
                    path = "WifiInfo.mWifiStandard",
                    type = "int",
                    source = "dumpsys.wifi",
                    mockable = true,
                    vendorExtension = false,
                    notes = "field shape only",
                ),
            ),
            failures = listOf(
                RootProbeEvidence.failure("probe_failed", "BSSID=12:34:56:78:9A:BC"),
            ),
            rawOutput = rawOutput,
        )
    }
}
