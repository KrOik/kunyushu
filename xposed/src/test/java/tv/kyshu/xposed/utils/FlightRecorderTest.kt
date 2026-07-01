package tv.kyshu.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FlightRecorderTest {

    @Test
    fun `ring buffer retains at most MAX_EVENTS events`() {
        FlightRecorder.clearForTest()

        // Record 257 events (MAX_EVENTS = 256)
        repeat(257) { i ->
            FlightRecorder.state(stepId = "TEST", detail = "event_$i")
        }

        val dump = FlightRecorder.dump(lastN = 300)
        val lines = dump.lines().filter { it.isNotBlank() }

        // Should have at most 256 events
        assertTrue("Expected ≤256 events but got ${lines.size}", lines.size <= 256)
        // The first event (event_0) should be evicted
        assertTrue("event_0 should be evicted", lines.none { it.contains("event_0") })
        // The last event (event_256) should be present
        assertTrue("event_256 should be present", lines.any { it.contains("event_256") })
    }

    @Test
    fun `dump respects lastN limit`() {
        FlightRecorder.clearForTest()

        repeat(10) { i ->
            FlightRecorder.state(stepId = "TEST", detail = "evt_$i")
        }

        val dump = FlightRecorder.dump(lastN = 3)
        val lines = dump.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
    }

    @Test
    fun `event contains required json fields`() {
        FlightRecorder.clearForTest()

        FlightRecorder.state(stepId = "BOOT", detail = "test_bootstrap")

        val dump = FlightRecorder.dump(lastN = 1)
        val line = dump.lines().first { it.isNotBlank() }

        assertTrue("Missing t (timestamp) field", line.contains("\"t\":"))
        assertTrue("Missing b (bootHash) field", line.contains("\"b\":"))
        assertTrue("Missing s (stepId) field", line.contains("\"s\""))
        assertTrue("Missing e (eventType) field", line.contains("\"e\""))
        assertTrue("Missing d (detail) field", line.contains("\"d\""))
    }

    @Test
    fun `gatePass and gateSkip produce different event types`() {
        FlightRecorder.clearForTest()

        FlightRecorder.gatePass(stepId = "TEST_GATE", detail = "passed")
        FlightRecorder.gateSkip(stepId = "TEST_GATE", detail = "skipped")

        val dump = FlightRecorder.dump(lastN = 10)
        assertTrue("Should contain GATE_PASS", dump.contains("\"e\":\"GATE_PASS\""))
        assertTrue("Should contain GATE_SKIP", dump.contains("\"e\":\"GATE_SKIP\""))
    }
}
