package tv.kyshu.android.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RootProbeObservationParserTest {
    @Test
    fun `parses field names and coarse types without retaining values`() {
        val result = RootProbeObservationParser.parse(
            source = "dumpsys.wifi",
            output = """
                mWifiStandard=6
                mBSSID=12:34:56:78:9A:BC
                mIpAddress=192.168.1.23
                isPrimary=true
                scanResults=[redacted]
                oplusVendorScore=42
            """.trimIndent(),
        )

        val byPath = result.observations.associateBy { it.path }
        assertEquals(0, result.skippedMalformed)
        assertEquals("int", byPath.getValue("dumpsys.wifi.mWifiStandard").type)
        assertEquals("mac", byPath.getValue("dumpsys.wifi.mBSSID").type)
        assertEquals("ipv4", byPath.getValue("dumpsys.wifi.mIpAddress").type)
        assertEquals("boolean", byPath.getValue("dumpsys.wifi.isPrimary").type)
        assertEquals("list", byPath.getValue("dumpsys.wifi.scanResults").type)
        assertTrue(byPath.getValue("dumpsys.wifi.mBSSID").mockable)
        assertTrue(byPath.getValue("dumpsys.wifi.oplusVendorScore").vendorExtension)
        assertFalse(result.observations.toString().contains("12:34:56:78:9A:BC"))
        assertFalse(result.observations.toString().contains("192.168.1.23"))
    }

    @Test
    fun `skips sensitive identifier fields rejected by evidence model`() {
        val result = RootProbeObservationParser.parse(
            source = "dumpsys.phone",
            output = """
                imei=490154203237518
                imsi=460001234567890
                serviceState=IN_SERVICE
                nrArfcn=630000
            """.trimIndent(),
        )

        assertEquals(2, result.skippedSensitive)
        assertTrue(result.observations.none { it.path.contains("imei", ignoreCase = true) })
        assertTrue(result.observations.none { it.path.contains("imsi", ignoreCase = true) })
        assertEquals(
            setOf("dumpsys.phone.serviceState", "dumpsys.phone.nrArfcn"),
            result.observations.map { it.path }.toSet(),
        )
    }

    @Test
    fun `parsed observations serialize without raw output values`() {
        val parsed = RootProbeObservationParser.parse(
            source = "dumpsys.bluetooth_manager",
            output = "address=AA:BB:CC:DD:EE:FF connected=true manufacturerData=01020304",
        )
        val artifact = RootProbeEvidence.createArtifact(
            command = RootProbeCommandPolicy.dryRun(
                RootProbeCommandPolicy.ProbeCommandRequest(
                    argv = listOf("dumpsys", "bluetooth_manager"),
                    purpose = "Collect Bluetooth field shape",
                ),
            ).plan!!,
            deviceContext = RootProbeEvidence.DeviceContext(
                sdk = 35,
                rom = "Android 15",
                manufacturer = "generic",
                brand = "generic",
                model = "test-device",
                moduleScope = "system-framework",
                targetProcess = "system_server",
                permissions = emptyMap(),
                featureFlags = emptyMap(),
            ),
            observations = parsed.observations,
            rawOutput = "address=AA:BB:CC:DD:EE:FF connected=true manufacturerData=01020304",
        )

        val json = RootProbeEvidence.toJsonString(artifact)
        assertFalse(json.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(json.contains("01020304"))
        assertTrue(json.contains("dumpsys.bluetooth_manager.connected"))
        assertTrue(json.contains("raw_output_bytes_dropped"))
    }
}
