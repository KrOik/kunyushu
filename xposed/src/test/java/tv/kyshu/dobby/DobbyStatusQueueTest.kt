package tv.kyshu.dobby

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DobbyStatusQueueTest {
    @After
    fun tearDown() {
        Dobby.resetForTest()
    }

    @Test
    fun `status is queued while native library is unavailable`() {
        Dobby.resetForTest()

        assertFalse(Dobby.setStatusWhenLoaded(true))

        assertEquals(true, Dobby.pendingStatusForTest)
        assertFalse(Dobby.isAvailable())
    }

    @Test
    fun `latest queued status wins before native library loads`() {
        Dobby.resetForTest()

        assertFalse(Dobby.setStatusWhenLoaded(true))
        assertFalse(Dobby.setStatusWhenLoaded(false))

        assertEquals(false, Dobby.pendingStatusForTest)
        assertEquals("not loaded", Dobby.unavailableReason)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `absolute path native load is denied`() {
        Dobby.resetForTest()

        assertFalse(Dobby.load("/data/local/portal-lib/libportal.so"))

        assertFalse(Dobby.isAvailable())
        assertTrue(Dobby.unavailableReason.contains("SecurityException"))
        assertTrue(Dobby.unavailableReason.contains("absolute native load disabled"))
    }
}
