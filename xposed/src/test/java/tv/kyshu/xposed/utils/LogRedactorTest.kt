package tv.kyshu.xposed.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun `export redaction removes high risk identifiers`() {
        val raw = """
            path=/data/user/0/tv.kyshu/files/brdc.rnx
            package=com.example.target
            process=com.example.target:remote
            uid=10338 pid=4321 tid=987
            UID 10338
            fingerprint=Xiaomi/foo/bar:13/TQ3A.230901/abc:user/release-keys
            boot_id=682a3606-b7de-42ba-b8e0-3ce750cfe765
            lat=39.9087 lon=116.3975
            mock=(39.9087,116.3975)
            token=secret-token
            mock_bssid=aa:bb:cc:dd:ee:ff
            nmea=${'$'}GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47
            payload_hex=00112233445566778899AABBCCDDEEFF
            ndef_payload_hex=D1010D55016578616D706C652E636F6D
            ble_address=AA:BB:CC:DD:EE:FF
            manufacturer_data_hex=4C000215AABBCCDDEEFF00112233445566778899
            cell_id=460001234567890
            tac=12345
            trajectory=39.9087,116.3975;39.9090,116.3980
            {"latitude":39.9087,"longitude":116.3975,"uid_hex":"01020304"}
        """.trimIndent()

        val redacted = LogRedactor.redactForExport(raw)

        assertFalse(redacted.contains("/data/user/0/tv.kyshu/files/brdc.rnx"))
        assertFalse(redacted.contains("com.example.target"))
        assertFalse(redacted.contains("10338"))
        assertFalse(redacted.contains("4321"))
        assertFalse(redacted.contains("987"))
        assertFalse(redacted.contains("Xiaomi/foo/bar"))
        assertFalse(redacted.contains("682a3606-b7de-42ba-b8e0-3ce750cfe765"))
        assertFalse(redacted.contains("39.9087"))
        assertFalse(redacted.contains("116.3975"))
        assertFalse(redacted.contains("secret-token"))
        assertFalse(redacted.contains("aa:bb:cc:dd:ee:ff"))
        assertFalse(redacted.contains("GPGGA"))
        assertFalse(redacted.contains("4807.038"))
        assertFalse(redacted.contains("00112233445566778899AABBCCDDEEFF"))
        assertFalse(redacted.contains("D1010D55016578616D706C652E636F6D"))
        assertFalse(redacted.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(redacted.contains("4C000215AABBCCDDEEFF00112233445566778899"))
        assertFalse(redacted.contains("460001234567890"))
        assertFalse(redacted.contains("12345"))
        assertFalse(redacted.contains("39.9090"))
        assertFalse(redacted.contains("116.3980"))
        assertFalse(redacted.contains("01020304"))
        assertTrue(redacted.contains("<app_private#"))
        assertTrue(redacted.contains("target_app#"))
        assertTrue(redacted.contains("<coord>"))
    }

    @Test
    fun `export all applies strict redaction across retained buffers`() {
        Log.clearForTest()
        Log.audit(
            "caller=com.example.target",
            "UID 10338",
            "caller com.example.target uid=10338 token=secret-token path=/data/user/0/tv.kyshu/files/brdc.rnx nmea=${'$'}GPRMC,092751.000,A,5321.6802,N,00630.3372,W,0.06,31.66,280511,,,A*43 cell_id=460001234567890",
        )
        Log.user("package=com.example.target", "mock=(39.9087,116.3975)", "package=com.example.target lat=39.9087 lon=116.3975")

        val exported = Log.exportAll()

        assertFalse(exported.contains("com.example.target"))
        assertFalse(exported.contains("10338"))
        assertFalse(exported.contains("secret-token"))
        assertFalse(exported.contains("/data/user/0/tv.kyshu/files/brdc.rnx"))
        assertFalse(exported.contains("39.9087"))
        assertFalse(exported.contains("116.3975"))
        assertFalse(exported.contains("GPRMC"))
        assertFalse(exported.contains("5321.6802"))
        assertFalse(exported.contains("460001234567890"))
    }
}
