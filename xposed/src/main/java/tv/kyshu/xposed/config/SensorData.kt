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

/**
 * 传感器模拟 — 计步器、摇一摇、步行波形、Doze 传感器间隔等。
 */
class SensorData {
    // ==================== 传感器开关与参数 ====================

    /**
     * 传感器模拟总开关
     */
    @Volatile
    var hookSensor = false

    /**
     * Doze 模式下传感器采样间隔 (ms)
     */
    @Volatile
    var dozeSensorIntervalMs = 200L

    /**
     * 计步模拟开关
     */
    @Volatile
    var enableStepCountMock = false

    /**
     * 目标步数
     */
    @Volatile
    var stepCount = 0

    /**
     * 摇一摇模拟开关
     */
    @Volatile
    var enableShakeMock = false

    /**
     * 保持步行波形
     */
    @Volatile
    var keepWalkingWaveform = false

    /**
     * 是否模拟运动传感器（加速度计/陀螺仪/重力等）。
     * 与enableStepCountMock分离，允许只模拟步数而不篡改IMU。
     */
    @Volatile
    var enableMotionMock: Boolean = false

    // ==================== 子对象专属更新 ====================

    /**
     * 从配置 map 更新 [SensorData] 专属字段。
     */
    internal fun updateFromMap(map: Map<String, Any?>) {
        hookSensor = map.getAnyVal(hookSensor, "hookSensor", "hook_sensor")
        enableStepCountMock = map.getAnyVal(enableStepCountMock, "enableStepCountMock", "enable_step_count_mock")
        stepCount = map.getAnyVal(stepCount, "stepCount", "step_count")
        enableShakeMock = map.getAnyVal(enableShakeMock, "enableShakeMock", "enable_shake_mock")
        enableMotionMock = map.getAnyVal(enableMotionMock, "enableMotionMock", "enable_motion_mock")
        keepWalkingWaveform = map.getAnyVal(keepWalkingWaveform, "keepWalkingWaveform", "keep_walking_waveform")
        dozeSensorIntervalMs = map.getAnyVal(dozeSensorIntervalMs, "dozeSensorIntervalMs", "doze_sensor_interval_ms")

        if (stepCount < 0) stepCount = 0
        if (dozeSensorIntervalMs < 20L) dozeSensorIntervalMs = 20L
        if (enableStepCountMock || enableShakeMock || enableMotionMock) hookSensor = true
    }
}
