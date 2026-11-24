package tv.kyshu.android.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RootProbeCollectorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `dry run collector writes artifact for allowlisted command without executing root`() {
        val context = RuntimeEnvironment.getApplication()
        val dir = temp.newFolder("artifacts")

        val result = RootProbeCollector.collectDryRun(
            context = context,
            request = RootProbeCommandPolicy.ProbeCommandRequest(
                argv = listOf("dumpsys", "wifi"),
                purpose = "Collect WiFi field shape",
                expectedFields = listOf("WifiInfo fields"),
            ),
            artifactDirectory = dir,
            moduleScope = "system-framework",
            targetProcess = "system_server",
            timestampMs = 5678L,
        )

        assertTrue(result.decision.allowed)
        assertEquals("root_probe_5678.json", result.file!!.name)
        assertTrue(result.file.isFile)
        assertEquals(listOf("dumpsys", "wifi"), result.artifact!!.commandArgv)
        assertEquals("system-framework", result.artifact.deviceContext.moduleScope)
        assertEquals("system_server", result.artifact.deviceContext.targetProcess)
        assertTrue(result.artifact.failures.any { it.reason == "dry_run_only" })
        assertFalse(result.artifact.rawOutputRetained)

        val parsed = RootProbeEvidence.readArtifact(result.file)
        assertEquals(result.artifact.commandArgv, parsed.commandArgv)
        assertEquals(0, parsed.rawOutputBytesDropped)
    }

    @Test
    fun `dry run collector rejects denied command without writing artifact`() {
        val context = RuntimeEnvironment.getApplication()
        val dir = temp.newFolder("artifacts")

        val result = RootProbeCollector.collectDryRun(
            context = context,
            request = RootProbeCommandPolicy.ProbeCommandRequest(
                argv = listOf("settings", "put", "secure", "location_mode", "0"),
                purpose = "Bad state change",
            ),
            artifactDirectory = dir,
            moduleScope = "system-framework",
            targetProcess = "system_server",
            timestampMs = 5678L,
        )

        assertFalse(result.decision.allowed)
        assertEquals("settings_write", result.decision.reason)
        assertNull(result.artifact)
        assertNull(result.file)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `provided output collector writes parsed observations without raw values`() {
        val context = RuntimeEnvironment.getApplication()
        val dir = temp.newFolder("artifacts")
        val output = """
            mBSSID=12:34:56:78:9A:BC
            mWifiStandard=6
            imei=490154203237518
        """.trimIndent()

        val result = RootProbeCollector.recordProvidedOutput(
            context = context,
            request = RootProbeCommandPolicy.ProbeCommandRequest(
                argv = listOf("dumpsys", "wifi"),
                purpose = "Collect WiFi field shape",
            ),
            output = output,
            artifactDirectory = dir,
            moduleScope = "system-framework",
            targetProcess = "system_server",
            timestampMs = 6789L,
        )

        assertTrue(result.decision.allowed)
        assertEquals(1, result.parseResult!!.skippedSensitive)
        assertEquals(
            setOf("dumpsys.wifi.mBSSID", "dumpsys.wifi.mWifiStandard"),
            result.artifact!!.observations.map { it.path }.toSet(),
        )
        assertTrue(result.artifact.failures.any { it.reason == "sensitive_fields_skipped" })
        assertEquals(output.toByteArray(Charsets.UTF_8).size, result.artifact.rawOutputBytesDropped)

        val json = result.file!!.readText(Charsets.UTF_8)
        assertFalse(json.contains("12:34:56:78:9A:BC"))
        assertFalse(json.contains("490154203237518"))
        assertTrue(json.contains("dumpsys.wifi.mBSSID"))
    }

    @Test
    fun `provided output collector rejects denied command without parsing or writing`() {
        val context = RuntimeEnvironment.getApplication()
        val dir = temp.newFolder("artifacts")

        val result = RootProbeCollector.recordProvidedOutput(
            context = context,
            request = RootProbeCommandPolicy.ProbeCommandRequest(
                argv = listOf("reboot"),
                purpose = "Bad command",
            ),
            output = "mWifiStandard=6",
            artifactDirectory = dir,
            moduleScope = "system-framework",
            targetProcess = "system_server",
            timestampMs = 6789L,
        )

        assertFalse(result.decision.allowed)
        assertNull(result.parseResult)
        assertNull(result.artifact)
        assertNull(result.file)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }
}
