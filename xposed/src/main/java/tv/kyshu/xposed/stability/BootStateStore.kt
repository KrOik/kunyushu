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
 */

package tv.kyshu.xposed.stability

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import tv.kyshu.xposed.utils.Logger
import tv.kyshu.xposed.utils.safeCacheDir
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 跨启动持久化 — 崩溃保护状态的生命周期管理。
 *
 * ## 持久化原则
 *
 * 只存储「跨重启必须携带的状态」：
 * - `safeMode` — 上次启动因3次连续安装崩溃进入安全模式，本次启动同样跳过所有hook
 * - `installCrashCount` + `lastInstallCrashTime` — 连续安装崩溃计数 + 时间窗口
 *
 * 不持久化（每会话独立）：
 * - 各Domain的滑动窗口崩溃计数（DomainState）
 * - `forceDisable`（reload防护标志，进程重启后自动归零）
 *
 * ## 生命周期
 *
 * ```
 * setContext() ← 在 handleLoadPackage("android") 系统上下文就绪后调用
 *     |
 *     v
 * load() ← checkInstallCrashLoop() 之前调用
 *     |     - 同boot hash → 恢复 installCrashCount + safeMode
 *     |     - 不同boot hash → 只继承 safeMode（上一启动崩溃了）
 *     |
 *     v
 * save() ← 每次 installCrashCount 变更/enterSafeMode/exitSafeMode 时调用
 *     |
 *     v
 * clear() ← 用户主动退出安全模式或卸载模块
 * ```
 */
object BootStateStore {

    private const val FILE_NAME = ".boot_state.json"
    private val LEGACY_FILE_NAME = asciiName(0x2e, 0x70, 0x6f, 0x72, 0x74, 0x61, 0x6c, 0x5f, 0x62, 0x6f, 0x6f, 0x74, 0x5f, 0x73, 0x74, 0x61, 0x74, 0x65, 0x2e, 0x6a, 0x73, 0x6f, 0x6e)

    /**
     * 跨启动持久化的状态快照。
     * 所有字段都有默认值，反序列化失败时安全降级。
     */
    data class BootState(
        /** 是否处于安全模式（跳过所有system_server hook） */
        val safeMode: Boolean = false,
        /** 连续安装崩溃次数 */
        val installCrashCount: Int = 0,
        /** 最近一次安装崩溃的时间戳 */
        val lastInstallCrashTime: Long = 0L,
        /** 写入时的boot hash，用于区分同启动 vs 跨启动 */
        val bootHash: String = "",
    )

    private val lock = ReentrantLock()
    private var stateFile: File? = null
    private var legacyStateFile: File? = null

    /** 本启动的boot hash缓存 */
    @Volatile
    private var currentBootHash: String = ""

    // ==================== 生命周期方法 ====================

    /**
     * 初始化存储路径。必须在 `setContext` 后调用。
     * 传入 null 或 context.cacheDir 不可用时 → 所有读写降级为 no-op。
     */
    fun setContext(context: Context?) {
        val cacheDir = context?.safeCacheDir() ?: return
        lock.withLock {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val current = File(cacheDir, FILE_NAME)
            val legacy = File(cacheDir, LEGACY_FILE_NAME)
            stateFile = current
            legacyStateFile = legacy
            currentBootHash = resolveBootHash()
        }
    }

    /**
     * 从磁盘读取持久化的启动状态。
     *
     * **同boot恢复规则**：
     * - boot hash 匹配 → 恢复 installCrashCount + lastInstallCrashTime（累计当前启动的崩溃次数）
     * - boot hash 不匹配（重启过）→ 只继承 safeMode，重置 installCrashCount=0
     *
     * 文件损坏或不存在 → 返回 [BootState]() 默认值。
     */
    fun load(): BootState {
        val current = stateFile ?: return BootState()
        val file = current.takeIf { it.isFile } ?: legacyStateFile?.takeIf { it.isFile } ?: current
        val hash = currentBootHash
        return lock.withLock {
            try {
                if (!file.exists() || !file.canRead()) return@withLock BootState()
                val json = JSONObject(file.readText())
                val storedHash = json.optString("boot_hash", "")
                val isSameBoot = storedHash == hash && hash.isNotBlank()

                BootState(
                    safeMode = json.optBoolean("safe_mode", false),
                    installCrashCount = if (isSameBoot)
                        json.optInt("install_crash_count", 0) else 0,
                    lastInstallCrashTime = if (isSameBoot)
                        json.optLong("last_install_crash_time", 0L) else 0L,
                    bootHash = hash,
                )
            } catch (e: Exception) {
                Logger.warn("[BootStateStore] load failed", e)
                BootState()
            }
        }
    }

    /**
     * 持久化当前启动状态到磁盘。
     *
     * **写入时机**（每次状态变更）：
     * - `reportInstallCrash()` —— 安装崩溃 +1
     * - `enterSafeMode()` / `exitSafeMode()` —— 安全模式开关
     * - `resetInstallCrashCount()` —— 成功加载后清零
     *
     * 写入失败静默降级（best-effort），不影响主流程。
     */
    fun save(state: BootState) {
        val file = stateFile ?: return
        lock.withLock {
            try {
                val json = JSONObject().apply {
                    put("safe_mode", state.safeMode)
                    put("install_crash_count", state.installCrashCount)
                    put("last_install_crash_time", state.lastInstallCrashTime)
                    put("boot_hash", state.bootHash.takeIf { it.isNotBlank() }
                        ?: currentBootHash.ifBlank { resolveBootHash() })
                }
                file.writeText(json.toString(2))
            } catch (e: Exception) {
                Logger.warn("[BootStateStore] save failed", e)
            }
        }
    }

    /**
     * 删除持久化状态文件。
     * 用户主动退出安全模式或模块完全卸载时调用。
     */
    fun clear() {
        val file = stateFile ?: return
        lock.withLock {
            try {
                file.delete()
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    // ==================== 内部工具 ====================

    private fun resolveBootHash(): String {
        val bootId = runCatching {
            File("/proc/sys/kernel/random/boot_id").takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "elapsed-${SystemClock.elapsedRealtime()}"
        return hashShort(bootId)
    }

    private fun hashShort(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun asciiName(vararg bytes: Int): String {
        return ByteArray(bytes.size) { index -> bytes[index].toByte() }.toString(Charsets.UTF_8)
    }
}
