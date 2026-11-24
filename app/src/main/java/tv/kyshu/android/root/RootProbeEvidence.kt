package tv.kyshu.android.root

import tv.kyshu.android.root.RootProbeCommandPolicy.PlannedProbeCommand
import tv.kyshu.xposed.utils.LogRedactor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Field-shape evidence model for rooted read-only probes.
 *
 * Artifacts keep command intent, sanitized device context, and field metadata.
 * Raw command output is intentionally not retained.
 */
object RootProbeEvidence {
    private const val SCHEMA_VERSION = 1
    private const val MAX_LABEL_LENGTH = 120
    private const val MAX_FIELD_LENGTH = 160
    private const val FILE_PREFIX = "root_probe_"
    private const val FILE_SUFFIX = ".json"

    private val sensitiveNameParts = listOf(
        "imei",
        "meid",
        "imsi",
        "iccid",
        "serial",
        "androidid",
        "android_id",
        "attestation",
        "subscriberid",
        "subscriber_id",
        "deviceid",
        "device_id",
        "line1number",
        "line1_number",
        "simserial",
        "sim_serial",
    )

    data class DeviceContext(
        val sdk: Int,
        val rom: String,
        val manufacturer: String,
        val brand: String,
        val model: String,
        val moduleScope: String,
        val targetProcess: String,
        val permissions: Map<String, Boolean>,
        val featureFlags: Map<String, Boolean>,
    ) {
        init {
            require(sdk in 1..100) { "sdk_out_of_range" }
            listOf(rom, manufacturer, brand, model, moduleScope, targetProcess).forEach {
                require(isSafeLabel(it)) { "unsafe_context_label" }
            }
            require(permissions.keys.all(::isSafeLabel)) { "unsafe_permission_key" }
            require(featureFlags.keys.all(::isSafeLabel)) { "unsafe_feature_flag_key" }
        }

        fun sanitized(): DeviceContext {
            return copy(
                rom = sanitizeLabel(rom),
                manufacturer = sanitizeLabel(manufacturer),
                brand = sanitizeLabel(brand),
                model = sanitizeLabel(model),
                moduleScope = sanitizeLabel(moduleScope),
                targetProcess = sanitizeLabel(targetProcess),
                permissions = permissions.toSortedMap(),
                featureFlags = featureFlags.toSortedMap(),
            )
        }
    }

    data class FieldObservation(
        val path: String,
        val type: String,
        val source: String,
        val mockable: Boolean,
        val vendorExtension: Boolean,
        val notes: String? = null,
    ) {
        init {
            require(isSafeFieldToken(path)) { "unsafe_field_path" }
            require(isSafeFieldToken(type)) { "unsafe_field_type" }
            require(isSafeLabel(source)) { "unsafe_field_source" }
            require(notes == null || isSafeLabel(notes)) { "unsafe_field_notes" }
            require(!containsSensitiveName(path)) { "sensitive_field_path" }
        }
    }

    data class ProbeFailure(
        val reason: String,
        val detail: String,
    ) {
        init {
            require(isSafeLabel(reason)) { "unsafe_failure_reason" }
            require(isSafeLabel(detail)) { "unsafe_failure_detail" }
        }
    }

    data class Artifact(
        val schemaVersion: Int,
        val commandArgv: List<String>,
        val commandPreview: String,
        val purpose: String,
        val expectedFields: List<String>,
        val deviceContext: DeviceContext,
        val observations: List<FieldObservation>,
        val failures: List<ProbeFailure>,
        val rawOutputBytesDropped: Int,
        val rawOutputRetained: Boolean,
    ) {
        init {
            require(schemaVersion == SCHEMA_VERSION) { "unsupported_schema_version" }
            require(!rawOutputRetained) { "raw_output_retention_forbidden" }
        }
    }

    fun createArtifact(
        command: PlannedProbeCommand,
        deviceContext: DeviceContext,
        observations: List<FieldObservation>,
        failures: List<ProbeFailure> = emptyList(),
        rawOutput: String? = null,
    ): Artifact {
        return Artifact(
            schemaVersion = SCHEMA_VERSION,
            commandArgv = command.argv,
            commandPreview = LogRedactor.redactForLog(command.preview),
            purpose = sanitizeLabel(command.purpose),
            expectedFields = command.expectedFields.map(::sanitizeLabel),
            deviceContext = deviceContext.sanitized(),
            observations = observations,
            failures = failures,
            rawOutputBytesDropped = rawOutput?.toByteArray(Charsets.UTF_8)?.size ?: 0,
            rawOutputRetained = false,
        )
    }

    fun failure(reason: String, detail: String): ProbeFailure {
        val redactedDetail = RootProbeCommandPolicy.redactProbeOutput(detail)
        return ProbeFailure(
            reason = sanitizeLabel(reason),
            detail = sanitizeLabel(redactedDetail),
        )
    }

    fun toJsonString(artifact: Artifact): String {
        val root = JSONObject()
            .put("schema_version", artifact.schemaVersion)
            .put("command_argv", artifact.commandArgv.toJsonArray())
            .put("command_preview", LogRedactor.redactForLog(artifact.commandPreview))
            .put("purpose", sanitizeLabel(artifact.purpose))
            .put("expected_fields", artifact.expectedFields.map(::sanitizeLabel).toJsonArray())
            .put("device_context", artifact.deviceContext.sanitized().toJson())
            .put("observations", artifact.observations.toJsonArray { it.toJson() })
            .put("failures", artifact.failures.toJsonArray { it.toJson() })
            .put("raw_output_bytes_dropped", artifact.rawOutputBytesDropped)
            .put("raw_output_retained", false)
        return root.toString(2)
    }

    fun fromJsonString(json: String): Artifact {
        val root = JSONObject(json)
        require(!root.has("raw_output")) { "raw_output_field_forbidden" }
        require(!root.has("rawOutput")) { "raw_output_field_forbidden" }
        return Artifact(
            schemaVersion = root.getInt("schema_version"),
            commandArgv = root.getJSONArray("command_argv").toStringList(),
            commandPreview = sanitizeLabel(root.getString("command_preview")),
            purpose = sanitizeLabel(root.getString("purpose")),
            expectedFields = root.getJSONArray("expected_fields").toStringList().map(::sanitizeLabel),
            deviceContext = root.getJSONObject("device_context").toDeviceContext(),
            observations = root.getJSONArray("observations").mapObjects { it.toFieldObservation() },
            failures = root.getJSONArray("failures").mapObjects { it.toProbeFailure() },
            rawOutputBytesDropped = root.getInt("raw_output_bytes_dropped"),
            rawOutputRetained = root.getBoolean("raw_output_retained"),
        )
    }

    fun writeArtifact(directory: File, artifact: Artifact, timestampMs: Long = System.currentTimeMillis()): File {
        require(directory.exists() || directory.mkdirs()) { "artifact_directory_unavailable" }
        require(directory.isDirectory) { "artifact_directory_not_directory" }
        val root = directory.canonicalFile
        val safeTimestamp = timestampMs.coerceAtLeast(0L)
        val target = nextArtifactFile(root, safeTimestamp)
        val temp = File(root, "${target.name}.tmp")
        temp.writeText(toJsonString(artifact), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("artifact_write_failed")
        }
        return target
    }

    fun readArtifact(file: File): Artifact {
        return fromJsonString(file.readText(Charsets.UTF_8))
    }

    private fun isSafeLabel(value: String): Boolean {
        return value.isNotBlank() &&
            value.length <= MAX_LABEL_LENGTH &&
            value.none { it == '\u0000' || it == '\n' || it == '\r' } &&
            !containsSensitiveName(value)
    }

    private fun isSafeFieldToken(value: String): Boolean {
        return value.isNotBlank() &&
            value.length <= MAX_FIELD_LENGTH &&
            value.none { it == '\u0000' || it == '\n' || it == '\r' } &&
            value.all { it.isLetterOrDigit() || it in setOf('.', '_', '-', '<', '>', '[', ']') }
    }

    private fun sanitizeLabel(value: String): String {
        val redacted = RootProbeCommandPolicy.redactProbeOutput(value)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
        return redacted.take(MAX_LABEL_LENGTH).ifBlank { "<redacted>" }
    }

    private fun containsSensitiveName(value: String): Boolean {
        val normalized = value.lowercase()
            .replace("-", "_")
            .replace(".", "_")
        return sensitiveNameParts.any { normalized.contains(it) }
    }

    private fun nextArtifactFile(directory: File, timestampMs: Long): File {
        var suffix = 0
        while (suffix < 1000) {
            val name = buildString {
                append(FILE_PREFIX).append(timestampMs)
                if (suffix > 0) append('_').append(suffix)
                append(FILE_SUFFIX)
            }
            val candidate = File(directory, name).canonicalFile
            require(candidate.parentFile == directory) { "artifact_path_escape" }
            if (!candidate.exists()) return candidate
            suffix += 1
        }
        throw IllegalStateException("artifact_filename_exhausted")
    }

    private fun DeviceContext.toJson(): JSONObject {
        return JSONObject()
            .put("sdk", sdk)
            .put("rom", sanitizeLabel(rom))
            .put("manufacturer", sanitizeLabel(manufacturer))
            .put("brand", sanitizeLabel(brand))
            .put("model", sanitizeLabel(model))
            .put("module_scope", sanitizeLabel(moduleScope))
            .put("target_process", sanitizeLabel(targetProcess))
            .put("permissions", permissions.toJsonObject())
            .put("feature_flags", featureFlags.toJsonObject())
    }

    private fun FieldObservation.toJson(): JSONObject {
        val json = JSONObject()
            .put("path", path)
            .put("type", type)
            .put("source", sanitizeLabel(source))
            .put("mockable", mockable)
            .put("vendor_extension", vendorExtension)
        notes?.let { json.put("notes", sanitizeLabel(it)) }
        return json
    }

    private fun ProbeFailure.toJson(): JSONObject {
        return JSONObject()
            .put("reason", sanitizeLabel(reason))
            .put("detail", sanitizeLabel(detail))
    }

    private fun JSONObject.toDeviceContext(): DeviceContext {
        return DeviceContext(
            sdk = getInt("sdk"),
            rom = getString("rom"),
            manufacturer = getString("manufacturer"),
            brand = getString("brand"),
            model = getString("model"),
            moduleScope = getString("module_scope"),
            targetProcess = getString("target_process"),
            permissions = getJSONObject("permissions").toBooleanMap(),
            featureFlags = getJSONObject("feature_flags").toBooleanMap(),
        )
    }

    private fun JSONObject.toFieldObservation(): FieldObservation {
        return FieldObservation(
            path = getString("path"),
            type = getString("type"),
            source = getString("source"),
            mockable = getBoolean("mockable"),
            vendorExtension = getBoolean("vendor_extension"),
            notes = if (has("notes")) getString("notes") else null,
        )
    }

    private fun JSONObject.toProbeFailure(): ProbeFailure {
        return ProbeFailure(
            reason = getString("reason"),
            detail = getString("detail"),
        )
    }

    private fun Map<String, Boolean>.toJsonObject(): JSONObject {
        val json = JSONObject()
        toSortedMap().forEach { (key, value) ->
            json.put(sanitizeLabel(key), value)
        }
        return json
    }

    private fun JSONObject.toBooleanMap(): Map<String, Boolean> {
        val out = sortedMapOf<String, Boolean>()
        keys().forEach { key ->
            out[sanitizeLabel(key)] = getBoolean(key)
        }
        return out
    }

    private fun List<String>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { array.put(sanitizeLabel(it)) }
        return array
    }

    private fun <T> List<T>.toJsonArray(mapper: (T) -> JSONObject): JSONArray {
        val array = JSONArray()
        forEach { array.put(mapper(it)) }
        return array
    }

    private fun JSONArray.toStringList(): List<String> {
        return (0 until length()).map { index -> getString(index) }
    }

    private fun <T> JSONArray.mapObjects(mapper: (JSONObject) -> T): List<T> {
        return (0 until length()).map { index -> mapper(getJSONObject(index)) }
    }
}
