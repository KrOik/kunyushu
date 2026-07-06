package tv.kyshu.xposed

import android.os.Bundle
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.kyshu.xposed.security.RemoteCommandSecurity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteCommandHandlerSessionTest {
    @Before
    fun setUp() {
        RemoteCommandHandler.clearProxyBindersForTest()
    }

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
    fun `sync only session can request sync config`() {
        val sessionKey = RemoteCommandHandler.rotateSessionKey(RemoteCommandHandler.SessionScope.SYNC_ONLY)
        val bundle = Bundle().apply { putString("command_id", "sync_config") }

        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = bundle,
                sessionKey = sessionKey,
                sequence = 1L,
                nowMs = System.currentTimeMillis(),
                nonce = "sync-only-config-nonce",
            )
        )

        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, bundle))
    }

    @Test
    fun `sync only session rejects mutating commands before proxy relay`() {
        val sessionKey = RemoteCommandHandler.rotateSessionKey(RemoteCommandHandler.SessionScope.SYNC_ONLY)
        val relayCalled = AtomicBoolean(false)
        val relayDone = CountDownLatch(1)
        val proxy = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                relayCalled.set(true)
                relayDone.countDown()
                return true
            }
        }
        val setProxy = Bundle().apply {
            putString("command_id", "set_proxy")
            putBinder("proxy", proxy)
        }
        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = setProxy,
                sessionKey = sessionKey,
                sequence = 1L,
                nowMs = System.currentTimeMillis(),
                nonce = "sync-only-proxy-nonce",
            )
        )
        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, setProxy))

        val setSpeed = Bundle().apply {
            putString("command_id", "set_speed")
            putDouble("speed", 2.0)
        }
        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = setSpeed,
                sessionKey = sessionKey,
                sequence = 2L,
                nowMs = System.currentTimeMillis(),
                nonce = "sync-only-set-speed-nonce",
            )
        )

        assertFalse(RemoteCommandHandler.handleInstruction(sessionKey, setSpeed))
        assertFalse(relayDone.await(250, TimeUnit.MILLISECONDS))
        assertFalse(relayCalled.get())
    }

    @Test
    fun `proxy relay is re-signed with remote process session`() {
        val remoteKey = RemoteCommandHandler.rotateSessionKey()
        val appKey = RemoteCommandHandler.rotateSessionKey()
        val relayVerified = AtomicBoolean(false)
        val relayFlags = AtomicInteger(-1)
        val relayDone = CountDownLatch(1)
        val relayError = AtomicReference<Throwable?>()
        val proxy = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                try {
                    relayFlags.set(flags)
                    val relayedSession = data.readString()
                    val relayedBundle = data.readBundle(javaClass.classLoader) ?: return false
                    val guard = RemoteCommandSecurity.ReplayGuard()

                    assertEquals(remoteKey, relayedSession)
                    assertTrue(guard.registerSession(remoteKey))
                    assertTrue(guard.accept(remoteKey, relayedBundle))
                    assertTrue(RemoteCommandSecurity.decryptSensitivePayload(relayedBundle, remoteKey))
                    assertEquals("set_speed", relayedBundle.getString("command_id"))
                    assertEquals(3.5, relayedBundle.getDouble("speed"), 0.0)
                    assertTrue(flags and IBinder.FLAG_ONEWAY != 0)
                    relayVerified.set(true)
                } catch (t: Throwable) {
                    relayError.set(t)
                } finally {
                    relayDone.countDown()
                }
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

        assertTrue(relayDone.await(2, TimeUnit.SECONDS))
        relayError.get()?.let { throw AssertionError(it) }
        assertTrue(relayVerified.get())
        assertTrue(relayFlags.get() and IBinder.FLAG_ONEWAY != 0)
    }

    @Test
    fun `slow proxy relay does not block command handling`() {
        val remoteKey = RemoteCommandHandler.rotateSessionKey()
        val appKey = RemoteCommandHandler.rotateSessionKey()
        val relayDone = CountDownLatch(1)
        val proxy = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                try {
                    Thread.sleep(400)
                } finally {
                    relayDone.countDown()
                }
                return true
            }
        }
        val setProxy = Bundle().apply {
            putString("command_id", "set_proxy")
            putBinder("proxy", proxy)
        }
        assertTrue(RemoteCommandSecurity.attachEnvelope(setProxy, remoteKey, 1L, System.currentTimeMillis(), "slow-proxy-register-nonce"))
        assertTrue(RemoteCommandHandler.handleInstruction(remoteKey, setProxy))

        val setSpeed = Bundle().apply {
            putString("command_id", "set_speed")
            putDouble("speed", 1.25)
        }
        assertTrue(RemoteCommandSecurity.attachEnvelope(setSpeed, appKey, 1L, System.currentTimeMillis(), "slow-app-set-speed-nonce"))

        val startedAt = System.nanoTime()
        assertTrue(RemoteCommandHandler.handleInstruction(appKey, setSpeed))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("handleInstruction blocked for ${elapsedMs}ms", elapsedMs < 250L)
        assertTrue(relayDone.await(2, TimeUnit.SECONDS))
    }
}
