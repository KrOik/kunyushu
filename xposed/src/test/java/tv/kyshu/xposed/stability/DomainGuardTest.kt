package tv.kyshu.xposed.stability

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DomainGuardTest {
    @After
    fun tearDown() {
        DomainGuard.resetAll()
    }

    @Test
    fun `disableDomain marks domain inactive until reset`() {
        val domain = DomainGuard.Domain.WLAN

        assertFalse(DomainGuard.isDomainDisabled(domain))

        DomainGuard.disableDomain(domain, "test disable")

        assertTrue(DomainGuard.isDomainDisabled(domain))

        DomainGuard.resetDomain(domain)

        assertFalse(DomainGuard.isDomainDisabled(domain))
    }
}
