package tv.kyshu.android.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RootProbeCommandPolicyTest {
    @Test
    fun `dry run admits only structured read only commands`() {
        val allowed = listOf(
            listOf("getprop", "ro.build.version.sdk"),
            listOf("settings", "get", "secure", "location_mode"),
            listOf("dumpsys", "wifi"),
            listOf("dumpsys", "telephony.registry", "--proto"),
            listOf("pm", "path", "com.android.nfc"),
            listOf("cmd", "wifi", "status"),
            listOf("logcat", "-d", "-t", "200", "-v", "time", "-s", "Portal:*", "LSPosed:*"),
        )

        allowed.forEach { argv ->
            val decision = RootProbeCommandPolicy.dryRun(
                RootProbeCommandPolicy.ProbeCommandRequest(
                    argv = argv,
                    purpose = "Collect field shape without storing raw values",
                    expectedFields = listOf("field names", "types"),
                ),
            )

            assertTrue("Expected ${argv.joinToString(" ")} to be allowed: ${decision.reason}", decision.allowed)
            assertNotNull(decision.plan)
            assertTrue(decision.plan!!.dryRun)
            assertEquals(argv, decision.plan.argv)
        }
    }

    @Test
    fun `denylist rejects writes state changes raw service calls and sensitive paths`() {
        val denied = listOf(
            listOf("setprop", "persist.demo", "1") to "denylisted_command",
            listOf("settings", "put", "secure", "location_mode", "0") to "settings_write",
            listOf("svc", "wifi", "enable") to "denylisted_command",
            listOf("ip", "link", "set", "wlan0", "down") to "network_state_write",
            listOf("service", "call", "iphonesubinfo", "1") to "service_call_not_allowlisted",
            listOf("reboot") to "denylisted_command",
            listOf("mount", "-o", "remount,rw", "/system") to "denylisted_command",
            listOf("dd", "if=/dev/block/by-name/modem", "of=/sdcard/modem.bin") to "denylisted_command",
            listOf("getprop", "ro.serialno") to "sensitive_property",
            listOf("dumpsys", "wifi", "/persist/wifi") to "blocked_partition_path",
        )

        denied.forEach { (argv, reason) ->
            val decision = RootProbeCommandPolicy.dryRun(
                RootProbeCommandPolicy.ProbeCommandRequest(
                    argv = argv,
                    purpose = "bad command",
                ),
            )

            assertFalse("Expected ${argv.joinToString(" ")} to be denied", decision.allowed)
            assertEquals(reason, decision.reason)
        }
    }

    @Test
    fun `shell concatenation is rejected even when tokens look read only`() {
        val decision = RootProbeCommandPolicy.dryRun(
            RootProbeCommandPolicy.ProbeCommandRequest(
                argv = listOf("dumpsys", "wifi; reboot"),
                purpose = "Probe WiFi shape",
            ),
        )

        assertFalse(decision.allowed)
        assertEquals("shell_metacharacter", decision.reason)
    }

    @Test
    fun `unknown command and non allowlisted properties are rejected`() {
        val unknown = RootProbeCommandPolicy.dryRun(
            RootProbeCommandPolicy.ProbeCommandRequest(
                argv = listOf("cat", "/proc/net/wireless"),
                purpose = "Probe WiFi shape",
            ),
        )
        val unsafeProperty = RootProbeCommandPolicy.dryRun(
            RootProbeCommandPolicy.ProbeCommandRequest(
                argv = listOf("getprop", "ro.boot.hardware.sku"),
                purpose = "Probe device context",
            ),
        )

        assertFalse(unknown.allowed)
        assertEquals("not_allowlisted", unknown.reason)
        assertFalse(unsafeProperty.allowed)
        assertEquals("not_allowlisted", unsafeProperty.reason)
    }

    @Test
    fun `service calls are denied until catalog has audited read only transaction`() {
        val decision = RootProbeCommandPolicy.dryRun(
            RootProbeCommandPolicy.ProbeCommandRequest(
                argv = listOf("service", "call", "wifi", "1"),
                purpose = "Probe WiFi binder shape",
            ),
        )

        assertFalse(decision.allowed)
        assertEquals("service_call_not_allowlisted", decision.reason)
    }

    @Test
    fun `probe output redaction removes unique identifiers and high risk fields`() {
        val redacted = RootProbeCommandPolicy.redactProbeOutput(
            """
            IMEI=490154203237518 IMSI=460001234567890 ICCID=8986001234567890123
            serialno=ABCDEF123456 android_id=1234567890abcdef
            mBSSID=12:34:56:78:9A:BC mIpAddress=192.168.1.23 mNci=1234567890
            """.trimIndent(),
        )

        assertFalse(redacted.contains("490154203237518"))
        assertFalse(redacted.contains("460001234567890"))
        assertFalse(redacted.contains("8986001234567890123"))
        assertFalse(redacted.contains("ABCDEF123456"))
        assertFalse(redacted.contains("1234567890abcdef"))
        assertFalse(redacted.contains("12:34:56:78:9A:BC"))
        assertFalse(redacted.contains("192.168.1.23"))
        assertTrue(redacted.contains("<identifier>"))
    }
}
