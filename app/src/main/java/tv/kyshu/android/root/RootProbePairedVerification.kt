package tv.kyshu.android.root

/**
 * Verifies the root-probe before/mock/after evidence sequence by field shape.
 *
 * Root probe artifacts intentionally do not retain raw values, so this verifier
 * proves that the restored phase exposes the same observable field shape as the
 * baseline phase and that the active phase produced the expected mockable field
 * surface. It does not claim value-level equality.
 */
object RootProbePairedVerification {
    enum class Status {
        RESTORED,
        DRIFT,
        INSUFFICIENT_MOCK_EVIDENCE,
    }

    data class Result(
        val status: Status,
        val fieldShapeRestored: Boolean,
        val mockEvidencePresent: Boolean,
        val baselineFieldCount: Int,
        val activeFieldCount: Int,
        val restoredFieldCount: Int,
        val missingAfterRestore: List<String>,
        val extraAfterRestore: List<String>,
        val requiredMockFieldsMissing: List<String>,
        val summary: String,
    )

    fun verify(
        before: RootProbeEvidence.Artifact,
        active: RootProbeEvidence.Artifact,
        after: RootProbeEvidence.Artifact,
        requiredMockFields: Set<String> = emptySet(),
    ): Result {
        val beforeShape = before.observations.toShapeSet()
        val activeShape = active.observations.toShapeSet()
        val afterShape = after.observations.toShapeSet()
        val missingAfter = (beforeShape - afterShape).map { it.render() }.sorted()
        val extraAfter = (afterShape - beforeShape).map { it.render() }.sorted()
        val requiredMissing = requiredMockFields
            .filterNot { required ->
                active.observations.any { it.path == required }
            }
            .sorted()
        val fieldShapeRestored = missingAfter.isEmpty() && extraAfter.isEmpty()
        val mockEvidencePresent = activeShape.isNotEmpty() && requiredMissing.isEmpty()
        val status = when {
            !mockEvidencePresent -> Status.INSUFFICIENT_MOCK_EVIDENCE
            fieldShapeRestored -> Status.RESTORED
            else -> Status.DRIFT
        }
        return Result(
            status = status,
            fieldShapeRestored = fieldShapeRestored,
            mockEvidencePresent = mockEvidencePresent,
            baselineFieldCount = beforeShape.size,
            activeFieldCount = activeShape.size,
            restoredFieldCount = afterShape.size,
            missingAfterRestore = missingAfter,
            extraAfterRestore = extraAfter,
            requiredMockFieldsMissing = requiredMissing,
            summary = buildSummary(status, beforeShape.size, activeShape.size, afterShape.size),
        )
    }

    private fun List<RootProbeEvidence.FieldObservation>.toShapeSet(): Set<FieldShape> {
        return map {
            FieldShape(
                path = it.path,
                type = it.type,
                source = it.source,
                mockable = it.mockable,
                vendorExtension = it.vendorExtension,
            )
        }.toSet()
    }

    private fun buildSummary(status: Status, before: Int, active: Int, after: Int): String {
        return "status=$status baseline_fields=$before active_fields=$active restored_fields=$after value_level=false"
    }

    private data class FieldShape(
        val path: String,
        val type: String,
        val source: String,
        val mockable: Boolean,
        val vendorExtension: Boolean,
    ) {
        fun render(): String = "$source:$path:$type:mockable=$mockable:vendor=$vendorExtension"
    }
}
