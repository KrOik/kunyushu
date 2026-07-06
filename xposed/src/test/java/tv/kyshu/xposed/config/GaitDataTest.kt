package tv.kyshu.xposed.config

import org.junit.Assert.assertEquals
import org.junit.Test

class GaitDataTest {
    @Test
    fun `snake case gait config updates replay json`() {
        val data = GaitData()

        data.updateFromMap(
            mapOf(
                "gait_freq" to 1.7f,
                "gait_amp_scale" to 0.8f,
                "gait_params_json" to """{"frequency":1.7}""",
            )
        )

        assertEquals(1.7f, data.gaitFreq, 0.001f)
        assertEquals(0.8f, data.gaitAmpScale, 0.001f)
        assertEquals("""{"frequency":1.7}""", data.gaitParamsJson)
    }

    @Test
    fun `camel case gait config remains supported for xsharedpreferences fallback`() {
        val data = GaitData()

        data.updateFromMap(
            mapOf(
                "gaitFreq" to 2.1f,
                "gaitAmpScale" to 1.2f,
                "gaitParamsJson" to """{"frequency":2.1}""",
            )
        )

        assertEquals(2.1f, data.gaitFreq, 0.001f)
        assertEquals(1.2f, data.gaitAmpScale, 0.001f)
        assertEquals("""{"frequency":2.1}""", data.gaitParamsJson)
    }
}
