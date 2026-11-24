package tv.kyshu.xposed.hooks.nmea

import org.junit.Assert.*
import org.junit.Test
import tv.kyshu.xposed.hooks.nmea.NmeaCallbackContract.Action

class NmeaCallbackContractTest {
    interface BinderListener { fun onNmeaReceived(timestamp: Long, nmea: String) }
    interface ReversedListener { fun onNmeaReceived(nmea: String, timestamp: Long) }
    interface VerifiedService {
        fun registerGnssNmeaCallback(callback: BinderListener, packageName: String, attributionTag: String?, listenerId: String)
    }
    interface WrongCallbackService {
        fun registerGnssNmeaCallback(callback: ReversedListener, packageName: String)
    }
    interface WrongReturnService {
        fun registerGnssNmeaCallback(callback: BinderListener, packageName: String): Boolean
    }
    interface UnknownArgumentService {
        fun registerGnssNmeaCallback(callback: BinderListener, flags: Int)
    }
    open class ListenerBase : BinderListener {
        override fun onNmeaReceived(timestamp: Long, nmea: String) = Unit
    }
    class InheritedListener : ListenerBase()

    @Test fun `verified four argument registration is supported and inherited callbacks resolve`() {
        assertTrue(NmeaCallbackContract.supportsRegistration(VerifiedService::class.java.methods.single()))
        assertNotNull(NmeaCallbackContract.callbackMethod(InheritedListener::class.java))
    }

    @Test fun `unknown signatures are rejected instead of guessing argument positions`() {
        listOf(WrongCallbackService::class.java, WrongReturnService::class.java, UnknownArgumentService::class.java)
            .forEach { assertFalse(NmeaCallbackContract.supportsRegistration(it.methods.single())) }
    }

    @Test fun `disabled mock always restores callbacks while active mock isolates internal consumer`() {
        assertEquals(Action.PASSTHROUGH, NmeaCallbackContract.action(false, true, false, true))
        assertEquals(Action.SUPPRESS, NmeaCallbackContract.action(true, true, true, false))
        assertEquals(Action.SUPPRESS, NmeaCallbackContract.action(true, true, true, true))
        assertEquals(Action.REWRITE, NmeaCallbackContract.action(true, false, true, false))
        assertEquals(Action.PASSTHROUGH, NmeaCallbackContract.action(true, false, true, true))
    }
}
