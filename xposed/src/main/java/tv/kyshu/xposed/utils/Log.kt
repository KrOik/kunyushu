/*
 * Copyright (C) 2025-2026 The KunYuShu Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ============================================================================
 * 统一日志系统 — 高维控制文件
 *
 * 所有日志的单一入口出口。内部按通道(Channel)分派到不同的 sink：
 *
 *   CAT    — XposedBridge / android.util.Log (实时控制台)
 *   FILE   — 文件持久化 (按日切割，自动轮转)
 *   RING   — 内存环形缓冲区 (Telemetry / Audit / UserOp)
 *   CRASH  — 崩溃标记文件
 *
 * 每个日志条目经过以下高维控制面过滤：
 *
 *   维度1: 日志级别 (INFO / DEBUG / WARN / ERROR)
 *   维度2: 通道掩码 (CAT | FILE | RING | CRASH)
 *   维度3: 标签前缀匹配 (支持通配符前缀)
 *   维度4: 运行环境 (xposed / app)
 *
 * 配置项在 [LogConfig] 中集中管理，支持运行时动态调整。
 * ============================================================================
 */
@file:Suppress("unused")

package tv.kyshu.xposed.utils

import android.content.Context
import android.os.Build
import android.util.Log as AndroidLog
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// ============================================================================
// 统一日志入口
// ============================================================================

object Log {

    // ---------- 通道常量 ----------

    object Channel {
        const val CAT = 1        // 控制台 (XposedBridge / Logcat)
        const val FILE = 2       // 文件持久化
        const val RING = 4       // 内存环形缓冲区
        const val CRASH = 8      // 崩溃标记文件
        const val ALL = CAT or FILE or RING or CRASH
    }

    // ---------- 级别常量 ----------

    object Level {
        const val INFO = 1
        const val DEBUG = 2
        const val WARN = 4
        const val ERROR = 8
        const val ALL = INFO or DEBUG or WARN or ERROR
    }

    // ---------- 公共 API：类别方法 ----------

    /** INFO 级别 */
    fun i(tag: String, msg: String) = write(tag, Level.INFO, msg)
    fun i(tag: String, msg: String, e: Throwable) = write(tag, Level.INFO, "$msg: ${e.stackTraceToString()}")

    /** DEBUG 级别 */
    fun d(tag: String, msg: String) = write(tag, Level.DEBUG, msg)
    fun d(tag: String, msg: String, e: Throwable) = write(tag, Level.DEBUG, "$msg: ${e.stackTraceToString()}")

    /** WARN 级别 */
    fun w(tag: String, msg: String) = write(tag, Level.WARN, msg)
    fun w(tag: String, msg: String, e: Throwable) = write(tag, Level.WARN, "$msg: ${e.stackTraceToString()}")

    /** ERROR 级别 */
    fun e(tag: String, msg: String) = write(tag, Level.ERROR, msg)
    fun e(tag: String, msg: String, e: Throwable) = write(tag, Level.ERROR, "$msg: ${e.stackTraceToString()}")

    /** 限速 DEBUG — 热路径用，相同 key 在 intervalMs 内只输出一次 */
    fun rl(tag: String, key: String, msg: String, intervalMs: Long = 5000) {
        if (!LogConfig.isAllowed(tag, Level.DEBUG, Channel.CAT)) return
        val now = System.currentTimeMillis()
        val last = rateLimitTimestamps[key]
        if (last != null && now - last < intervalMs) return
        if (rateLimitTimestamps.size >= 128) rateLimitTimestamps.clear()
        rateLimitTimestamps[key] = now
        write(tag, Level.DEBUG, "[RL] $msg")
    }

    // ---------- 领域专用 API ----------

    /** Telemetry: 结构化遥测事件 (hook 生命周期、健康检查) */
    fun telemetry(source: String, event: String, detail: String, extra: Map<String, Any?> = emptyMap()) {
        if (!LogConfig.isAllowed("TELEM", Level.INFO, Channel.RING)) return
        val ev = TelemetryEvent(
            timestampMs = System.currentTimeMillis(),
            bootHash = bootHash,
            stepId = source,
            eventType = event,
            detail = detail,
            extra = extra,
        )
        telemetryLock.withLock {
            telemetryBuf.addLast(ev)
            while (telemetryBuf.size > 256) telemetryBuf.removeFirst()
        }
        if (event in persistTypes) {
            telemetryFile?.appendText(ev.toJson() + "\n")
        }
        write("TELEM", Level.INFO, "[$event] $source: $detail")
    }

    /** Audit: mock 决策审计 (注入/跳过/配置变更) */
    fun audit(category: String, tag: String, message: String) {
        if (!LogConfig.isAllowed("AUDIT", Level.INFO, Channel.RING)) return
        val ev = AuditEvent(seq.incrementAndGet(), System.currentTimeMillis(), category, tag, message)
        auditBuf.add(ev)
        while (auditBuf.size > 256) auditBuf.poll()
        write("AUDIT", Level.INFO, "[$category] [$tag] $message")
    }

    /** User: 用户操作日志 (UI 交互记录) */
    fun user(source: String, action: String, detail: String = "") {
        if (!LogConfig.isAllowed("USER", Level.INFO, Channel.RING)) return
        val ev = UserEvent(seq.incrementAndGet(), System.currentTimeMillis(), source, action, detail)
        userBuf.add(ev)
        while (userBuf.size > 512) userBuf.poll()
        AndroidLog.d("UserOp", "[$source] $action: $detail")
    }

    /** Crash: 崩溃标记 */
    fun crash(scope: String, reason: String, error: Throwable? = null) {
        if (!LogConfig.isAllowed("CRASH", Level.ERROR, Channel.CRASH)) return
        val extra = JSONObject().apply {
            put("error", error?.javaClass?.simpleName ?: "unknown")
            put("message", error?.message ?: "")
        }
        crashAppend("CRASH:$scope:$reason:${resolveBootHashShort()}:${System.currentTimeMillis()}:$extra")
        write("CRASH", Level.ERROR, "[$scope] $reason")
    }

    /** Crash: 安全门跳过记录 */
    fun skip(scope: String, gate: String) {
        if (!LogConfig.isAllowed("CRASH", Level.WARN, Channel.CRASH)) return
        crashAppend("SKIP:$scope:$gate:${resolveBootHashShort()}:${System.currentTimeMillis()}")
        write("CRASH", Level.WARN, "[$scope] gate skip: $gate")
    }

    // ---------- 初始化 ----------

    fun init(context: Context?) {
        FileSink.setContext(context)
        TelemetrySink.setContext(context)
        CrashSink.setContext(context)
        LogConfig.setContext(context)
    }

    // ---------- 导出 ----------

    fun dumpTelemetry(lastN: Int = 256): String {
        val limit = lastN.coerceAtMost(256)
        return telemetryLock.withLock {
            val current = telemetryEvents().takeLast(limit).map { it.toJson() }
            val previous = previousTelemetryLines
            (previous + current).joinToString("\n")
        }
    }

    fun dumpAudit(): String {
        val list = auditBuf.toList()
        if (list.isEmpty()) return "[Log.Audit] 无审计记录"
        return list.joinToString("\n") { it.toString() }
    }

    fun dumpAudit(category: String): String {
        val list = auditBuf.toList().filter { it.category == category }
        if (list.isEmpty()) return "[Log.Audit] 无 $category 审计记录"
        return list.joinToString("\n") { it.toString() }
    }

    fun dumpUser(lastN: Int = 50): String {
        val list = userBuf.toList().takeLast(lastN.coerceAtMost(512))
        if (list.isEmpty()) return "[Log.User] 暂无操作记录"
        return list.joinToString("\n") { it.toString() }
    }

    fun dumpCrash(): String = crashRead()

    /** 全量导出 (Telemetry + Audit + User + Crash 合并) */
    fun exportAll(): String = buildString {
        appendLine("=== Telemetry ===")
        appendLine(dumpTelemetry())
        appendLine()
        appendLine("=== Audit ===")
        appendLine(dumpAudit())
        appendLine()
        appendLine("=== User ===")
        appendLine(dumpUser())
        appendLine()
        appendLine("=== Crash ===")
        appendLine(dumpCrash())
    }

    // ---------- 快捷便利方法 (兼容旧 API 命名) ----------

    /** 开关用户操作 */
    fun switchOp(source: String, label: String, value: Boolean) {
        user(source, "切换 $label", if (value) "开" else "关")
    }

    /** 对话框确认 */
    fun dialogOp(source: String, label: String, value: String) {
        user(source, "设置 $label", value)
    }

    /** 按钮点击 */
    fun buttonOp(source: String, action: String) {
        user(source, "点击", action)
    }

    // ====================================================================
    // 内部实现
    // ====================================================================

    // ---- 核心写方法 ----

    private fun write(tag: String, level: Int, msg: String) {
        val channels = LogConfig.resolveChannels(tag, level)
        if (channels == 0) return

        if (channels and Channel.CAT != 0) {
            CatSink.write(tag, level, msg)
        }
        if (channels and Channel.FILE != 0) {
            FileSink.write(tag, level, msg)
        }
    }

    // ---- 限速缓存 ----

    private val rateLimitTimestamps = ConcurrentHashMap<String, Long>(128)

    // ---- 数据类 ----

    private data class TelemetryEvent(
        val timestampMs: Long,
        val bootHash: String,
        val stepId: String,
        val eventType: String,
        val detail: String,
        val extra: Map<String, Any?> = emptyMap(),
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("t", timestampMs)
            json.put("b", bootHash)
            json.put("s", stepId)
            json.put("e", eventType)
            json.put("d", detail)
            val extraJson = JSONObject()
            extra.forEach { (key, value) -> extraJson.put(key, value ?: JSONObject.NULL) }
            json.put("x", extraJson)
            return json.toString()
        }
    }

    private data class AuditEvent(
        val seq: Int,
        val ts: Long,
        val category: String,
        val tag: String,
        val message: String,
    ) {
        override fun toString(): String = "[#$seq ${ts / 1000}.${String.format("%03d", ts % 1000)}] [$category] [$tag] $message"
    }

    data class UserEvent(
        val seq: Int,
        val timestampMs: Long,
        val source: String,
        val action: String,
        val detail: String = "",
    ) {
        override fun toString(): String = "[#$seq $source] $action${if (detail.isNotBlank()) ": $detail" else ""}"
    }

    // ---- Telemetry ring buffer ----

    private val telemetryLock = ReentrantLock()
    private val telemetryBuf = ArrayDeque<TelemetryEvent>(256)
    private val bootHash by lazy { resolveBootHash() }
    private val persistTypes = setOf("HOOK_INSTALL", "EXEC_DONE", "CRASH_MARKER", "GATE_SKIP", "EXEC_FAIL", "HOOK_FAIL")
    private var telemetryFile: File? = null
    private var previousTelemetryLines: List<String> = emptyList()



    private fun telemetryEvents(): List<TelemetryEvent> = telemetryLock.withLock { telemetryBuf.toList() }

    // ---- Telemetry sink ----

    private object TelemetrySink {
        fun setContext(context: Context?) {
            val cacheDir = context?.cacheDir ?: return
            telemetryLock.withLock {
                if (!cacheDir.exists()) cacheDir.mkdirs()
                telemetryFile = File(cacheDir, ".portal_telemetry_$bootHash.jsonl")
                previousTelemetryLines = loadPreviousBootTelemetryLocked(cacheDir)
                cleanupLocked(cacheDir)
            }
        }

        private fun loadPreviousBootTelemetryLocked(cacheDir: File): List<String> {
            val previous = cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith(".portal_telemetry_") &&
                    file.name.endsWith(".jsonl") && !file.name.contains(bootHash)
            }?.maxByOrNull { it.lastModified() } ?: return emptyList()

            val marker = TelemetryEvent(
                timestampMs = System.currentTimeMillis(),
                bootHash = bootHash,
                stepId = "LIFECYCLE",
                eventType = "CRASH_MARKER",
                detail = "PREVIOUS_BOOT_TELEMETRY_LOADED",
                extra = mapOf("source_file" to previous.name),
            ).toJson()
            return listOf(marker) + previous.readLines().takeLast(256)
        }

        private fun cleanupLocked(cacheDir: File) {
            val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith(".portal_telemetry_") && file.lastModified() < cutoff
            }?.forEach { it.delete() }
        }

        private fun resolveBootHash(): String {
            val bootId = runCatching {
                File("/proc/sys/kernel/random/boot_id").takeIf { it.isFile }?.readText()?.trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "elapsed-${android.os.SystemClock.elapsedRealtime()}"
            return hashShort(bootId)
        }

        private fun hashShort(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .take(4).joinToString("") { "%02x".format(it) }
    }

    // ---- Cat sink (XposedBridge / Logcat) ----

    private object CatSink {
        private val levelLabels = mapOf(
            Level.INFO to "",
            Level.DEBUG to "[DEBUG]",
            Level.WARN to "[WARN]",
            Level.ERROR to "[ERROR]",
        )

        fun write(tag: String, level: Int, msg: String) {
            val label = levelLabels[level] ?: ""
            val line = "[$tag]$label $msg"

            // XposedBridge.log 只在 xposed 环境中可用
            try {
                XposedBridge.log(line)
            } catch (_: NoClassDefFoundError) {
                // 回退到 android.util.Log
                val alogLevel = when (level) {
                    Level.ERROR -> AndroidLog.ERROR
                    Level.WARN -> AndroidLog.WARN
                    Level.DEBUG -> AndroidLog.DEBUG
                    else -> AndroidLog.INFO
                }
                AndroidLog.println(alogLevel, tag, msg)
            }
        }
    }

    // ---- File sink (日切割文件持久化) ----

    private object FileSink {
        private var logDir: File? = null
        private var enabled = false
        private const val MAX_FILE_SIZE = 512L * 1024L
        private const val MAX_FILES = 28
        private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        fun setContext(context: Context?) {
            logDir = if (context != null) {
                context.filesDir.resolve("logs").also { it.mkdirs() }
            } else null
            enabled = logDir?.exists() == true
        }

        private val levelLabels = mapOf(
            Level.INFO to "INFO",
            Level.DEBUG to "DEBUG",
            Level.WARN to "WARN",
            Level.ERROR to "ERROR",
        )

        fun write(tag: String, level: Int, msg: String) {
            if (!enabled) return
            val dir = logDir ?: return
            val today = LocalDate.now().format(dateFormat)
            val file = File(dir, "portal_$today.log")
            if (file.length() > MAX_FILE_SIZE) {
                val rotated = File(dir, "portal_${today}_${System.currentTimeMillis()}.log")
                file.renameTo(rotated)
                cleanup(dir)
            }
            val label = levelLabels[level] ?: "INFO"
            val ts = LocalDate.now().atTime(LocalTime.now()).format(tsFormat)
            file.appendText("$ts [$label] [$tag] $msg\n")
        }

        private fun cleanup(dir: File) {
            val files = dir.listFiles { it.name.startsWith("portal_") }
                ?.sortedByDescending { it.lastModified() }?.toMutableList() ?: return
            while (files.size > MAX_FILES) {
                files.removeLastOrNull()?.delete()
            }
        }
    }

    // ---- Crash sink ----

    // ---- Crash sink ----

    private object CrashSink {
        val lock = java.util.concurrent.locks.ReentrantLock()
        var crashFile: java.io.File? = null

        fun setContext(context: Context?) {
            val cacheDir = context?.cacheDir ?: return
            lock.withLock {
                if (!cacheDir.exists()) cacheDir.mkdirs()
                crashFile = java.io.File(cacheDir, ".portal_crash_${resolveBootHashShort()}.log")
                crashFile?.apply {
                    if (!exists()) createNewFile()
                    setReadable(true, true)
                    setWritable(true, true)
                    setExecutable(false, false)
                }
                cleanupLocked(cacheDir)
            }
        }

        private fun cleanupLocked(cacheDir: java.io.File) {
            val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith(".portal_crash_") && file.lastModified() < cutoff
            }?.forEach { it.delete() }
        }
    }

    private fun crashAppend(line: String) {
        CrashSink.lock.withLock {
            CrashSink.crashFile?.appendText(line + "\n")
        }
    }

    private fun crashRead(): String {
        return CrashSink.lock.withLock {
            try {
                CrashSink.crashFile?.takeIf { it.exists() && it.length() > 0L }?.readText()?.trim() ?: ""
            } catch (_: Exception) { "" }
        }
    }
    // ---- 审计 & 用户环形缓冲区 ----

    private val seq = AtomicInteger(0)
    private val auditBuf = ConcurrentLinkedQueue<AuditEvent>()
    private val userBuf = ConcurrentLinkedQueue<UserEvent>()

    // ---- bootHash 代理 ----

    private fun resolveBootHash(): String = TelemetrySink.run {
        val bootId = runCatching {
            File("/proc/sys/kernel/random/boot_id").takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "elapsed-${android.os.SystemClock.elapsedRealtime()}"
        return@run MessageDigest.getInstance("SHA-256").digest(bootId.toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
    }

    private fun resolveBootHashShort(): String {
        val fp = android.os.Build.FINGERPRINT.orEmpty()
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(fp.toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
    }

    // ---- 测试用 ----

    fun clearForTest() {
        rateLimitTimestamps.clear()
        telemetryLock.withLock { telemetryBuf.clear() }
        auditBuf.clear()
        userBuf.clear()
    }
}

// ============================================================================
// 高维日志配置
// ============================================================================

/**
 * 高维日志配置 — 控制 [Log] 的每个标签前缀在不同维度的行为。
 *
 * ## 维度
 *
 * 1. **标签前缀匹配** — 按 tag 的前缀分组，支持 `*` 匹配任意。
 * 2. **级别掩码** — 每条规则指定允许的级别 (INFO|DEBUG|WARN|ERROR)。
 * 3. **通道掩码** — 每条规则指定输出的通道 (CAT|FILE|RING|CRASH)。
 * 4. **环境** — 区分 xposed / app 运行环境。
 *
 * ## 默认规则
 *
 * - `*` (所有标签): CAT + FILE, INFO|WARN|ERROR
 * - `USER`: RING only, INFO
 * - `AUDIT`: RING only, INFO  
 * - `TELEM`: RING only, INFO
 * - `CRASH`: CRASH only, ERROR|WARN
 * - `DEBUG` 级别默认只在 TAG 以 `DEV_` 或 `TEST_` 开头时开启
 */
object LogConfig {

    /** 通道掩码快捷常量 (等价于 Log.Channel.*) */
    const val CAT = 1
    const val FILE = 2
    const val RING = 4
    const val CRASH = 8

    /** 级别快捷常量 (等价于 Log.Level.*) */
    const val INFO = 1
    const val DEBUG = 2
    const val WARN = 4
    const val ERROR = 8

    /** 单条路由规则 */
    data class Route(
        val tagPrefix: String,
        val channels: Int,
        val levels: Int,
    )

    // 路由表：按 tag 前缀匹配，第一条命中即停止
    private val routes = mutableListOf(
        Route("",    channels = CAT or FILE, levels = INFO or WARN or ERROR),   // 兜底
        Route("*",   channels = CAT or FILE, levels = INFO or WARN or ERROR),
        Route("DEV_", channels = CAT or FILE, levels = INFO or DEBUG or WARN or ERROR),
        Route("TEST_", channels = CAT or FILE, levels = INFO or DEBUG or WARN or ERROR),
        Route("USER", channels = RING, levels = INFO),
        Route("AUDIT", channels = RING, levels = INFO),
        Route("TELEM", channels = RING, levels = INFO),
        Route("CRASH", channels = CRASH, levels = ERROR or WARN),
    )

    /** 运行时动态添加路由 */
    fun addRoute(prefix: String, channels: Int, levels: Int) {
        synchronized(routes) {
            routes.add(0, Route(prefix, channels, levels))
        }
    }

    /** 移除路由 */
    fun removeRoute(prefix: String) {
        synchronized(routes) {
            routes.removeAll { it.tagPrefix == prefix }
        }
    }

    /** 检查某 tag+level 是否允许输出到某 channel */
    fun isAllowed(tag: String, level: Int, channel: Int): Boolean {
        return (resolveChannels(tag, level) and channel) != 0
    }

    /** 解析 tag+level 到通道掩码 */
    fun resolveChannels(tag: String, level: Int): Int {
        synchronized(routes) {
            for (route in routes) {
                if (route.tagPrefix == "*") return route.channels.takeIf { it and level != 0 } ?: 0
                if (tag.startsWith(route.tagPrefix)) {
                    if (route.levels and level == 0) return 0
                    return route.channels
                }
            }
        }
        return CAT or FILE  // 默认
    }

    // ---------- 持久化 ----------

    private var configFile: File? = null

    fun setContext(context: Context?) {
        configFile = context?.filesDir?.resolve("log_config.json")
        load()
    }

    fun load() {
        val file = configFile ?: return
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("routes") ?: return
            synchronized(routes) {
                routes.clear()
                routes.add(Route("", CAT or FILE, INFO or WARN or ERROR))
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    routes.add(Route(
                        tagPrefix = obj.getString("prefix"),
                        channels = obj.getInt("channels"),
                        levels = obj.getInt("levels"),
                    ))
                }
            }
        } catch (_: Exception) {}
    }

    fun save() {
        val file = configFile ?: return
        synchronized(routes) {
            val arr = org.json.JSONArray()
            for (route in routes) {
                if (route.tagPrefix.isEmpty()) continue
                arr.put(JSONObject().apply {
                    put("prefix", route.tagPrefix)
                    put("channels", route.channels)
                    put("levels", route.levels)
                })
            }
            file.writeText(JSONObject().apply { put("routes", arr) }.toString(2))
        }
    }

    /** 当前路由表快照 (调试用) */
    fun dumpRoutes(): String = synchronized(routes) {
        routes.joinToString("\n") { "  [${it.tagPrefix}] ch=${it.channels} lv=${it.levels}" }
    }
}
