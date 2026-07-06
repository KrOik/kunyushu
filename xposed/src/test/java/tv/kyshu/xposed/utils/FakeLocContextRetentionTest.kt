package tv.kyshu.xposed.utils

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FakeLocContextRetentionTest {
    private class WrappedContext(base: Context) : ContextWrapper(base)
    private class PackageNamedContext(
        base: Context,
        private val name: String,
    ) : ContextWrapper(base) {
        override fun getPackageName(): String = name
        override fun getApplicationContext(): Context = this
    }

    @Before
    fun setUp() {
        FakeLoc.clearContextForTest()
    }

    @Test
    fun `setContext retains application context for provider fallbacks`() {
        val app = RuntimeEnvironment.getApplication()

        assertTrue(FakeLoc.setContext(WrappedContext(app)))

        assertNotNull(FakeLoc.getContext())
        assertSame(app.applicationContext, FakeLoc.getContext())
    }

    @Test
    fun `non authoritative app context cannot replace android context`() {
        val app = RuntimeEnvironment.getApplication()
        val androidContext = PackageNamedContext(app, "android")
        val externalContext = PackageNamedContext(app, "com.x1y9.probe")

        assertTrue(FakeLoc.setContext(androidContext, authoritative = true))
        assertFalse(FakeLoc.setContext(externalContext, authoritative = false))

        assertSame(androidContext, FakeLoc.getContext())
    }
}
