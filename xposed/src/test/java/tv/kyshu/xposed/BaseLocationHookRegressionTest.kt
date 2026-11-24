package tv.kyshu.xposed

import android.location.Location
import android.location.LocationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.stability.DomainGuard

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BaseLocationHookRegressionTest {
    private class TestLocationHook : BaseLocationHook()

    @Implements(Location::class)
    class CopyHookShadow {
        companion object { var copyCalls = 0 }
        @RealObject lateinit var realLocation: Location

        @Implementation
        fun set(source: Location) {
            copyCalls++
            // Reproduce BasicLocationHook on the real constructor -> set path.
            val replacement = TestLocationHook().injectLocation(source)
            reflector(LocationDirect::class.java, realLocation).set(replacement)
        }
    }

    @ForType(Location::class)
    interface LocationDirect {
        @Direct fun set(source: Location)
    }

    @Test
    @Config(shadows = [CopyHookShadow::class])
    fun `hooked copy constructor does not recurse and later injections still work`() {
        resetFakeLoc()
        CopyHookShadow.copyCalls = 0
        FakeLoc.enable = true
        FakeLoc.latitude = 39.0
        FakeLoc.longitude = 116.0
        FakeLoc.accuracy = 0f
        val origin = Location("gps").apply { latitude = 30.0; longitude = 120.0 }
        val result = TestLocationHook().injectLocation(origin)
        assertEquals(39.0, result.latitude, 0.0)
        assertTrue("Must exercise the constructor -> hooked set path", CopyHookShadow.copyCalls > 0)
        assertEquals(30.0, origin.latitude, 0.0)
        assertFalse(DomainGuard.isDomainDisabled(DomainGuard.Domain.LOCATION_INJECTION))
        FakeLoc.latitude = 38.0
        assertEquals(38.0, TestLocationHook().injectLocation(origin).latitude, 0.0)
    }

    @After
    fun tearDown() {
        resetFakeLoc()
    }

    @Test
    fun `jitterLocation accepts zero accuracy`() {
        resetFakeLoc()
        FakeLoc.latitude = 39.9042
        FakeLoc.longitude = 116.4074
        FakeLoc.accuracy = 0f

        val (lat, lon) = FakeLoc.jitterLocation()

        assertEquals(39.9042, lat, 0.0)
        assertEquals(116.4074, lon, 0.0)
    }

    @Test
    fun `injectLocation accepts zero speed amplitude`() {
        resetFakeLoc()
        FakeLoc.enable = true
        FakeLoc.latitude = 39.9042
        FakeLoc.longitude = 116.4074
        FakeLoc.altitude = 80.0
        FakeLoc.accuracy = 0f
        FakeLoc.speed = 0.0
        FakeLoc.speedAmplitude = 0.0
        FakeLoc.enableAltitudeJitter = false

        val origin = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 30.0
            longitude = 120.0
            accuracy = 0f
            speed = 0f
            time = 1_700_000_000_000L
            elapsedRealtimeNanos = 1_000_000L
        }

        val injected = TestLocationHook().injectLocation(origin)

        assertEquals(39.9042, injected.latitude, 0.0)
        assertEquals(116.4074, injected.longitude, 0.0)
        assertTrue(injected.speed >= 0f)
    }

    private fun resetFakeLoc() {
        DomainGuard.resetDomain(DomainGuard.Domain.LOCATION_INJECTION)
        FakeLoc.enable = false
        FakeLoc.enableDebugLog = false
        FakeLoc.latitude = 0.0
        FakeLoc.longitude = 0.0
        FakeLoc.altitude = 80.0
        FakeLoc.accuracy = 25.0f
        FakeLoc.speed = 1.35
        FakeLoc.bearing = 0.0
        FakeLoc.speedAmplitude = 1.0
        FakeLoc.enableAltitudeJitter = true
        FakeLoc.altitudeJitter = 0.7f
        FakeLoc.hideMock = true
        FakeLoc.disableNetworkLocation = true
        FakeLoc.lastLocation = null
    }

    @Test
    fun `invalid profile fuses injection without mutating original and stays disabled`() {
        resetFakeLoc()
        FakeLoc.enable = true
        FakeLoc.latitude = Double.NaN
        val origin = Location(LocationManager.NETWORK_PROVIDER).apply { altitude = 0.0 }
        val hook = TestLocationHook()

        assertSame(origin, hook.injectLocation(origin, realLocation = false))
        assertEquals(LocationManager.NETWORK_PROVIDER, origin.provider)
        assertEquals(0.0, origin.altitude, 0.0)
        assertTrue(DomainGuard.isDomainDisabled(DomainGuard.Domain.LOCATION_INJECTION))
        FakeLoc.latitude = 31.0
        assertSame(origin, hook.injectLocation(origin))
    }

    @Test
    fun `disabled injection preserves origin even for generated input`() {
        resetFakeLoc()
        val origin = Location(LocationManager.NETWORK_PROVIDER).apply { altitude = 0.0 }
        assertSame(origin, TestLocationHook().injectLocation(origin, realLocation = false))
        assertEquals(0.0, origin.altitude, 0.0)
        assertFalse(DomainGuard.isDomainDisabled(DomainGuard.Domain.LOCATION_INJECTION))
    }
}
