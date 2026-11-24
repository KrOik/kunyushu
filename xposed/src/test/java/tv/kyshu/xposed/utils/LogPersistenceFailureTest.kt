package tv.kyshu.xposed.utils

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.kyshu.xposed.stability.DomainGuard
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogPersistenceFailureTest {
    @Test fun `unwritable diagnostic paths cannot interrupt immediate fuse`() {
        val root = Files.createTempDirectory("kyshu-log-failure").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        DomainGuard.resetAll()
        Log.init(context)
        val logs = File(context.filesDir, "logs")
        // Replace only the fresh test-owned directory, forcing a deterministic I/O error.
        assertTrue(logs.delete())
        logs.writeText("not a directory")
        // Block individual persistence targets as directories, preserving cache metadata.
        val logClass = Log::class.java
        val telemetry = logClass.getDeclaredField("telemetryFile").apply { isAccessible = true }.get(Log) as File
        assertTrue(!telemetry.exists() || telemetry.delete())
        assertTrue(telemetry.mkdir())
        val crashClass = Class.forName("tv.kyshu.xposed.utils.Log\$CrashSink")
        val instance = crashClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val crash = crashClass.getDeclaredField("crashFile").apply { isAccessible = true }.get(instance) as File
        assertTrue(!crash.exists() || crash.delete())
        assertTrue(crash.mkdir())
        LogConfig.addRoute("", Log.Channel.FILE or Log.Channel.RING or Log.Channel.CRASH, Log.Level.ALL)
        try {
            assertTrue(DomainGuard.tripNow(DomainGuard.Domain.LOCATION_INJECTION, "test", IllegalArgumentException()))
            assertTrue(DomainGuard.isDomainDisabled(DomainGuard.Domain.LOCATION_INJECTION))
            assertTrue(Log.dumpTelemetry().contains("DOMAIN_IMMEDIATE_FUSE"))
            assertNull(logClass.getDeclaredField("telemetryFile").apply { isAccessible = true }.get(Log))
            assertNull(crashClass.getDeclaredField("crashFile").apply { isAccessible = true }.get(instance))
        } finally {
            LogConfig.resetForTest()
            Log.init(RuntimeEnvironment.getApplication())
            DomainGuard.resetAll()
        }
    }
}
