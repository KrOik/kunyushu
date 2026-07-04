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
 * 步态模拟参数 — 频率、振幅缩放、JSON 步态曲线配置。
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

    // ==================== 子对象专属更新 ====================

    /**
     * 从配置 map 更新 [GaitData] 专属字段。
     */
    internal fun updateFromMap(map: Map<String, Any?>) {
        gaitFreq = map.getAnyVal(gaitFreq, "gaitFreq", "gait_freq")
        gaitAmpScale = map.getAnyVal(gaitAmpScale, "gaitAmpScale", "gait_amp_scale")
        gaitParamsJson = map.getAnyVal(gaitParamsJson, "gaitParamsJson", "gait_params_json")

        if (gaitFreq <= 0f) gaitFreq = 2.8f
        if (gaitAmpScale <= 0f) gaitAmpScale = 1.0f
    }
}
