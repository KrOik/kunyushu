package tv.kyshu.xposed.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@Suppress("DEPRECATION")
class MockProbeLogTest {
    @After
    fun tearDown() {
        MockProbeLog.resetRateLimitForTest()
    }

    @Test
    fun `move ipc logs are sampled and summarized`() {
        MockProbeLog.resetRateLimitForTest()

        assertEquals(
            "[IPC_RCVD] move -> processing",
            MockProbeLog.formatIpcReceivedForTest("move", "processing", nowMs = 1_000L),
        )
        assertNull(MockProbeLog.formatIpcReceivedForTest("move", "processing", nowMs = 1_100L))
        assertNull(MockProbeLog.formatIpcReceivedForTest("move", "processing", nowMs = 2_000L))
        assertEquals(
            "[IPC_RCVD] move -> processing suppressed=2/5s",
            MockProbeLog.formatIpcReceivedForTest("move", "processing", nowMs = 6_000L),
        )
    }

    @Test
    fun `non move ipc logs are not sampled`() {
        MockProbeLog.resetRateLimitForTest()

        assertEquals(
            "[IPC_RCVD] ping -> processing",
            MockProbeLog.formatIpcReceivedForTest("ping", "processing", nowMs = 1_000L),
        )
        assertEquals(
            "[IPC_RCVD] ping -> processing",
            MockProbeLog.formatIpcReceivedForTest("ping", "processing", nowMs = 1_100L),
        )
    }
}
