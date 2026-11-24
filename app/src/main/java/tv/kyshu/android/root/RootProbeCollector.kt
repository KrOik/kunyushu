package tv.kyshu.android.root

import android.content.Context
import java.io.File

/**
 * Root probe artifact collector.
 *
 * Current implementation is dry-run only: it validates the command policy and
 * writes the artifact that a future executor must use. It does not invoke root.
 */
object RootProbeCollector {
    data class DryRunResult(
        val decision: RootProbeCommandPolicy.Decision,
        val artifact: RootProbeEvidence.Artifact?,
        val file: File?,
    )

    data class OutputRecordResult(
        val decision: RootProbeCommandPolicy.Decision,
        val parseResult: RootProbeObservationParser.Result?,
        val artifact: RootProbeEvidence.Artifact?,
        val file: File?,
    )

    fun collectDryRun(
        context: Context,
        request: RootProbeCommandPolicy.ProbeCommandRequest,
        artifactDirectory: File,
        moduleScope: String,
        targetProcess: String = "",
        timestampMs: Long = System.currentTimeMillis(),
    ): DryRunResult {
        val decision = RootProbeCommandPolicy.dryRun(request)
        val plan = decision.plan ?: return DryRunResult(
            decision = decision,
            artifact = null,
            file = null,
        )
        val artifact = RootProbeEvidence.createArtifact(
            command = plan,
            deviceContext = RootProbeDeviceContext.capture(
                context = context,
                moduleScope = moduleScope,
                targetProcess = targetProcess,
            ),
            observations = emptyList(),
            failures = listOf(
                RootProbeEvidence.failure(
                    reason = "dry_run_only",
                    detail = "Command was planned but not executed",
                ),
            ),
        )
        val file = RootProbeEvidence.writeArtifact(artifactDirectory, artifact, timestampMs)
        return DryRunResult(
            decision = decision,
            artifact = artifact,
            file = file,
        )
    }

    fun recordProvidedOutput(
        context: Context,
        request: RootProbeCommandPolicy.ProbeCommandRequest,
        output: String,
        artifactDirectory: File,
        moduleScope: String,
        targetProcess: String = "",
        timestampMs: Long = System.currentTimeMillis(),
    ): OutputRecordResult {
        val decision = RootProbeCommandPolicy.approveForExecution(request)
        val plan = decision.plan ?: return OutputRecordResult(
            decision = decision,
            parseResult = null,
            artifact = null,
            file = null,
        )
        val source = plan.argv.take(2).joinToString(".")
        val parsed = RootProbeObservationParser.parse(
            source = source,
            output = output,
        )
        val failures = buildList {
            if (parsed.skippedSensitive > 0) {
                add(
                    RootProbeEvidence.failure(
                        reason = "sensitive_fields_skipped",
                        detail = "count=${parsed.skippedSensitive}",
                    ),
                )
            }
            if (parsed.skippedMalformed > 0) {
                add(
                    RootProbeEvidence.failure(
                        reason = "malformed_fields_skipped",
                        detail = "count=${parsed.skippedMalformed}",
                    ),
                )
            }
        }
        val artifact = RootProbeEvidence.createArtifact(
            command = plan,
            deviceContext = RootProbeDeviceContext.capture(
                context = context,
                moduleScope = moduleScope,
                targetProcess = targetProcess,
            ),
            observations = parsed.observations,
            failures = failures,
            rawOutput = output,
        )
        val file = RootProbeEvidence.writeArtifact(artifactDirectory, artifact, timestampMs)
        return OutputRecordResult(
            decision = decision,
            parseResult = parsed,
            artifact = artifact,
            file = file,
        )
    }
}
