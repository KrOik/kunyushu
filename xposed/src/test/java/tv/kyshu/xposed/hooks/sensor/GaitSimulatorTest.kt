package tv.kyshu.xposed.hooks.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GaitSimulatorTest {

    @Test
    fun loadFromJsonReplaysAnalyzerFieldShape() {
        val simulator = GaitSimulator.loadFromJson(
            """
            {
              "frequency": 1.0,
              "gravity": [0.0, -9.81, 0.0],
              "noiseSigma": 0.0,
              "noiseSigmaAccel": 0.0,
              "noiseSigmaGyro": 0.0,
              "noiseSigmaGravity": 0.0,
              "humanize": false,
              "humanizeIntensity": 0.0,
              "dcAccel": [0.0, 0.0, 0.0],
              "dcGyro": [0.0, 0.0, 0.0],
              "fourierAccel": [[1.0, 0.0], [], []],
              "fourierGyro": [[], [], []],
              "fourierGravity": [[], [], []],
              "pulseAccel": [[], [], []],
              "pulseGyro": [[], [], []],
              "pulseGravity": [[], [], []],
              "freqMulAccel": [1.0, 1.0, 1.0],
              "freqMulGyro": [1.0, 1.0, 1.0],
              "freqMulGravity": [1.0, 1.0, 1.0]
            }
            """.trimIndent(),
        )

        assertNotNull(simulator)
        val values = simulator!!.getMotionValues(0.25f)

        assertEquals(4, values.size)
        assertEquals(1.0f, values[3][0], 0.001f)
        assertEquals(-9.81f, values[2][1], 0.001f)
        assertEquals(1.0f, values[0][0], 0.001f)
    }

    @Test
    fun humanizeIsBoundedAroundPureReplayShape() {
        val pure = GaitSimulator.loadFromJson(replayJson(humanize = false, intensity = 0.0f))
        val humanized = GaitSimulator.loadFromJson(replayJson(humanize = true, intensity = 1.0f))

        assertNotNull(pure)
        assertNotNull(humanized)

        for (i in 0..200) {
            val t = i / 40.0f
            val pureValues = pure!!.getMotionValues(t)
            val humanValues = humanized!!.getMotionValues(t)

            assertWithinReplayBand("accel", pureValues[0], humanValues[0], floor = 1.0f)
            assertWithinReplayBand("gyro", pureValues[1], humanValues[1], floor = 0.1f)
            assertWithinReplayBand("gravity", pureValues[2], humanValues[2], floor = 1.0f)
            assertWithinReplayBand("linear", pureValues[3], humanValues[3], floor = 1.0f)
        }
    }

    private fun assertWithinReplayBand(label: String, pure: FloatArray, actual: FloatArray, floor: Float) {
        for (axis in 0 until 3) {
            val limit = max(abs(pure[axis]), floor) * GaitSimulator.MAX_REPLAY_HUMANIZE_INTENSITY + 0.0005f
            val delta = abs(actual[axis] - pure[axis])
            assertTrue("$label axis=$axis delta=$delta limit=$limit", delta <= limit)
        }
    }

    private fun replayJson(humanize: Boolean, intensity: Float): String {
        return """
            {
              "frequency": 1.0,
              "gravity": [0.0, -9.81, 0.0],
              "noiseSigma": 50.0,
              "noiseSigmaAccel": 50.0,
              "noiseSigmaGyro": 50.0,
              "noiseSigmaGravity": 50.0,
              "humanize": $humanize,
              "humanizeIntensity": $intensity,
              "dcAccel": [0.0, 0.0, 0.0],
              "dcGyro": [0.0, 0.0, 0.0],
              "fourierAccel": [[1.0, 0.0], [0.6, 0.5], [0.4, -0.2]],
              "fourierGyro": [[0.4, 0.1], [0.2, 0.2], [0.1, 0.3]],
              "fourierGravity": [[0.1, 0.0], [0.2, 0.0], [0.1, 0.0]],
              "pulseAccel": [[], [], []],
              "pulseGyro": [[], [], []],
              "pulseGravity": [[], [], []],
              "freqMulAccel": [1.0, 1.0, 1.0],
              "freqMulGyro": [1.0, 1.0, 1.0],
              "freqMulGravity": [1.0, 1.0, 1.0]
            }
        """.trimIndent()
    }
}
