package tv.kyshu.xposed.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationServiceHookGnssCallbackTest {
    private open class ParentGnssCallback {
        fun onSvStatusChanged(status: Any?) {
            status.hashCode()
        }
    }

    private class ChildGnssCallback : ParentGnssCallback()

    private class AppStyleGnssCallback {
        fun onSatelliteStatusChanged(status: Any?) {
            status.hashCode()
        }
    }

    private class NotGnssCallback {
        fun onLocationChanged() = Unit
    }

    @Test
    fun `finds gnss callback outside first argument`() {
        val callback = ChildGnssCallback()

        assertSame(callback, LocationServiceHook.findGnssStatusCallback(arrayOf("package", null, callback)))
    }

    @Test
    fun `recognizes inherited and app style gnss status methods`() {
        assertTrue(LocationServiceHook.hasHookableGnssStatusCallbackMethod(ChildGnssCallback::class.java))
        assertTrue(LocationServiceHook.hasHookableGnssStatusCallbackMethod(AppStyleGnssCallback::class.java))
    }

    @Test
    fun `rejects non gnss callback classes`() {
        assertFalse(LocationServiceHook.hasHookableGnssStatusCallbackMethod(NotGnssCallback::class.java))
        assertNull(LocationServiceHook.findGnssStatusCallback(arrayOf("package", NotGnssCallback())))
    }
}
