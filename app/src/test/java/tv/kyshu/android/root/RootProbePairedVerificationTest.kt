package tv.kyshu.android.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RootProbePairedVerificationTest {
    @Test
    fun `restored when after shape matches baseline and active has required fields`() {
        val before = artifactWith(
            field("dumpsys.wifi.mWifiStandard", "int", mockable = true),
            field("dumpsys.wifi.isPrimary", "boolean", mockable = true),
        )
        val active = artifactWith(
            field("dumpsys.wifi.mWifiStandard", "int", mockable = true),
            field("dumpsys.wifi.isPrimary", "boolean", mockable = true),
            field("dumpsys.wifi.mockEnabled", "boolean", mockable = true),
        )
        val after = artifactWith(
            field("dumpsys.wifi.mWifiStandard", "int", mockable = true),
            field("dumpsys.wifi.isPrimary", "boolean", mockable = true),
        )

        val result = RootProbePairedVerification.verify(
            before = before,
            active = active,
            after = after,
            requiredMockFields = setOf("dumpsys.wifi.mockEnabled"),
        )

        assertEquals(RootProbePairedVerification.Status.RESTORED, result.status)
        assertTrue(result.fieldShapeRestored)
        assertTrue(result.mockEvidencePresent)
        assertTrue(result.missingAfterRestore.isEmpty())
        assertTrue(result.extraAfterRestore.isEmpty())
        assertTrue(result.summary.contains("value_level=false"))
    }

    @Test
    fun `drift when restored phase keeps extra mock field`() {
        val before = artifactWith(
            field("dumpsys.bluetooth_manager.connected", "boolean", mockable = true),
        )
        val active = artifactWith(
            field("dumpsys.bluetooth_manager.connected", "boolean", mockable = true),
            field("dumpsys.bluetooth_manager.mockDevice", "string", mockable = true),
        )
        val after = artifactWith(
            field("dumpsys.bluetooth_manager.connected", "boolean", mockable = true),
            field("dumpsys.bluetooth_manager.mockDevice", "string", mockable = true),
        )

        val result = RootProbePairedVerification.verify(
            before = before,
            active = active,
            after = after,
            requiredMockFields = setOf("dumpsys.bluetooth_manager.mockDevice"),
        )

        assertEquals(RootProbePairedVerification.Status.DRIFT, result.status)
        assertFalse(result.fieldShapeRestored)
        assertTrue(result.mockEvidencePresent)
        assertTrue(result.extraAfterRestore.any { it.contains("mockDevice") })
    }

    @Test
    fun `insufficient when active phase lacks required mock evidence`() {
        val before = artifactWith(
            field("dumpsys.nfc.enabled", "boolean", mockable = true),
        )
        val active = artifactWith(
            field("dumpsys.nfc.enabled", "boolean", mockable = true),
        )
        val after = artifactWith(
            field("dumpsys.nfc.enabled", "boolean", mockable = true),
        )

        val result = RootProbePairedVerification.verify(
            before = before,
            active = active,
            after = after,
            requiredMockFields = setOf("dumpsys.nfc.mockTag"),
        )

        assertEquals(RootProbePairedVerification.Status.INSUFFICIENT_MOCK_EVIDENCE, result.status)
        assertTrue(result.fieldShapeRestored)
        assertFalse(result.mockEvidencePresent)
        assertEquals(listOf("dumpsys.nfc.mockTag"), result.requiredMockFieldsMissing)
    }

    private fun artifactWith(vararg observations: RootProbeEvidence.FieldObservation): RootProbeEvidence.Artifact {
        val decision = RootProbeCommandPolicy.dryRun(
            RootProbeCommandPolicy.ProbeCommandRequest(
                argv = listOf("dumpsys", "wifi"),
                purpose = "Collect field shape",
            ),
        )
        return RootProbeEvidence.createArtifact(
            command = decision.plan!!,
            deviceContext = RootProbeEvidence.DeviceContext(
                sdk = 35,
                rom = "Android 15",
                manufacturer = "generic",
                brand = "generic",
                model = "test-device",
                moduleScope = "system-framework",
                targetProcess = "system_server",
                permissions = emptyMap(),
                featureFlags = emptyMap(),
            ),
            observations = observations.toList(),
        )
    }

    private fun field(
        path: String,
        type: String,
        mockable: Boolean,
        vendorExtension: Boolean = false,
    ): RootProbeEvidence.FieldObservation {
        return RootProbeEvidence.FieldObservation(
            path = path,
            type = type,
            source = path.substringBeforeLast('.'),
            mockable = mockable,
            vendorExtension = vendorExtension,
        )
    }
}
