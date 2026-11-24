package tv.kyshu.android.root

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ShellUtilsTest {
    @Test
    fun `non whitelisted root commands are rejected before shell execution`() {
        val executeCommand = ShellUtils::class.java
            .getDeclaredMethod("executeCommand", String::class.java)
            .apply { isAccessible = true }
        val executeCommandToBytes = ShellUtils::class.java
            .getDeclaredMethod("executeCommandToBytes", String::class.java)
            .apply { isAccessible = true }

        assertTrue(Modifier.isPrivate(executeCommand.modifiers))
        assertTrue(Modifier.isPrivate(executeCommandToBytes.modifiers))
        assertEquals("", executeCommand.invoke(ShellUtils, "cat /data/local/tmp/portal"))
        assertEquals("", executeCommand.invoke(ShellUtils, "chmod 755 /data/local/tmp/portal"))
        assertArrayEquals(ByteArray(0), executeCommandToBytes.invoke(ShellUtils, "echo ok; id") as ByteArray)
    }
}
