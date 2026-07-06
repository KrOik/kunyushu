package tv.kyshu.utils

import java.io.File

object GaitCaptureStore {
    private val requiredCaptureFiles = listOf("linear.csv", "gyro.csv", "gravity.csv")

    data class CsvParseResult(
        val samples: List<GaitAnalyzer.SensorSample>,
        val linesTotal: Int,
        val linesParsed: Int,
        val linesSkipped: Int,
    )

    data class ImportPathResult(
        val directory: File?,
        val error: String?,
    )

    fun resolveImportDirectory(rawPath: String, appPackageName: String): ImportPathResult {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return ImportPathResult(null, "路径为空")
        val canonical = try {
            File(trimmed).canonicalFile
        } catch (_: Exception) {
            return ImportPathResult(null, "路径无效或不可访问")
        }
        val path = canonical.path
        if (path.isBlank() || path == File.separator || path.contains("..")) {
            return ImportPathResult(null, "路径包含非法字符或遍历尝试")
        }
        val appDataPrefix = "/data/data/$appPackageName"
        if (path.startsWith("/data/data/") && !path.startsWith(appDataPrefix)) {
            return ImportPathResult(null, "不支持从其他应用目录导入数据")
        }
        val forbiddenPrefixes = listOf(
            "/system",
            "/sys",
            "/proc",
            "/dev",
            "/data/user",
            "/data/system",
            "/config",
            "/cache",
            "/metadata",
            "/root",
        )
        if (forbiddenPrefixes.any { path.startsWith(it) }) {
            return ImportPathResult(null, "不支持从系统目录导入数据")
        }
        if (!canonical.exists() || !canonical.isDirectory) {
            return ImportPathResult(null, "目录不存在: $path")
        }
        return ImportPathResult(canonical, null)
    }

    fun latestCaptureDirectory(externalFilesDir: File?): File? {
        val capturesDir = externalFilesDir?.resolve("captures") ?: return null
        if (!capturesDir.isDirectory) return null
        return capturesDir.listFiles()
            ?.filter { it.isDirectory && hasRequiredCaptureFiles(it) }
            ?.maxByOrNull { it.lastModified() }
    }

    fun hasRequiredCaptureFiles(directory: File): Boolean {
        return requiredCaptureFiles.all { File(directory, it).isFile }
    }

    fun readCsvSamples(file: File): CsvParseResult {
        if (!file.exists() || !file.isFile) return CsvParseResult(emptyList(), 0, 0, 0)
        val out = ArrayList<GaitAnalyzer.SensorSample>(1024)
        var total = 0
        var skipped = 0
        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty()) {
                    skipped++
                    return@forEachIndexed
                }
                if (index == 0 && (line.startsWith("timestamp") || line.startsWith("time"))) {
                    return@forEachIndexed
                }
                total++
                val parts = line.split(',')
                if (parts.size < 4) {
                    skipped++
                    return@forEachIndexed
                }
                val timestamp = parts[0].trim().toLongOrNull()
                val x = parts[1].trim().toFloatOrNull()
                val y = parts[2].trim().toFloatOrNull()
                val z = parts[3].trim().toFloatOrNull()
                if (timestamp == null || x == null || y == null || z == null) {
                    skipped++
                    return@forEachIndexed
                }
                out.add(GaitAnalyzer.SensorSample(timestamp, x, y, z))
            }
        }
        return CsvParseResult(out, total, out.size, skipped)
    }

    fun saveCsv(file: File, data: List<GaitAnalyzer.SensorSample>) {
        if (data.isEmpty()) return
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            out.write("timestamp_ns,x,y,z\n")
            data.forEach { sample ->
                out.write("${sample.timestamp},${sample.x},${sample.y},${sample.z}\n")
            }
        }
    }

    fun cropToOverlap(
        linear: List<GaitAnalyzer.SensorSample>,
        gyro: List<GaitAnalyzer.SensorSample>,
        gravity: List<GaitAnalyzer.SensorSample>,
    ): Triple<List<GaitAnalyzer.SensorSample>, List<GaitAnalyzer.SensorSample>, List<GaitAnalyzer.SensorSample>> {
        val starts = listOf(linear, gyro, gravity).filter { it.isNotEmpty() }.map { it.first().timestamp }
        val ends = listOf(linear, gyro, gravity).filter { it.isNotEmpty() }.map { it.last().timestamp }
        val start = starts.maxOrNull()
        val end = ends.minOrNull()
        if (start == null || end == null || end <= start) return Triple(linear, gyro, gravity)
        return Triple(
            linear.filter { it.timestamp in start..end },
            gyro.filter { it.timestamp in start..end },
            gravity.filter { it.timestamp in start..end },
        )
    }
}
