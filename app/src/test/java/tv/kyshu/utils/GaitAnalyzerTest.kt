package tv.kyshu.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class GaitAnalyzerTest {

    @Test
    fun analyzeFitsMockWalkShapedWalkingSignal() {
        val samples = syntheticLinearSamples(freqHz = 2.0, durationSec = 6.0, fs = 60.0)

        val params = GaitAnalyzer.analyze(samples, emptyList(), emptyList())
        val verify = GaitAnalyzer.verify(samples, emptyList(), emptyList(), params)

        assertEquals(2.0f, params.frequency, 0.25f)
        assertEquals(3, params.fourierAccel.size)
        assertEquals(3, params.pulseAccel.size)
        assertEquals(3, params.freqMulAccel.size)
        assertEquals(3, params.dcAccel.size)
        assertTrue(params.noiseSigmaAccel >= 0f)
        assertTrue(params.humanize)
        assertEquals(0.04f, params.humanizeIntensity, 0.001f)
        assertTrue(verify.linear > 70f)
    }

    @Test
    fun analyzeReturnsReplayCompatibleDefaultsForInsufficientData() {
        val params = GaitAnalyzer.analyze(emptyList(), emptyList(), emptyList())

        assertEquals(2.8f, params.frequency, 0.01f)
        assertEquals(3, params.gravity.size)
        assertEquals(3, params.fourierAccel.size)
        assertEquals(3, params.freqMulAccel.size)
        assertEquals(0.04f, params.humanizeIntensity, 0.001f)
    }

    private fun syntheticLinearSamples(
        freqHz: Double,
        durationSec: Double,
        fs: Double,
    ): List<GaitAnalyzer.SensorSample> {
        val count = (durationSec * fs).toInt()
        val startNs = 1_000_000_000L
        return List(count) { index ->
            val t = index / fs
            val phase = 2.0 * PI * freqHz * t
            GaitAnalyzer.SensorSample(
                timestamp = startNs + (t * 1_000_000_000.0).toLong(),
                x = (1.4 + 1.1 * sin(phase)).toFloat(),
                y = (0.3 * sin(phase + PI / 2.0)).toFloat(),
                z = (0.6 * sin(2.0 * phase)).toFloat(),
            )
        }
    }
}
