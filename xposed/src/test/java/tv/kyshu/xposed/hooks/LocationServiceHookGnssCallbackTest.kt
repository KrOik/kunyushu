package tv.kyshu.xposed.hooks

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
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

    @Test
    fun `uses deterministic fallback satellites in fix without rinex`() {
        assertTrue(LocationServiceHook.isFallbackSatelliteUsedInFix(15f, 30f))
        assertFalse(LocationServiceHook.isFallbackSatelliteUsedInFix(14.9f, 30f))
        assertFalse(LocationServiceHook.isFallbackSatelliteUsedInFix(15f, 29.9f))
    }

    @Test
    fun `skips OPPO DeepThinker framework location listener`() {
        assertTrue(
            LocationServiceHook.shouldSkipSystemLocationListener(
                "com.android.server.display.ambience.events.delegate.DeepThinkerDelegate\$3"
            )
        )
        assertFalse(LocationServiceHook.shouldSkipSystemLocationListener("com.example.app.LocationListener"))
    }

    @Test
    fun `rinex fingerprint changes when file metadata changes`() {
        val file = kotlin.io.path.createTempFile("portal-rinex", ".rnx").toFile()
        try {
            file.writeText("RINEX VERSION / TYPE\n")
            val first = LocationServiceHook.rinexFileFingerprint("/provider/rinex", file)

            file.appendText("updated\n")
            file.setLastModified(System.currentTimeMillis() + 1000L)
            val second = LocationServiceHook.rinexFileFingerprint("/provider/rinex", file)

            assertNotEquals(first, second)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `rinex cache dir falls back when context has no app data dirs`() {
        val fallback = kotlin.io.path.createTempDirectory("portal-rinex-cache").toFile()
            .resolve("system-cache")
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir(): File {
                throw RuntimeException("No data directory found for package android")
            }

            override fun getFilesDir(): File {
                throw RuntimeException("No data directory found for package android")
            }
        }

        try {
            assertEquals(fallback, LocationServiceHook.resolveRinexCacheDir(context, fallback))
            assertTrue(fallback.isDirectory)
        } finally {
            fallback.deleteRecursively()
        }
    }
}
