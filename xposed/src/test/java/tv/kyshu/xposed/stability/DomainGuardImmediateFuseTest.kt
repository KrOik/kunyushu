package tv.kyshu.xposed.stability

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DomainGuardImmediateFuseTest {
    @After fun cleanUp() = DomainGuard.resetAll()

    @Test fun `tripNow is immediate and idempotent`() {
        assertTrue(DomainGuard.tripNow(DomainGuard.Domain.BLE, "first", IllegalStateException()))
        assertFalse(DomainGuard.tripNow(DomainGuard.Domain.BLE, "second", IllegalStateException()))
        assertTrue(DomainGuard.domainStates().getValue(DomainGuard.Domain.BLE))
    }
}
