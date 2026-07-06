package tv.kyshu.xposed.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorDataTest {
    @Test
    fun `motion mock is disabled by default`() {
        val data = SensorData()

        assertFalse(data.hookSensor)
        assertFalse(data.enableMotionMock)
    }

    @Test
    fun `snake case sensor fields update motion and step switches`() {
        val data = SensorData()

        data.updateFromMap(
            mapOf(
                "hook_sensor" to true,
                "enable_motion_mock" to true,
                "enable_step_count_mock" to true,
            )
        )

        assertTrue(data.hookSensor)
        assertTrue(data.enableMotionMock)
        assertTrue(data.enableStepCountMock)
    }

    @Test
    fun `step only config enables sensor hook`() {
        val data = SensorData()

        data.updateFromMap(mapOf("enable_step_count_mock" to true))

        assertTrue(data.hookSensor)
        assertTrue(data.enableStepCountMock)
        assertFalse(data.enableMotionMock)
    }
}
