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
 * 携带方式 — 手持摆臂 / 裤兜，Kuroko-Teleport carry mode 语义。
 * 决定姿态基线（pitch）与横摆频率（手持 f，裤兜 f/2 + 更小振幅）。
 */
enum class CarryMode {
    /** 手持摆臂：pitch ≈ 0.55 rad，横摆频率 = 步频 f。 */
    HAND_SWING,
    /** 裤兜：pitch ≈ 1.38 rad，横摆频率 = 步频 f/2，振幅 ~0.4×。 */
    POCKET,
}

/**
 * 步态模拟参数 — 频率、振幅缩放、JSON 步态曲线配置、携带方式。
 */
class GaitData {
    // ==================== 步态参数 ====================

    /**
     * 步态基础频率 (Hz)，默认 2.8
     */
    @Volatile
    var gaitFreq = 2.8f

    /**
     * 步态振幅缩放倍数，默认 1.0
     */
    @Volatile
    var gaitAmpScale = 1.0f

    /**
     * 步态参数 JSON 字符串，定义自定义步态曲线
     */
    @Volatile
    var gaitParamsJson = ""

    /**
     * 携带方式：手持摆臂（默认）或裤兜。Kuroko SET_MODE <motion> <carry> 移植。
     */
    @Volatile
    var carryMode = CarryMode.HAND_SWING

    /**
     * 手持模式 pitch 基线 (rad)
     */
    @Volatile
    var handPitchRad = 0.55f

    /**
     * 裤兜模式 pitch 基线 (rad)
     */
    @Volatile
    var pocketPitchRad = 1.38f

    // ==================== 子对象专属更新 ====================

    /**
     * 从配置 map 更新 [GaitData] 专属字段。
     */
    internal fun updateFromMap(map: Map<String, Any?>) {
        gaitFreq = map.getAnyVal(gaitFreq, "gaitFreq", "gait_freq")
        gaitAmpScale = map.getAnyVal(gaitAmpScale, "gaitAmpScale", "gait_amp_scale")
        gaitParamsJson = map.getAnyVal(gaitParamsJson, "gaitParamsJson", "gait_params_json")
        when (map.getAnyVal("hand", "carryMode", "carry_mode")) {
            "pocket", "POCKET", "1" -> carryMode = CarryMode.POCKET
            "hand", "HAND_SWING", "0" -> carryMode = CarryMode.HAND_SWING
        }
        handPitchRad = map.getAnyVal(handPitchRad, "handPitchRad", "hand_pitch_rad")
        pocketPitchRad = map.getAnyVal(pocketPitchRad, "pocketPitchRad", "pocket_pitch_rad")

        if (gaitFreq <= 0f) gaitFreq = 2.8f
        if (gaitAmpScale <= 0f) gaitAmpScale = 1.0f
        if (handPitchRad <= 0f) handPitchRad = 0.55f
        if (pocketPitchRad <= 0f) pocketPitchRad = 1.38f
    }
}
