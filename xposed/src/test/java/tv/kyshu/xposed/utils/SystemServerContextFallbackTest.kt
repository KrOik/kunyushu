package tv.kyshu.xposed.utils

import android.content.Context
import android.content.ContextWrapper
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.kyshu.xposed.stability.BootStateStore
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemServerContextFallbackTest {
    private class NoDataDirContext(base: Context) : ContextWrapper(base) {
        override fun getFilesDir(): File {
            throw RuntimeException("No data directory found for package android")
        }

        override fun getCacheDir(): File {
            throw RuntimeException("No data directory found for package android")
        }
    }

    @Test
    fun `system server android context without data dirs does not break auxiliary init`() {
        val context = NoDataDirContext(RuntimeEnvironment.getApplication())

        Log.init(context)
        BootStateStore.setContext(context)
        ChannelIdentity.init(context)
    }

    @Test
    fun `default log route keeps warn and error visible in xposed logs`() {
        assertEquals(Log.Channel.CAT or Log.Channel.FILE, LogConfig.resolveChannels("Portal", Log.Level.WARN))
        assertEquals(Log.Channel.CAT or Log.Channel.FILE, LogConfig.resolveChannels("Portal", Log.Level.ERROR))
    }
}
