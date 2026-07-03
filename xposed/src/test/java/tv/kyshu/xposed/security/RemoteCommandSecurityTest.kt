package tv.kyshu.xposed.security

import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteCommandSecurityTest {
    @Test
    fun `bundle codec preserves signed encrypted payload for content provider fallback`() {
        val now = 1_000_000L
        val key = "auth_test_key"
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { now })
        val original = Bundle().apply {
            putString("command_id", "update_location")
            putDouble("lat", 39.9087)
            putDouble("lon", 116.3975)
            putFloat("accuracy", 4.5f)
            putStringArrayList("ssids", arrayListOf("OfficeWifi", "PrivateWifi"))
            putIntegerArrayList("rssis", arrayListOf(-42, -70))
            putByteArray("opaque", byteArrayOf(1, 2, 3, 4))
        }

        assertTrue(guard.registerSession(key))
        assertTrue(RemoteCommandSecurity.attachEnvelope(original, key, sequence = 1L, nowMs = now, nonce = "codec-nonce-12345"))

        val decoded = RemoteCommandBundleCodec.decode(RemoteCommandBundleCodec.encode(original))

        assertTrue(guard.accept(key, decoded))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(decoded, key))
        assertEquals("update_location", decoded.getString("command_id"))
        assertEquals(39.9087, decoded.getDouble("lat"), 0.0)
        assertEquals(116.3975, decoded.getDouble("lon"), 0.0)
        assertEquals(4.5f, decoded.getFloat("accuracy"), 0f)
        assertEquals(arrayListOf("OfficeWifi", "PrivateWifi"), decoded.getStringArrayList("ssids"))
        assertEquals(arrayListOf(-42, -70), decoded.getIntegerArrayList("rssis"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded.getByteArray("opaque"))
    }

    @Test
    fun `fresh signed command is accepted once`() {
        val now = 1_000_000L
        val key = "auth_test_key"
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { now })
        val bundle = Bundle().apply { putString("command_id", "ping") }

        assertTrue(guard.registerSession(key))
        assertTrue(RemoteCommandSecurity.attachEnvelope(bundle, key, sequence = 1L, nowMs = now, nonce = "nonce-1234567890"))

        assertTrue(guard.accept(key, bundle))
        assertFalse(guard.accept(key, bundle))
    }

    @Test
    fun `missing envelope is rejected`() {
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { 1_000_000L })
        val bundle = Bundle().apply { putString("command_id", "ping") }

        assertFalse(guard.accept("auth_test_key", bundle))
    }

    @Test
    fun `unregistered session is rejected even with valid envelope`() {
        val now = 1_000_000L
        val key = "auth_test_key"
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { now })
        val bundle = Bundle().apply { putString("command_id", "ping") }

        assertTrue(RemoteCommandSecurity.attachEnvelope(bundle, key, sequence = 1L, nowMs = now, nonce = "nonce-1234567890"))

        assertFalse(guard.accept(key, bundle))
    }

    @Test
    fun `session key is exchanged only through encrypted transport fields`() {
        val bundle = Bundle()
        val sessionKey = "auth_abcdef1234567890"

        assertTrue(RemoteCommandSecurity.putEncryptedSessionKey(bundle, sessionKey))

        assertFalse(bundle.containsKey("key"))
        assertTrue(bundle.containsKey(RemoteCommandSecurity.FIELD_ENCRYPTED_SESSION_KEY))
        assertTrue(bundle.containsKey(RemoteCommandSecurity.FIELD_SESSION_KEY_IV))
        assertEquals(sessionKey, RemoteCommandSecurity.decryptSessionKey(bundle))
    }

    @Test
    fun `tampered encrypted session key is rejected`() {
        val bundle = Bundle()
        val sessionKey = "auth_abcdef1234567890"

        assertTrue(RemoteCommandSecurity.putEncryptedSessionKey(bundle, sessionKey))
        val encrypted = bundle.getByteArray(RemoteCommandSecurity.FIELD_ENCRYPTED_SESSION_KEY)!!
        encrypted[encrypted.lastIndex] = (encrypted[encrypted.lastIndex].toInt() xor 0x01).toByte()
        bundle.putByteArray(RemoteCommandSecurity.FIELD_ENCRYPTED_SESSION_KEY, encrypted)

        assertNull(RemoteCommandSecurity.decryptSessionKey(bundle))
    }

    @Test
    fun `stale envelope is rejected`() {
        val now = 1_000_000L
        val key = "auth_test_key"
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { now })
        val bundle = Bundle().apply { putString("command_id", "ping") }
        val stale = now - RemoteCommandSecurity.DEFAULT_REPLAY_WINDOW_MS - 1L

        assertTrue(guard.registerSession(key))
        assertTrue(RemoteCommandSecurity.attachEnvelope(bundle, key, sequence = 1L, nowMs = stale, nonce = "nonce-1234567890"))

        assertFalse(guard.accept(key, bundle))
    }

    @Test
    fun `tampered command id is rejected`() {
        val now = 1_000_000L
        val key = "auth_test_key"
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { now })
        val bundle = Bundle().apply { putString("command_id", "ping") }

        assertTrue(guard.registerSession(key))
        assertTrue(RemoteCommandSecurity.attachEnvelope(bundle, key, sequence = 1L, nowMs = now, nonce = "nonce-1234567890"))
        bundle.putString("command_id", "stop")

        assertFalse(guard.accept(key, bundle))
    }

    @Test
    fun `tampered command payload is rejected`() {
        val now = 1_000_000L
        val key = "auth_test_key"
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { now })
        val bundle = Bundle().apply {
            putString("command_id", "update_location")
            putDouble("lat", 10.0)
            putDouble("lon", 20.0)
        }

        assertTrue(guard.registerSession(key))
        assertTrue(RemoteCommandSecurity.attachEnvelope(bundle, key, sequence = 1L, nowMs = now, nonce = "nonce-1234567890"))
        bundle.putDouble("lat", 11.0)

        assertFalse(guard.accept(key, bundle))
    }

    @Test
    fun `sensitive payload fields are encrypted before signing and restored after verification`() {
        val now = 1_000_000L
        val key = "auth_test_key"
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { now })
        val bundle = Bundle().apply {
            putString("command_id", "put_config")
            putDouble("latitude", 39.9)
            putFloat("accuracy", 4.5f)
            putString("mock_ssid", "PrivateWifi")
        }

        assertTrue(guard.registerSession(key))
        assertTrue(RemoteCommandSecurity.attachEnvelope(bundle, key, sequence = 1L, nowMs = now, nonce = "nonce-1234567890"))

        assertFalse(bundle.containsKey("latitude"))
        assertFalse(bundle.containsKey("accuracy"))
        assertFalse(bundle.containsKey("mock_ssid"))
        assertTrue(bundle.containsKey("p_latitude"))
        assertTrue(bundle.containsKey("p_accuracy"))
        assertTrue(bundle.containsKey("p_mock_ssid"))
        assertTrue(guard.accept(key, bundle))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(bundle, key))

        assertEquals(39.9, bundle.getDouble("latitude"), 0.0)
        assertEquals(4.5f, bundle.getFloat("accuracy"), 0f)
        assertEquals("PrivateWifi", bundle.getString("mock_ssid"))
    }

    @Test
    fun `tampered sensitive payload fails without partial plaintext rollback`() {
        val key = "auth_test_key"
        val bundle = Bundle().apply {
            putDouble("lat", 39.9087)
            putDouble("lon", 116.3975)
        }

        assertTrue(RemoteCommandSecurity.encryptSensitivePayload(bundle, key))
        val encryptedLon = bundle.getByteArray("p_lon")!!
        encryptedLon[encryptedLon.lastIndex] = (encryptedLon[encryptedLon.lastIndex].toInt() xor 0x01).toByte()
        bundle.putByteArray("p_lon", encryptedLon)

        assertFalse(RemoteCommandSecurity.decryptSensitivePayload(bundle, key))
        assertFalse(bundle.containsKey("lat"))
        assertFalse(bundle.containsKey("lon"))
        assertTrue(bundle.containsKey("p_lat"))
        assertTrue(bundle.containsKey("p_lon"))
    }

    @Test
    fun `wifi scan list payload fields are encrypted and restored after verification`() {
        val now = 1_000_000L
        val key = "auth_test_key"
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { now })
        val bundle = Bundle().apply {
            putString("command_id", "update_wifi")
            putStringArrayList("ssids", arrayListOf("PrivateWifi", "OfficeWifi"))
            putStringArrayList("bssids", arrayListOf("02:11:22:33:44:55", "02:66:77:88:99:aa"))
            putIntegerArrayList("rssis", arrayListOf(-42, -70))
            putIntegerArrayList("freqs", arrayListOf(2412, 5200))
            putStringArrayList("caps", arrayListOf("[WPA2-PSK-CCMP][ESS]", "[WPA3-SAE-CCMP][ESS]"))
            putIntegerArrayList("standards", arrayListOf(4, 6))
            putIntegerArrayList("widths", arrayListOf(1, 2))
        }

        assertTrue(guard.registerSession(key))
        assertTrue(RemoteCommandSecurity.attachEnvelope(bundle, key, sequence = 1L, nowMs = now, nonce = "nonce-1234567890"))

        assertFalse(bundle.containsKey("ssids"))
        assertFalse(bundle.containsKey("bssids"))
        assertFalse(bundle.containsKey("rssis"))
        assertTrue(bundle.containsKey("p_ssids"))
        assertTrue(bundle.containsKey("p_bssids"))
        assertTrue(bundle.containsKey("p_rssis"))
        assertTrue(guard.accept(key, bundle))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(bundle, key))

        assertEquals(arrayListOf("PrivateWifi", "OfficeWifi"), bundle.getStringArrayList("ssids"))
        assertEquals(arrayListOf("02:11:22:33:44:55", "02:66:77:88:99:aa"), bundle.getStringArrayList("bssids"))
        assertEquals(arrayListOf(-42, -70), bundle.getIntegerArrayList("rssis"))
        assertEquals(arrayListOf(2412, 5200), bundle.getIntegerArrayList("freqs"))
        assertEquals(arrayListOf("[WPA2-PSK-CCMP][ESS]", "[WPA3-SAE-CCMP][ESS]"), bundle.getStringArrayList("caps"))
        assertEquals(arrayListOf(4, 6), bundle.getIntegerArrayList("standards"))
        assertEquals(arrayListOf(1, 2), bundle.getIntegerArrayList("widths"))
    }

    @Test
    fun `empty wifi scan list payload fields keep their types through encryption`() {
        val now = 1_000_000L
        val key = "auth_test_key"
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { now })
        val bundle = Bundle().apply {
            putString("command_id", "update_wifi")
            putStringArrayList("ssids", arrayListOf())
            putIntegerArrayList("rssis", arrayListOf())
        }

        assertTrue(guard.registerSession(key))
        assertTrue(RemoteCommandSecurity.attachEnvelope(bundle, key, sequence = 1L, nowMs = now, nonce = "nonce-1234567890"))
        assertTrue(guard.accept(key, bundle))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(bundle, key))

        assertEquals(arrayListOf<String>(), bundle.getStringArrayList("ssids"))
        assertEquals(arrayListOf<Int>(), bundle.getIntegerArrayList("rssis"))
    }

    @Test
    fun `concurrent replay race accepts only one claimant`() {
        val now = 1_000_000L
        val key = "auth_test_key"
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { now })
        val bundle = Bundle().apply { putString("command_id", "ping") }
        val accepted = AtomicInteger(0)
        val workers = 12
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)

        assertTrue(guard.registerSession(key))
        assertTrue(RemoteCommandSecurity.attachEnvelope(bundle, key, sequence = 1L, nowMs = now, nonce = "nonce-1234567890"))

        repeat(workers) {
            executor.execute {
                ready.countDown()
                start.await(2, TimeUnit.SECONDS)
                if (guard.accept(key, bundle)) {
                    accepted.incrementAndGet()
                }
            }
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(1, accepted.get())
    }

    @Test
    fun `new session registration does not revoke existing sessions`() {
        val now = 1_000_000L
        val oldKey = "auth_old_key"
        val newKey = "auth_new_key"
        val guard = RemoteCommandSecurity.ReplayGuard(nowProvider = { now })
        val oldBundle = Bundle().apply { putString("command_id", "ping") }
        val newBundle = Bundle().apply { putString("command_id", "ping") }

        assertTrue(guard.registerSession(oldKey))
        assertTrue(guard.registerSession(newKey))
        assertTrue(RemoteCommandSecurity.attachEnvelope(oldBundle, oldKey, sequence = 1L, nowMs = now, nonce = "old-nonce-123456"))
        assertTrue(RemoteCommandSecurity.attachEnvelope(newBundle, newKey, sequence = 1L, nowMs = now, nonce = "new-nonce-123456"))

        assertTrue(guard.accept(oldKey, oldBundle))
        assertTrue(guard.accept(newKey, newBundle))
    }
}
