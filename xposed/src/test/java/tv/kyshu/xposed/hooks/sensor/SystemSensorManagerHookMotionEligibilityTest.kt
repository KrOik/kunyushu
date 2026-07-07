package tv.kyshu.xposed.hooks.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import tv.kyshu.xposed.utils.FakeLoc
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class SystemSensorManagerHookMotionEligibilityTest {

    @After
    fun resetSensorConfig() {
        FakeLoc.sensorData.enableStepCountMock = false
        FakeLoc.sensorData.enableMotionMock = false
        FakeLoc.sensorData.enableShakeMock = false
        SystemSensorManagerHook.clearDeliveryHandlersForTest()
        SystemSensorManagerHook.resetStepCounterForTest()
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

    @Test
    fun `active sensor pusher posts callbacks through registered handler`() {
        val calls = AtomicInteger(0)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                calls.incrementAndGet()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val handler = Handler(Looper.getMainLooper())

        SystemSensorManagerHook.rememberDeliveryHandlerForTest(listener, handler)

        assertTrue(SystemSensorManagerHook.deliverSensorEventForTest(listener, sensorEvent(valueCount = 1)))
        assertEquals(0, calls.get())

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, calls.get())
    }

    @Test
    fun `step counter replacement never falls back to real sensor value`() {
        SystemSensorManagerHook.resetStepCounterForTest(current = 0L)

        assertEquals(0.0f, SystemSensorManagerHook.replaceStepCounterValueForTest(12_345.0f), 0.0f)

        SystemSensorManagerHook.resetStepCounterForTest(current = 42L)

        assertEquals(42.0f, SystemSensorManagerHook.replaceStepCounterValueForTest(12_345.0f), 0.0f)
    }

    private fun sensorEvent(valueCount: Int): SensorEvent {
        val ctor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        ctor.isAccessible = true
        return ctor.newInstance(valueCount)
    }
}
