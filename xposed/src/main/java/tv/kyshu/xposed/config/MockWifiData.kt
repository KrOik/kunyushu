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
 * Mock WiFi 扫描结果数据类。
 *
 * 原为 [com.kunyushu.xposed.utils.FakeLoc] 内部类，现迁移至独立文件。
 * 外部引用仍可通过 [FakeLoc.MockWifiData] 兼容（类型别名保留），
 * 建议新代码直接导入 [MockWifiData]。
 */
data class MockWifiData(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val channelWidth: Int,
    val capabilities: String,
    val standard: Int
)
