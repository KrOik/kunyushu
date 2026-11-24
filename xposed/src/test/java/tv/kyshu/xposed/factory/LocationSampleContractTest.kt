package tv.kyshu.xposed.factory

import android.location.Location
import android.os.SystemClock
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.kyshu.xposed.utils.FakeLoc

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationSampleContractTest {
    private val oldSpeed = FakeLoc.speed
    private val oldAltitude = FakeLoc.altitude
    private val oldJitter = FakeLoc.enableAltitudeJitter
    private val oldAmplitude = FakeLoc.speedAmplitude

    @After fun restore() {
        FakeLoc.speed = oldSpeed
        FakeLoc.altitude = oldAltitude
        FakeLoc.enableAltitudeJitter = oldJitter
        FakeLoc.speedAmplitude = oldAmplitude
        LocationFactory.resetDynamicsForTest()
    }

    @Test fun `stationary sea level sample does not inherit motion or manufacture MSL altitude`() {
        LocationFactory.resetDynamicsForTest()
        FakeLoc.speed = 0.0
        FakeLoc.speedAmplitude = 2.0
        FakeLoc.altitude = 0.0
        FakeLoc.enableAltitudeJitter = false
        val origin = Location("gps").apply {
            speed = 10f
            altitude = 150.0
            accuracy = 5f
            mslAltitudeMeters = 120.0
            mslAltitudeAccuracyMeters = 4f
            elapsedRealtimeUncertaintyNanos = 50.0
            time = 1L
            elapsedRealtimeNanos = 1L
        }
        val before = System.currentTimeMillis()
        val injected = LocationFactory.createInjectedLocation(origin)
        assertEquals(0f, injected.speed, 0f)
        assertEquals(0.0, injected.altitude, 0.0)
        assertFalse(injected.hasMslAltitude())
        assertFalse(injected.hasMslAltitudeAccuracy())
        assertFalse(injected.hasElapsedRealtimeUncertaintyNanos())
        assertTrue(injected.isComplete)
        assertTrue(injected.time in before..System.currentTimeMillis())
        assertTrue(injected.elapsedRealtimeNanos in 1..SystemClock.elapsedRealtimeNanos().coerceAtLeast(1))
        val parcel = android.os.Parcel.obtain()
        try {
            injected.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = Location.CREATOR.createFromParcel(parcel)
            assertEquals(0f, restored.speed, 0f)
            assertFalse(restored.hasMslAltitude())
            assertTrue(restored.hasAltitude())
            assertTrue(restored.isComplete)
        } finally { parcel.recycle() }
        assertEquals(10f, origin.speed, 0f)
        assertEquals(120.0, origin.mslAltitudeMeters, 0.0)
        assertEquals(1L, origin.time)
    }
}
