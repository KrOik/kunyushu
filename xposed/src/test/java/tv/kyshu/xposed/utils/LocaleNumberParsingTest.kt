package tv.kyshu.xposed.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocaleNumberParsingTest {
    @Test
    fun `finite numbers accept dot and comma decimal separators`() {
        assertEquals(2.5, LocaleNumberParsing.parseFiniteDouble("2.5")!!, 0.0)
        assertEquals(2.5, LocaleNumberParsing.parseFiniteDouble("2,5")!!, 0.0)
        assertEquals(4.5f, LocaleNumberParsing.parseFiniteFloat("4,5")!!, 0.0f)
    }

    @Test
    fun `invalid and non finite numbers are rejected`() {
        assertNull(LocaleNumberParsing.parseFiniteDouble("bad"))
        assertNull(LocaleNumberParsing.parseFiniteDouble("NaN"))
        assertNull(LocaleNumberParsing.parseFiniteDouble("1,2,3"))
    }
}
