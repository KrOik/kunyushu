package tv.kyshu.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MockCommandContractValidationTest {
    @Test
    fun `latitude validator rejects out of range values`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockCommandContract.requireLatitude(120.0, "lat")
        }
    }

    @Test
    fun `altitude validator rejects non finite values`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockCommandContract.requireAltitude(Double.NaN, "altitude")
        }
    }

    @Test
    fun `bearing validator accepts valid heading`() {
        assertEquals(180.0, MockCommandContract.requireBearing(180.0, "bearing"), 0.0)
    }
}
