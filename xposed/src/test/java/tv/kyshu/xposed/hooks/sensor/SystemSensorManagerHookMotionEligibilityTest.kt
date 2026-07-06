package tv.kyshu.xposed.hooks.sensor

import android.hardware.Sensor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.kyshu.xposed.utils.FakeLoc

class SystemSensorManagerHookMotionEligibilityTest {

    @After
    fun resetSensorConfig() {
        FakeLoc.sensorData.enableStepCountMock = false
        FakeLoc.sensorData.enableMotionMock = false
        FakeLoc.sensorData.enableShakeMock = false
    }

    @Test
    fun `step mock enables walking IMU output`() {
        FakeLoc.sensorData.enableStepCountMock = true

        assertTrue(SystemSensorManagerHook.shouldMockMotionSensor(Sensor.TYPE_ACCELEROMETER))
        assertTrue(SystemSensorManagerHook.shouldMockMotionSensor(Sensor.TYPE_GYROSCOPE))
        assertFalse(SystemSensorManagerHook.shouldMockMotionSensor(Sensor.TYPE_STEP_COUNTER))
    }

    @Test
    fun `shake mock only enables accelerometer motion output`() {
        FakeLoc.sensorData.enableShakeMock = true

        assertTrue(SystemSensorManagerHook.shouldMockMotionSensor(Sensor.TYPE_ACCELEROMETER))
        assertFalse(SystemSensorManagerHook.shouldMockMotionSensor(Sensor.TYPE_GYROSCOPE))
    }

    @Test
    fun `step replay push interval follows gait frequency`() {
        assertEquals(357L, SystemSensorManagerHook.stepPushIntervalMsForFrequency(2.8f))
        assertEquals(250L, SystemSensorManagerHook.stepPushIntervalMsForFrequency(4.0f))
        assertEquals(1667L, SystemSensorManagerHook.stepPushIntervalMsForFrequency(0.6f))
    }

    @Test
    fun `step replay push interval clamps unsupported gait frequency`() {
        assertEquals(250L, SystemSensorManagerHook.stepPushIntervalMsForFrequency(8.0f))
        assertEquals(1667L, SystemSensorManagerHook.stepPushIntervalMsForFrequency(0.1f))
    }
}
