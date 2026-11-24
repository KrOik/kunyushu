package tv.kyshu.service

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.kyshu.xposed.MockCommandContract
import tv.kyshu.xposed.security.RemoteCommandBundleCodec
import tv.kyshu.xposed.security.RemoteCommandSecurity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MockServiceHelperSecurityTest {

    @Test
    fun `stop requires explicit stopped state and never treats missing response as stopped`() {
        val manager = mockk<LocationManager>()
        every { manager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "exchange_key", any()) } answers {
            (invocation.args[2] as Bundle).putEncryptedSessionKey("auth_test_key")
            true
        }
        MockServiceHelper.tryInitService(manager)
        every { manager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "auth_test_key", any()) } returns true
        assertFalse(MockServiceHelper.tryCloseMock(manager))
        assertEquals(null, MockServiceHelper.queryMockState(manager))
        every { manager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "auth_test_key", any()) } answers {
            (invocation.args[2] as Bundle).putBoolean("is_start", false)
            true
        }
        assertTrue(MockServiceHelper.tryCloseMock(manager))
        every { manager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "auth_test_key", any()) } returns false
        assertEquals(null, MockServiceHelper.queryMockState(manager))
    }

    @Before
    fun resetMockServiceHelper() {
        MockServiceHelper::class.java.getDeclaredField("randomKey").apply {
            isAccessible = true
            set(MockServiceHelper, null)
        }
        MockServiceHelper::class.java.getDeclaredField("commandSequence").apply {
            isAccessible = true
            setLong(MockServiceHelper, 0L)
        }
    }

    private fun Bundle.putEncryptedSessionKey(sessionKey: String) {
        assertTrue(RemoteCommandSecurity.putEncryptedSessionKey(this, sessionKey))
    }

    @Test
    fun `uninitialized service returns false for all operations`() {
        val locationManager = mockk<LocationManager>()
        assertFalse(MockServiceHelper.isMockStart(locationManager))
        assertFalse(MockServiceHelper.startGnssMock(locationManager))
        assertFalse(MockServiceHelper.stopGnssMock(locationManager))
        assertFalse(MockServiceHelper.startWifiMock(locationManager))
        assertFalse(MockServiceHelper.stopWifiMock(locationManager))
    }

    @Test
    fun `exchange_key returns a non-empty key and marks service as initialized`() {
        val locationManager = mockk<LocationManager>()
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "exchange_key", any())
        } answers {
            val bundle = invocation.args[2] as Bundle
            bundle.putEncryptedSessionKey("auth_abcdef1234567890abcdef1234567890")
            true
        }

        MockServiceHelper.tryInitService(locationManager)
        assertTrue(MockServiceHelper.isServiceInit())
    }

    @Test
    fun `exchange_key failure leaves service uninitialized`() {
        val locationManager = mockk<LocationManager>()
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "exchange_key", any())
        } returns false

        MockServiceHelper.tryInitService(locationManager)
        assertFalse(MockServiceHelper.isServiceInit())
    }

    @Test
    fun `plain exchange_key response leaves service uninitialized`() {
        val locationManager = mockk<LocationManager>()
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "exchange_key", any())
        } answers {
            val bundle = invocation.args[2] as Bundle
            bundle.putString("key", "auth_plain_key_is_not_accepted")
            true
        }

        MockServiceHelper.tryInitService(locationManager)
        assertFalse(MockServiceHelper.isServiceInit())
    }

    @Test
    fun `isMockStart returns remote is_start after encrypted key exchange`() {
        val locationManager = mockk<LocationManager>()
        // Init first
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "exchange_key", any())
        } answers {
            val bundle = invocation.args[2] as Bundle
            bundle.putEncryptedSessionKey("auth_test_key")
            true
        }
        MockServiceHelper.tryInitService(locationManager)

        // Then query: authenticated command returns is_start=true.
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "auth_test_key", any())
        } answers {
            val bundle = invocation.args[2] as Bundle
            bundle.putBoolean("is_start", true)
            true
        }

        assertTrue(MockServiceHelper.isMockStart(locationManager))
    }

    @Test
    fun `getLocation decrypts encrypted response fields`() {
        val locationManager = mockk<LocationManager>()
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "exchange_key", any())
        } answers {
            val bundle = invocation.args[2] as Bundle
            bundle.putEncryptedSessionKey("auth_test_key")
            true
        }
        MockServiceHelper.tryInitService(locationManager)

        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "auth_test_key", any())
        } answers {
            val bundle = invocation.args[2] as Bundle
            bundle.putDouble("lat", 39.9087)
            bundle.putDouble("lon", 116.3975)
            RemoteCommandSecurity.encryptSensitivePayload(bundle, "auth_test_key")
            true
        }

        val location = MockServiceHelper.getLocation(locationManager)
        assertEquals(39.9087, location!!.first, 0.0)
        assertEquals(116.3975, location.second, 0.0)
    }

    @Test
    fun `putConfig emits versioned domain config json in authenticated payload`() {
        val locationManager = mockk<LocationManager>()
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "exchange_key", any())
        } answers {
            val bundle = invocation.args[2] as Bundle
            bundle.putEncryptedSessionKey("auth_put_config_key")
            true
        }
        MockServiceHelper.tryInitService(locationManager)

        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("enable", true)
            .putString("selectedLocation", "Tokyo,Station,35.6812,139.7671")
            .putFloat("altitude", 44.0f)
            .putFloat("speed", 1.5f)
            .putInt("minSatelliteCount", 20)
            .putBoolean("enableNMEA", true)
            .putBoolean("hideBaseStation", true)
            .commit()

        var sentBundle: Bundle? = null
        every {
            locationManager.sendExtraCommand(MockServiceHelper.PROVIDER_NAME, "auth_put_config_key", any())
        } answers {
            sentBundle = invocation.args[2] as Bundle
            true
        }

        assertTrue(MockServiceHelper.putConfig(locationManager, context))
        val sent = sentBundle!!
        val json = JSONObject(sent.getString(MockCommandContract.Keys.MOCK_DOMAIN_CONFIG_JSON)!!)

        assertEquals(MockCommandContract.Commands.PUT_CONFIG, sent.getString(MockCommandContract.Keys.COMMAND_ID))
        assertEquals(35.6812, sent.getDouble(MockCommandContract.Keys.LATITUDE), 0.0)
        assertEquals(139.7671, sent.getDouble(MockCommandContract.Keys.LONGITUDE), 0.0)
        assertEquals(tv.kyshu.xposed.config.MockDomainConfig.CURRENT_SCHEMA_VERSION, json.getInt("schema_version"))
        assertEquals(35.6812, json.getJSONObject("location").getDouble("latitude"), 0.0)
        assertEquals(139.7671, json.getJSONObject("location").getDouble("longitude"), 0.0)
        assertEquals(20, json.getJSONObject("gnss").getInt("min_satellite_count"))
        assertTrue(json.getJSONObject("nmea").getBoolean("enabled"))
        assertEquals("MASK_REAL", json.getJSONObject("switches").getJSONObject("CELL").getString("mode"))
    }

    @Test
    fun `content provider fallback emits signed bundle compatible with remote security`() {
        MockServiceHelper::class.java.getDeclaredField("randomKey").apply {
            isAccessible = true
            set(MockServiceHelper, "auth_fallback_key")
        }
        val insertedValues = arrayOfNulls<ContentValues>(1)
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        every { resolver.insert(any(), any()) } answers {
            insertedValues[0] = invocation.args[1] as ContentValues
            Uri.parse("content://tv.kyshu.xposed.config/cmd/1")
        }

        val method = MockServiceHelper::class.java.getDeclaredMethod(
            "sendViaContentProvider",
            Context::class.java,
            String::class.java,
            Bundle::class.java,
        ).apply { isAccessible = true }
        val sent = method.invoke(
            MockServiceHelper,
            context,
            "update_location",
            Bundle().apply {
                putDouble("lat", 39.9087)
                putDouble("lon", 116.3975)
            },
        ) as Boolean

        assertTrue(sent)
        val commandJson = JSONObject(insertedValues[0]!!.getAsString("command"))
        assertEquals("auth_fallback_key", commandJson.getString("session_key"))
        val decoded = RemoteCommandBundleCodec.decode(commandJson.getString("bundle"))
        val guard = RemoteCommandSecurity.ReplayGuard()

        assertFalse(commandJson.has("command_id"))
        assertNotNull(decoded.getByteArray("p_lat"))
        assertNotNull(decoded.getByteArray("p_lon"))
        assertTrue(guard.registerSession("auth_fallback_key"))
        assertTrue(guard.accept("auth_fallback_key", decoded))
        assertTrue(RemoteCommandSecurity.decryptSensitivePayload(decoded, "auth_fallback_key"))
        assertEquals("update_location", decoded.getString("command_id"))
        assertEquals(39.9087, decoded.getDouble("lat"), 0.0)
        assertEquals(116.3975, decoded.getDouble("lon"), 0.0)
    }
}
