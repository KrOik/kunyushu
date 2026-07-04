/*
 * Copyright (C) 2025 The KunYuShu Project
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
package tv.kyshu.xposed.config

import java.util.concurrent.locks.ReentrantLock

/**
 * 全局控制状态 — 定位 mock 总开关、崩溃保护、调试日志、root 模式等全局标志。
 */
class MockState {
    // ==================== 控制开关 ====================

    /**
     * 模拟定位服务开关
     */
    @Volatile
    var enable = false

    /**
     * 崩溃保护标志 — 当 enterSilentMode 触发后设为 true，阻止 reload() 覆盖 enable=false。
     * 只能通过用户显式 start 命令恢复。
     */
    @Volatile
    var forceDisable = false

    /**
     * 是否允许打印日志
     */
    @Volatile
    var enableLog = false

    /**
     * 是否允许打印调试日志
     */
    @Volatile
    var enableDebugLog = false

    @Volatile
    var rootAvailable = false

    @Volatile
    var rootMode = false

    // ==================== Reload 基础设施 ====================

    @Volatile
    var lastReloadTime = 0L

    val reloadLock = ReentrantLock()

    // ==================== 子对象专属更新 ====================

    /**
     * 从配置 map 更新 [MockState] 专属字段。
     * 不包含其他子对象的字段。
     */
    internal fun updateFromMap(map: Map<String, Any?>) {
        // forceDisable 保护：崩溃静默模式激活时，reload 不得重新启用 mock
        if (!forceDisable) {
            enable = map.getAnyVal(enable, "enable")
        }
        enableDebugLog = map.getAnyVal(enableDebugLog, "enableDebugLog", "enable_debug_log", "debug")
        enableLog = map.getAnyVal(enableLog, "enableLog", "enable_log")
        rootAvailable = map.getAnyVal(rootAvailable, "rootAvailable", "root_available")
        rootMode = map.getAnyVal(rootMode, "rootMode", "root_mode")
    }
}
