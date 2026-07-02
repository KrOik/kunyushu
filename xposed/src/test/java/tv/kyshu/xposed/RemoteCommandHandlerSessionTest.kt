package tv.kyshu.xposed

import android.os.Bundle
import android.os.Binder
import android.os.Parcel
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.kyshu.xposed.security.RemoteCommandSecurity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteCommandHandlerSessionTest {
    @Test
    fun `new exchange session does not revoke previous sendExtraCommand session`() {
        val firstKey = RemoteCommandHandler.rotateSessionKey()
        val secondKey = RemoteCommandHandler.rotateSessionKey()
        val now = System.currentTimeMillis()

        val firstBundle = Bundle().apply { putString("command_id", "ping") }
        val secondBundle = Bundle().apply { putString("command_id", "ping") }

        assertTrue(RemoteCommandSecurity.attachEnvelope(firstBundle, firstKey, 1L, now, "first-session-nonce"))
        assertTrue(RemoteCommandSecurity.attachEnvelope(secondBundle, secondKey, 1L, now, "second-session-nonce"))

        assertTrue(RemoteCommandHandler.handleInstruction(firstKey, firstBundle))
        assertTrue(RemoteCommandHandler.handleInstruction(secondKey, secondBundle))
        assertEquals("pong", firstBundle.getString("pong"))
        assertEquals("pong", secondBundle.getString("pong"))
    }

    @Test
    fun `unregistered sendExtraCommand session is rejected`() {
        val sessionKey = "auth_unregistered_session"
        val bundle = Bundle().apply { putString("command_id", "ping") }

        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = bundle,
                sessionKey = sessionKey,
                sequence = 1L,
                nowMs = System.currentTimeMillis(),
                nonce = "unregistered-nonce",
            )
        )

        assertFalse(RemoteCommandHandler.handleInstruction(sessionKey, bundle))
    }

    @Test
    fun `registered external session can be used by remote process proxy handler`() {
        val sessionKey = "auth_external_session"
        val bundle = Bundle().apply { putString("command_id", "ping") }

        assertTrue(RemoteCommandHandler.registerSessionKey(sessionKey))
        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = bundle,
                sessionKey = sessionKey,
                sequence = 1L,
                nowMs = System.currentTimeMillis(),
                nonce = "external-session-nonce",
            )
        )

        assertTrue(RemoteCommandHandler.handleSessionInstruction(sessionKey, bundle))
        assertEquals("pong", bundle.getString("pong"))
    }

    @Test
    fun `proxy relay is re-signed with remote process session`() {
        val remoteKey = RemoteCommandHandler.rotateSessionKey()
        val appKey = RemoteCommandHandler.rotateSessionKey()
        val relayVerified = AtomicBoolean(false)
        val proxy = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                val relayedSession = data.readString()
                val relayedBundle = data.readBundle(javaClass.classLoader) ?: return false
                val guard = RemoteCommandSecurity.ReplayGuard()

                assertEquals(remoteKey, relayedSession)
                assertTrue(guard.registerSession(remoteKey))
                assertTrue(guard.accept(remoteKey, relayedBundle))
                assertTrue(RemoteCommandSecurity.decryptSensitivePayload(relayedBundle, remoteKey))
                assertEquals("set_speed", relayedBundle.getString("command_id"))
                assertEquals(3.5, relayedBundle.getDouble("speed"), 0.0)
                relayVerified.set(true)
                return true
            }
        }
        val setProxy = Bundle().apply {
            putString("command_id", "set_proxy")
            putBinder("proxy", proxy)
        }
        assertTrue(RemoteCommandSecurity.attachEnvelope(setProxy, remoteKey, 1L, System.currentTimeMillis(), "proxy-register-nonce"))
        assertTrue(RemoteCommandHandler.handleInstruction(remoteKey, setProxy))

        val setSpeed = Bundle().apply {
            putString("command_id", "set_speed")
            putDouble("speed", 3.5)
        }
        assertTrue(RemoteCommandSecurity.attachEnvelope(setSpeed, appKey, 1L, System.currentTimeMillis(), "app-set-speed-nonce"))
        assertTrue(RemoteCommandHandler.handleInstruction(appKey, setSpeed))

        assertTrue(relayVerified.get())
    }
}
