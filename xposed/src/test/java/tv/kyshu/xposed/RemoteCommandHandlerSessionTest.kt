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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.kyshu.xposed.security.RemoteCommandSecurity
import tv.kyshu.xposed.config.DomainSwitch
import tv.kyshu.xposed.config.BleDeviceProfile
import tv.kyshu.xposed.config.GnssProfile
import tv.kyshu.xposed.config.LocationProfile
import tv.kyshu.xposed.config.MockDomain
import tv.kyshu.xposed.config.MockDomainConfig
import tv.kyshu.xposed.config.MockDomainConfigJson
import tv.kyshu.xposed.config.MockMode
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Log
import tv.kyshu.xposed.utils.LogConfig
import java.io.File
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteCommandHandlerSessionTest {
    @Before
    fun setUp() {
        RemoteCommandHandler.clearProxyBindersForTest()
        FakeLoc.clearContextForTest()
        FakeLoc.enable = false
        FakeLoc.latitude = 0.0
        FakeLoc.longitude = 0.0
        FakeLoc.altitude = 80.0
        FakeLoc.speed = 1.35
        FakeLoc.accuracy = 25f
        FakeLoc.bearing = 0.0
        FakeLoc.locationData.hasBearings = false
        FakeLoc.enableMockGnss = false
        FakeLoc.enableNMEA = false
        FakeLoc.minSatellites = 12
        FakeLoc.enableMockWifi = true
        FakeLoc.mockWifiList = emptyList()
        FakeLoc.enableBleMock = false
        FakeLoc.bleDevices = emptyList()
        Log.clearForTest()
        LogConfig.resetForTest()
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
    fun `control sync config includes versioned domain config json`() {
        FakeLoc.enable = true
        FakeLoc.latitude = 31.2304
        FakeLoc.longitude = 121.4737
        FakeLoc.enableMockGnss = true
        FakeLoc.minSatellites = 18
        val sessionKey = RemoteCommandHandler.rotateSessionKey()
        val bundle = Bundle().apply { putString("command_id", MockCommandContract.Commands.SYNC_CONFIG) }
        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = bundle,
                sessionKey = sessionKey,
                sequence = 1L,
                nowMs = System.currentTimeMillis(),
                nonce = "control-sync-domain-config-nonce",
            )
        )

        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, bundle))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(bundle, sessionKey))
        val json = JSONObject(bundle.getString(MockCommandContract.Keys.MOCK_DOMAIN_CONFIG_JSON).orEmpty())

        assertEquals(MockDomainConfig.CURRENT_SCHEMA_VERSION, json.getInt("schema_version"))
        assertEquals(31.2304, json.getJSONObject("location").getDouble("latitude"), 0.0)
        assertEquals(18, json.getJSONObject("gnss").getInt("min_satellite_count"))
    }

    @Test
    fun `authenticated put config applies versioned domain config json`() {
        val configJson = MockDomainConfigJson.exportJson(
            MockDomainConfig(
                switches = mapOf(
                    MockDomain.LOCATION to DomainSwitch(
                        domain = MockDomain.LOCATION,
                        runtimeEnabled = true,
                        mode = MockMode.REPLACE_WITH_PROFILE,
                    ),
                    MockDomain.GNSS to DomainSwitch(
                        domain = MockDomain.GNSS,
                        runtimeEnabled = true,
                        mode = MockMode.REPLACE_WITH_PROFILE,
                    ),
                ),
                location = LocationProfile(
                    latitude = 35.6812,
                    longitude = 139.7671,
                    altitudeMeters = 44.0,
                    speedMetersPerSecond = 1.5f,
                    horizontalAccuracyMeters = 6.0f,
                ),
                gnss = GnssProfile(minSatelliteCount = 20),
            ),
        )
        val sessionKey = RemoteCommandHandler.rotateSessionKey()
        val bundle = signedBundle(sessionKey, MockCommandContract.Commands.PUT_CONFIG, sequence = 1L) {
            putString(MockCommandContract.Keys.MOCK_DOMAIN_CONFIG_JSON, configJson)
        }

        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, bundle))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(bundle, sessionKey))
        assertTrue(FakeLoc.enable)
        assertEquals(35.6812, FakeLoc.latitude, 0.0)
        assertEquals(139.7671, FakeLoc.longitude, 0.0)
        assertEquals(44.0, FakeLoc.altitude, 0.0)
        assertEquals(1.5, FakeLoc.speed, 0.0)
        assertEquals(6.0f, FakeLoc.accuracy)
        assertTrue(FakeLoc.enableMockGnss)
        assertEquals(20, FakeLoc.minSatellites)
    }

    @Test
    fun `authenticated location domain update applies runtime location profile`() {
        val sessionKey = RemoteCommandHandler.rotateSessionKey()
        val bundle = signedBundle(sessionKey, MockCommandContract.Commands.LOCATION_UPDATE, sequence = 1L) {
            putBoolean(MockCommandContract.Keys.ENABLE, true)
            putDouble(MockCommandContract.Keys.LATITUDE, 31.2304)
            putDouble(MockCommandContract.Keys.LONGITUDE, 121.4737)
            putDouble(MockCommandContract.Keys.ALTITUDE, 15.5)
            putDouble(MockCommandContract.Keys.SPEED, 2.0)
            putFloat(MockCommandContract.Keys.ACCURACY, 8.5f)
            putDouble(MockCommandContract.Keys.BEARING, 90.0)
        }

        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, bundle))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(bundle, sessionKey))
        assertTrue(FakeLoc.enable)
        assertEquals(31.2304, FakeLoc.latitude, 0.0)
        assertEquals(121.4737, FakeLoc.longitude, 0.0)
        assertEquals(15.5, FakeLoc.altitude, 0.0)
        assertEquals(2.0, FakeLoc.speed, 0.0)
        assertEquals(8.5f, FakeLoc.accuracy)
        assertEquals(90.0, FakeLoc.bearing, 0.0)
        assertTrue(FakeLoc.locationData.hasBearings)
        assertEquals("active", bundle.getString("domain_state"))
    }

    @Test
    fun `authenticated gnss and nmea domain updates apply existing switches`() {
        val sessionKey = RemoteCommandHandler.rotateSessionKey()
        val gnss = signedBundle(sessionKey, MockCommandContract.Commands.GNSS_UPDATE_STATUS, sequence = 1L) {
            putInt(MockCommandContract.Keys.MIN_SATELLITES, 18)
        }
        val nmea = signedBundle(sessionKey, MockCommandContract.Commands.NMEA_UPDATE_STREAM, sequence = 2L, nonce = "domain-nmea-nonce") {
            putString(MockCommandContract.Keys.PAYLOAD_HEX, "244750474741")
        }

        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, gnss))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(gnss, sessionKey))
        assertTrue(FakeLoc.enableMockGnss)
        assertEquals(18, FakeLoc.minSatellites)
        assertEquals("active", gnss.getString("domain_state"))

        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, nmea))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(nmea, sessionKey))
        assertTrue(FakeLoc.enableNMEA)
        assertEquals("active", nmea.getString("domain_state"))
    }

    @Test
    fun `authenticated ble update applies profiles when feature is enabled`() {
        val payloadJson = MockDomainConfigJson.exportJson(
            MockDomainConfig(
                bleDevices = listOf(
                    BleDeviceProfile(
                        address = "C0:AA:BB:CC:DD:EF",
                        name = "PortalBand",
                        rssiDbm = -61,
                        serviceUuids = listOf("0000180D-0000-1000-8000-00805F9B34FB"),
                        manufacturerDataHex = "4C000215",
                        serviceDataHex = "0102",
                        connectable = true,
                    ),
                ),
            ),
        )
        val sessionKey = RemoteCommandHandler.rotateSessionKey()
        val bundle = signedBundle(sessionKey, MockCommandContract.Commands.BLE_UPDATE, sequence = 1L) {
            putString(MockCommandContract.Keys.PAYLOAD_JSON, payloadJson)
            putInt(MockCommandContract.Keys.PAYLOAD_COUNT, 1)
        }

        val handled = RemoteCommandHandler.handleInstruction(sessionKey, bundle)

        if (BuildConfig.ENABLE_BLE_MOCK) {
            assertTrue(handled)
            assertTrue(RemoteCommandSecurity.decryptSensitivePayload(bundle, sessionKey))
            assertTrue(FakeLoc.enableBleMock)
            assertEquals(1, FakeLoc.bleDevices.size)
            assertEquals("PortalBand", FakeLoc.bleDevices.single().name)
            assertEquals("active", bundle.getString("domain_state"))
            assertEquals(1, bundle.getInt("applied_count"))
        } else {
            assertFalse(handled)
            assertFalse(FakeLoc.enableBleMock)
            assertTrue(FakeLoc.bleDevices.isEmpty())
        }
    }

    @Test
    fun `default build applies enabled wireless domain command with redacted audit`() {
        val sessionKey = RemoteCommandHandler.rotateSessionKey()
        val bundle = signedBundle(sessionKey, MockCommandContract.Commands.WIFI_UPDATE_CONNECTED, sequence = 1L) {
            putBoolean(MockCommandContract.Keys.ENABLE_MOCK_WIFI, true)
            putString(MockCommandContract.Keys.MOCK_SSID, "LabNet")
            putString(MockCommandContract.Keys.MOCK_BSSID, "02:00:00:00:00:01")
        }

        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, bundle))
        assertTrue(FakeLoc.enableMockWifi)
        val audit = Log.dumpAudit()
        assertTrue(audit.contains("domain.wifi.connected"))
        assertFalse(audit.contains("LabNet"))
        assertFalse(audit.contains("02:00:00:00:00:01"))
    }

    @Test
    fun `invalid domain command records redacted validation audit`() {
        val sessionKey = RemoteCommandHandler.rotateSessionKey()
        val bundle = signedBundle(sessionKey, MockCommandContract.Commands.LOCATION_UPDATE, sequence = 1L) {
            putDouble(MockCommandContract.Keys.LATITUDE, 181.0)
        }

        assertFalse(RemoteCommandHandler.handleInstruction(sessionKey, bundle))
        assertFalse(FakeLoc.enable)
        val audit = Log.dumpAudit()
        assertTrue(audit.contains("domain.validation"))
        assertTrue(audit.contains("location.update rejected"))
        assertTrue(audit.contains("Invalid latitude command field 'latitude'"))
        assertFalse(audit.contains("181.0"))
    }

    @Test
    fun `sync only session rejects proxy registration and mutating commands`() {
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
        assertFalse(RemoteCommandHandler.handleInstruction(sessionKey, setProxy))

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
    fun `sync only session receives non sensitive config subset`() {
        FakeLoc.latitude = 39.9087
        FakeLoc.longitude = 116.3975
        FakeLoc.rinexPath = "/data/user/0/tv.kyshu/files/brdc.rnx"
        FakeLoc.mockSSID = "OfficeWifi"
        FakeLoc.gaitParamsJson = """{"frequency":1.8}"""
        FakeLoc.hookSensor = true
        FakeLoc.enableStepCountMock = true

        val sessionKey = RemoteCommandHandler.rotateSessionKey(RemoteCommandHandler.SessionScope.SYNC_ONLY)
        val bundle = Bundle().apply { putString("command_id", MockCommandContract.Commands.SYNC_CONFIG) }
        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = bundle,
                sessionKey = sessionKey,
                sequence = 1L,
                nowMs = System.currentTimeMillis(),
                nonce = "sync-only-sensitive-filter-nonce",
            )
        )

        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, bundle))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(bundle, sessionKey))
        assertTrue(bundle.getBoolean("hook_sensor"))
        assertTrue(bundle.getBoolean("enable_step_count_mock"))
        assertFalse(bundle.containsKey("latitude"))
        assertFalse(bundle.containsKey("longitude"))
        assertFalse(bundle.containsKey("rinex_path"))
        assertFalse(bundle.containsKey("mock_ssid"))
        assertFalse(bundle.containsKey("gait_params_json"))
        assertFalse(bundle.containsKey(MockCommandContract.Keys.MOCK_DOMAIN_CONFIG_JSON))
    }

    @Test
    fun `export logs requires one time consent token`() {
        val context = RuntimeEnvironment.getApplication()
        FakeLoc.setContext(context, authoritative = true)
        Log.init(context)
        val sessionKey = RemoteCommandHandler.rotateSessionKey()
        val export = Bundle().apply {
            putString("command_id", MockCommandContract.Commands.EXPORT_LOGS)
            putBoolean("user_authorized", true)
        }
        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = export,
                sessionKey = sessionKey,
                sequence = 1L,
                nowMs = System.currentTimeMillis(),
                nonce = "export-no-consent-nonce",
            )
        )

        assertFalse(RemoteCommandHandler.handleInstruction(sessionKey, export))
        assertEquals("valid export consent token required", export.getString("export_error"))
    }

    @Test
    fun `export logs are redacted and return app relative path`() {
        val context = RuntimeEnvironment.getApplication()
        FakeLoc.setContext(context, authoritative = true)
        Log.init(context)
        Log.i(
            "TEST_EXPORT",
            "path=/data/user/0/tv.kyshu/files/brdc.rnx package=com.example.target " +
                "uid=10338 fingerprint=Xiaomi/foo/bar:13/TQ3A.230901/abc:user/release-keys " +
                "lat=39.9087 lon=116.3975 token=secret-token"
        )

        val sessionKey = RemoteCommandHandler.rotateSessionKey()
        val grant = Bundle().apply {
            putString("command_id", MockCommandContract.Commands.GRANT_LOG_EXPORT)
            putBoolean("user_authorized", true)
        }
        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = grant,
                sessionKey = sessionKey,
                sequence = 1L,
                nowMs = System.currentTimeMillis(),
                nonce = "export-grant-nonce",
            )
        )
        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, grant))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(grant, sessionKey))
        val token = grant.getString("export_consent_token")
        assertTrue(!token.isNullOrBlank())

        val export = Bundle().apply {
            putString("command_id", MockCommandContract.Commands.EXPORT_LOGS)
            putBoolean("user_authorized", true)
            putString("export_consent_token", token)
        }
        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = export,
                sessionKey = sessionKey,
                sequence = 2L,
                nowMs = System.currentTimeMillis(),
                nonce = "export-with-consent-nonce",
            )
        )
        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, export))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(export, sessionKey))

        val exportPath = export.getString("export_path").orEmpty()
        assertTrue(exportPath.startsWith("logs/export/"))
        assertFalse(File(exportPath).isAbsolute)
        val content = File(context.filesDir, exportPath).readText(Charsets.UTF_8)
        assertTrue(content.contains("PTL Log Export"))
        assertFalse(content.contains("/data/user/0/tv.kyshu/files/brdc.rnx"))
        assertFalse(content.contains("com.example.target"))
        assertFalse(content.contains("10338"))
        assertFalse(content.contains("Xiaomi/foo/bar"))
        assertFalse(content.contains("39.9087"))
        assertFalse(content.contains("116.3975"))
        assertFalse(content.contains("secret-token"))
    }

    @Test
    fun `export logs byte cap holds for multibyte content`() {
        val context = RuntimeEnvironment.getApplication()
        FakeLoc.setContext(context, authoritative = true)
        Log.init(context)
        val payload = "敏感日志".repeat(300)
        repeat(260) { index ->
            Log.audit("EXPORT", "bulk", "line=$index token=secret-token payload=$payload")
        }

        val sessionKey = RemoteCommandHandler.rotateSessionKey()
        val grant = Bundle().apply {
            putString("command_id", MockCommandContract.Commands.GRANT_LOG_EXPORT)
            putBoolean("user_authorized", true)
        }
        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = grant,
                sessionKey = sessionKey,
                sequence = 1L,
                nowMs = System.currentTimeMillis(),
                nonce = "export-byte-cap-grant-nonce",
            )
        )
        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, grant))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(grant, sessionKey))
        val token = grant.getString("export_consent_token")
        assertTrue(!token.isNullOrBlank())

        val export = Bundle().apply {
            putString("command_id", MockCommandContract.Commands.EXPORT_LOGS)
            putBoolean("user_authorized", true)
            putString("export_consent_token", token)
        }
        assertTrue(
            RemoteCommandSecurity.attachEnvelope(
                bundle = export,
                sessionKey = sessionKey,
                sequence = 2L,
                nowMs = System.currentTimeMillis(),
                nonce = "export-byte-cap-nonce",
            )
        )
        assertTrue(RemoteCommandHandler.handleInstruction(sessionKey, export))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(export, sessionKey))

        val exportPath = export.getString("export_path").orEmpty()
        val bytes = File(context.filesDir, exportPath).readBytes().size
        assertTrue("export exceeded byte cap: $bytes", bytes <= 512 * 1024)
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

    @Test
    fun `registration and relay never synchronously ping a proxy`() {
        val remoteKey = RemoteCommandHandler.rotateSessionKey()
        val appKey = RemoteCommandHandler.rotateSessionKey()
        val done = CountDownLatch(1)
        val proxy = mockk<IBinder>()
        every { proxy.pingBinder() } throws AssertionError("Synchronous peer probe")
        every { proxy.isBinderAlive } returns true
        every { proxy.transact(any(), any(), any(), any()) } answers {
            done.countDown()
            true
        }
        repeat(2) { index ->
            val register = signedBundle(remoteKey, "set_proxy", index + 1L) {
                putBinder("proxy", proxy)
            }
            assertTrue(RemoteCommandHandler.handleInstruction(remoteKey, register))
        }
        val command = signedBundle(appKey, "set_speed", 1L) { putDouble("speed", 0.0) }
        assertTrue(RemoteCommandHandler.handleInstruction(appKey, command))
        assertTrue(done.await(2, TimeUnit.SECONDS))
        verify(exactly = 0) { proxy.pingBinder() }
    }

    @Test
    fun `blocked proxy worker does not hold registry lock or block a second command`() {
        val remoteKey = RemoteCommandHandler.rotateSessionKey()
        val appKey = RemoteCommandHandler.rotateSessionKey()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val proxy = mockk<IBinder>()
        every { proxy.isBinderAlive } answers {
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            false
        }
        val register = signedBundle(remoteKey, "set_proxy", 1L) { putBinder("proxy", proxy) }
        assertTrue(RemoteCommandHandler.handleInstruction(remoteKey, register))
        val command = signedBundle(appKey, "set_speed", 1L) { putDouble("speed", 0.0) }
        val error = AtomicReference<Throwable?>()
        try {
            assertTrue(RemoteCommandHandler.handleInstruction(appKey, command))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val thread = Thread {
                try {
                    val next = signedBundle(remoteKey, "set_proxy", 2L) { putBinder("proxy", Binder()) }
                    assertTrue(RemoteCommandHandler.handleInstruction(remoteKey, next))
                    val update = signedBundle(appKey, "set_speed", 2L) { putDouble("speed", 1.0) }
                    assertTrue(RemoteCommandHandler.handleInstruction(appKey, update))
                } catch (t: Throwable) { error.set(t) } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
            assertTrue("Registry/command thread blocked by peer", done.await(2, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError(it) }
            thread.join(100)
        } finally {
            release.countDown()
        }
        verify(exactly = 0) { proxy.pingBinder() }
    }

    private fun signedBundle(
        sessionKey: String,
        commandId: String,
        sequence: Long,
        nonce: String = "domain-command-nonce-$sequence",
        block: Bundle.() -> Unit,
    ): Bundle {
        return Bundle().apply {
            putString("command_id", commandId)
            block()
            assertTrue(
                RemoteCommandSecurity.attachEnvelope(
                    bundle = this,
                    sessionKey = sessionKey,
                    sequence = sequence,
                    nowMs = System.currentTimeMillis(),
                    nonce = nonce,
                )
            )
        }
    }
}
