# Kuroko-Teleport 优势清单与移植决策

> 分析基线：Kuroko-Teleport @ `477f0df` (v0.6.3, versionCode 12)，本地克隆于临时目录。
> kyushu（Portal, `tv.kyshu`）基线：master @ `0062f6f` + 未提交工作树（已提交为 wip 同步点）。
> 决策标记：✅ 直接移植 / 🔧 适配移植 / ⏸ 暂不移植

## 0. 两个项目的根本架构差异（决策前提）

| | Kuroko-Teleport | kyushu (Portal) |
|---|---|---|
| Hook 层 | Zygisk(native PLT hook in system_server) + LSPosed(仅 `android`+`com.android.phone` scope) | LSPosed 多 scope（android / com.android.phone / fused 厂商进程）+ 自有 native (Dobby) |
| 数据通道 | root daemon (C++) → 16KB memfd Seqlock 共享内存 → system_server/com.android.phone | 控制 App → `sendExtraCommand` 虚拟 provider + HMAC 会话 + ConfigProvider 兜底 |
| 路由决策点 | 按 UID 在 system_server 侧分流（32 目标表） | 全局 mock 状态（FakeLoc 单例），无按 UID 策略 |
| 交付 | All-in-One KernelSU 模块（内嵌 APK） | 纯 APK（LSPosed 模块激活） |
| 运行基线 | Android 12+ / arm64-v8a / KernelSU + Zygisk Next + LSPosed | minSdk 26 / compileSdk 35 / arm64+x86_64 |

**关键事实**：kyushu 已具备零目标 App 注入特性——其 Xposed scope 只含系统进程 + 自身控制包，目标第三方 App 不在 scope 内。kyushu 的定位是"单机全域 mock"（mock 所有请求方），Kuroko 是"按 UID 白名单 mock"。这是产品哲学差异，不是缺失。

---

## 1. 架构层面

### 1.1 目标 App 零注入
- **Kuroko 实现**：`xposed/KurokoXposedEntry.kt`（硬拒绝非 android/com.android.phone 包）、`native/zygisk/module.cpp` `preAppSpecialize` 中 `DLCLOSE_MODULE_LIBRARY` 自卸载、scope 数组仅含两个系统包。
- **kyushu 现状**：✅ **已有等价实现**。`FakeLocation.kt` scope 即系统进程集；`TARGET_PACKAGES` 不含第三方 App；LSPosed scope 需人工保持不勾选目标 App（操作约束，非代码缺失）。
- **决策**：⏸ 暂不移植（已具备）。验证动作保留：真机确认目标 App `/proc/<pid>/maps` 干净。

### 1.2 按 UID 隔离的 system_server 侧分流（32 目标表 + 共享内存策略）
- **Kuroko 实现**：`common/include/kuroko_shm.h`（KurokoTarget{uid, policy_flags}, 32 上限）、`native/daemon/main.cpp` SET_TARGETS、`ShmReader.kt` hasPolicy(uid, flag)、`sensor_hook.cpp` hook_send_events 按 UID 门控。
- **kyushu 现状**：无按 UID 策略，mock 全局生效。
- **决策**：🔧 **适配移植**（第一期先做"按 UID 门控"最小版）。
  - 理由：Kuroko 的 memfd/daemon 通道依赖 root daemon + Zygisk companion，kyushu 无此基础设施（移植成本远超收益）；但"按 UID 决定是否 mock"的策略语义可以在 kyushu 现有通道内实现：在 system_server hook 侧用 `Binder.getCallingUid()` 对照一份 UID→策略表（经现有 HMAC 通道下发，替代 SET_TARGETS）。
  - 价值：同设备多 App 场景下，只对目标 App mock，其他 App（银行/地图等）拿到真实数据，大幅降低全局泄漏面。

### 1.3 Zygisk 纯 PLT/GOT hook libsensorservice（sensor 层零 Java 注入）
- **Kuroko 实现**：`native/zygisk/sensor_hook.cpp`（约 1600 行：ELF 解析、pltHookRegister、GOT 自愈 watchdog、多版本符号表 A12–17、BTI/PAC 安全）。
- **kyushu 现状**：已有 native sensor hook（`xposed/src/main/cpp/sensor_hook.cpp` + Dobby inline hook + Java 层 `SystemSensorManagerHook.kt` 的 dispatchSensorEvent hook）。
- **决策**：🔧 **适配移植（P0-4，用户指定）**。隐蔽性/对抗性关键路径（sensor 事件改写）改用 Kuroko 的 Zygisk PLT/GOT hook 路线：
  - P0-3 的 KernelSU 模块同时携带 Zygisk companion（`zygisk/arm64-v8a.so`），仅在 system_server（`preServerSpecialize`）激活，普通 App 进程 `DLCLOSE_MODULE_LIBRARY` 自卸载。
  - 移植 Kuroko 的 ELF/PLT 解析 + `pltHookRegister`/`pltHookCommit` + GOT 自愈 watchdog + 多版本符号表，目标 `libsensorservice.so` 的 SensorEventConnection 发送路径。
  - Dobby inline hook 从 sensor 路径退役（对抗面上 inline hook 的 trampoline 特征更易被 maps/扫描检测，且 BTI/PAC 下有崩溃风险）；Java 层 `SystemSensorManagerHook` 保留作为回退层（DomainGuard 熔断后降级），非对抗路径不受影响。
  - 依赖：Zygisk Next（远端真机已具备 1.5.3）。

### 1.4 1Hz 主动位置泵 + 注册级 GNSS/NMEA 拦截
- **Kuroko 实现**：`LocationHook.kt` hookListenerMultiplexer / hookGnssStatusProvider + PUMP 线程主动向注册者推送合成 LocationResult/GnssStatus/NMEA。
- **kyushu 现状**：已有注册级拦截（`LocationManagerHook.kt` ListenerTransport/BatchedLocationCallback、`GnssHook.kt`、`LocationNMEAHook.kt`），被动拉取（getLastLocation/getCurrentLocation）全覆盖；缺"主动泵"（对不触发回调的被动消费场景补推）。
- **决策**：🔧 适配移植（低优先级，列为 P2）。kyushu 的广播式 `broadcast_location` 已覆盖大多数场景；主动泵仅在"目标 App 注册后长期无系统位置更新"场景有价值，先观察真机表现再定。

### 1.5 root daemon + memfd SCM_RIGHTS 通道
- **决策**：⏸ 暂不移植。kyushu 的 sendExtraCommand+HMAC+ConfigProvider 通道已在远端真机验证可用（见 verification-report）；引入 root daemon 需要重构整个交付模型。

---

## 2. 轨迹与传感器

### 2.1 Catmull-Rom 平滑弯道轨迹 + 弯道减速
- **Kuroko 实现**：`native/daemon/sim_engine.cpp` catmull_rom（样条稠密化 ~3m/样本）、corner factor（`1 - clamp(Δheading/120°, 0, 0.30)` 弯道减速）、速度惯性平滑 τ=1.2s。
- **kyushu 现状**：`MockServiceViewModel.initRocker` 路线循环是原始航点直线行进（Geodesic.WGS84.Inverse + 固定步长），**无任何平滑**。急转弯处航向瞬变，是轨迹检测的显著特征。
- **决策**：🔧 **适配移植（P0）**。样条逻辑与运行时无关，直接在 app 侧 `MockServiceViewModel`/新 helper 中实现 Kotlin 版 Catmull-Rom 稠密化 + 弯道因子 + 速度惯性平滑，插入现有路线 tick 循环。这是 Kuroko 对 kyushu 收益最大、风险最低的单项移植。

### 2.2 循环/里程/圈数自动收尾
- **Kuroko 实现**：`sim_engine.cpp` loop / max_dist_m / max_laps，达标后减速停车，引擎驻留终点。
- **kyushu 现状**：路线播完即 routeStage=0 无限循环重启，无里程/圈数目标、无收尾减速。
- **决策**：🔧 **适配移植（P0）**。在 `HistoricalRoute` 加 `loopMode / targetDistanceMeters / targetLaps` 字段（JSON 兼容默认值），播放循环中累计里程，达标后减速至 0 并暂停 mock（而不是瞬间归零或无限循环）。

### 2.3 双时间尺度 OU 配速波动 + 相关 GPS 噪声
- **Kuroko 实现**：`sim_engine.cpp` OU(τ=18s,σ=0.75r) + OU(τ=4s,σ=0.35r) 配速；GPS ENU OU 有色噪声（垂直 0.65×）+ accuracy 呼吸 (3–18m, τ=10s)。
- **kyushu 现状**：速度抖动是 tick 级随机（distancePerTick / 0.85 + minSpeed/maxSpeed 振幅），LocationFactory 有 accuracy 抖动，但无时间相关性（白噪声特征，可被 FFT/序列检验识别）。
- **决策**：🔧 **适配移植（P1）**。Kotlin 实现 OU 过程（指数衰减 + 高斯激励，两个时间尺度），叠加到路线 tick 速度；accuracy 呼吸用 OU 替代随机抖动。放 app 侧 `MockServiceViewModel` 速度计算处 + xposed `CorrelatedLocationModel`（已有该类，扩展）。

### 2.4 手持/裤兜姿态切换（同源 5 通道 IMU）
- **Kuroko 实现**：`sim_engine.cpp` update_biomechanics_locked：步长 L(v)、步频 f(v)±4.5% 抖动、f/2 横摆、pitch 0.55rad(手)/1.38rad(兜)、四元数→解析陀螺仪、地磁 ENU、气压耦合海拔；SET_MODE carry 切换。
- **kyushu 现状**：`SystemSensorManagerHook.getMockMotionValues` 正弦波形 + `GaitSimulator.kt`（Fourier 谐波 + 脉冲，ported from mockWalk），但**无姿态模式概念**（无手持/裤兜区分、无 f/2 横摆、无四元数↔陀螺一致性）。
- **决策**：🔧 **适配移植（P1）**。给 `GaitData`/`SensorData` 增加 carry 模式（HAND/POCKET），GaitSimulator 输出按模式切换 pitch 基线与横摆频率（f/2），陀螺仪由姿态四元数差分导出保证一致性。UI 下拉切换。

### 2.5 室内静止 IMU+计步同源同步（HW 计步抑制 + 40Hz 注入）
- **Kuroko 实现**：sensor_hook.cpp 按 UID 丢弃真实硬件 17/18/19 事件 + 40Hz 注入线程，步数严格单调。
- **kyushu 现状**：✅ 大体已有等价实现——Java 层 hook 覆盖 TYPE_STEP_COUNTER/DETECTOR，commit 0062f6f 加了主动推送线程（ensureStepEventPusher），57e051f 同步步频回放。差异：kyushu 依赖真实事件驱动 + 推送，Kuroko 是纯抑制+注入（更彻底但需要 native 层）。
- **决策**：⏸ 暂不移植（机制层面）；真机验证 kyushu 现有链路的步数单调性与 IMU 相位（见 verification-report），若发现真实事件穿透再评估。

### 2.6 计步/IMU 自检
- **决策**：🔧 适配移植（P2，视时间）。kyushu 有 BlackBoxProbe/ReadinessProbe 健康检查体系，可挂一个"传感器回读自检"项。非核心。

---

## 3. 防泄漏

### 3.1 按 UID 策略位（跨城严防 0x3F / 同城户外 0x2F）
- **Kuroko 实现**：`kuroko_shm.h` policy bits + `TargetAppSheet.kt` 两档 UI。
- **决策**：并入 1.2 的 UID 门控移植（同一份 UID→策略表增加 HIDE_WIFI/HIDE_CELL 位）。

### 3.2 Wi-Fi 隐藏粒度（APEX 双 classloader 处理）
- **Kuroko 实现**：`WifiConnectivityHook.kt` — APEX classloader 三重动态捕获 + getScanResults 空 ParceledListSlice（jarjar-renamed 类名）+ WifiInfo.makeCopy 脱敏 + ConnectivityService sanitize 强制 includeLocationInfo=false（同时覆盖同步查询与 NetworkCallback）。
- **kyushu 现状**：`WlanHook.kt` 替换为假 AP 列表（WifiScanResultFactory 构造对象）。**用户反馈：现有实现有崩溃/软重启风险**。
- **决策**：🔧 **适配移植（P0-5，用户指定）**。WiFi/基站/BLE 三条对抗面全部参考 Kuroko 成熟模式重写：
  - 核心哲学转变：从"构造复杂假对象"（依赖 ROM layout 假设，正是崩溃源）转为"按 UID 返回空/null/脱敏"的保守语义（不构造新对象，天然免疫 layout 变化）。
  - WiFi: 移植 APEX classloader 三重探测 + 空 ParceledListSlice + makeCopy 脱敏 + sanitize 钩子。
  - 基站: PhoneInterfaceManager/TelephonyRegistry 按 UID 空返回（kyushu 已有两进程 hook 结构，改返回语义即可）。
  - BLE: Kuroko 无实现（其 TODOLIST Phase 4），按同一保守模式加固 BluetoothHook 回调层。
  - 原 Factory 路线保留但默认禁用（feature flag），供"回放真实采集环境"模式使用（该模式构造的是真实采集数据，风险可控）。

### 3.3 双进程基站隐藏 + GNSS 原始测量抑制
- **kyushu 现状**：`TelephonyHook.kt` 已覆盖 PhoneInterfaceManager 全套 + TelephonyRegistry + binder transact 层；CellInfoFactory 支持 createFromProfiles。GNSS 原始测量（GnssMeasurementsEvent）kyushu 未明确 hook——这是真实缺口。
- **决策**：🔧 **适配移植（P1）**：a) TelephonyHook/WlanHook 并入 UID 门控；b) 补 `GnssMeasurementsEvent` 回调拦截（对 mock 域内调用返回"不支持测量"或合成测量），防观测级检测。Kuroko 的两进程结构 kyushu 已有（scope 含 com.android.phone）。

### 3.4 HyperOS 泄漏豁免
- **kyushu 现状**：已有 `MiuiLocationManagerHook` + `MiuiBlurLocationProviderHook`（处理 blur location），**缺 GpsProxyManager 冻结豁免**。
- **决策**：🔧 **适配移植（P1）**：参考 `HyperOsHook.kt` 的 `GpsProxyManager` hook 思路，在 kyushu miui 包内补对应豁免。Flyme 设备上 no-op，不影响。

---

## 4. 工程与交付

### 4.1 All-in-One KernelSU 模块（内嵌 APK 流式安装 + 延迟安装 + 数据保护卸载）
- **Kuroko 实现**：`module_template/customize.sh`（SHA-256 校验、`pm install -r -d -S` 流式）、`service.sh` 延迟安装、`uninstall.sh` 不删用户数据、`build_module.py` 打包校验。
- **kyushu 现状**：纯 APK 交付，无 KernelSU 模块。
- **决策**：🔧 **适配移植（P0，交付形态）**。为 kyushu 建 `module_template/`（customize.sh/service.sh/uninstall.sh/module.prop + build_module.py），内嵌 Portal APK。注意：kyushu 是 LSPosed 模块，模块安装 APK 后仍需 LSPosed 侧激活——service.sh 可通过 `lspctl`/LSPosed API 尝试自动启用模块（失败则提示用户手动）。这是交付方式增强，不改变运行时架构，brick 风险低。

### 4.2 boot-guard 防砖
- **Kuroko 实现**：`post-fs-data.sh` 递增计数、`service.sh` boot_completed 后清零、连续 2 次未完成启动 → 模块自禁用。
- **决策**：🔧 **直接移植（P0）**。该机制是纯 shell 逻辑（`module_template/post-fs-data.sh`/`service.sh`），与运行时无关，可原样移植（改路径 /data/adb/kyshu/）。kyushu 模块本身只装 APK 不动系统分区，风险本就低于 Kuroko（无 zygisk 注入），boot-guard 作为保险层直接复用。

### 4.3 四组件版本锁状态机
- **决策**：⏸ 暂不移植。该机制服务于 Kuroko 的 daemon/zygisk/lspd/apk 四组件分布式版本一致性；kyushu 单 APK 无此问题。kyushu 现有 fail-fast 配置校验已覆盖。

### 4.4 诊断中心 + 脱敏日志导出
- **kyushu 现状**：已有 FlightRecorder/CrashForensics/LogRedactor/MockAuditTrail + 日志导出命令。
- **决策**：⏸ 暂不移植（已有等价实现）。验证阶段直接用 kyushu 现有导出 + 远端 logcat。

### 4.5 其他（token TCP 控制面、i18n 审计、悬浮 HUD、无 Key 地图、更新检查）
- **决策**：⏸ 暂不移植。kyushu 均有等价物（HMAC sendExtraCommand 控制面、Compose UI、悬浮窗 OverlayUtils、百度地图）。

---

## 5. 移植执行清单（本次范围）

| # | 项目 | 级别 | 类型 | 主要落点 |
|---|---|---|---|---|
| P0-1 | Catmull-Rom 平滑 + 弯道减速 + 速度惯性 | 2.1 | 适配 | app: 新 `RouteSmoother.kt` + MockServiceViewModel 路线循环 |
| P0-2 | 循环/里程/圈数自动收尾 | 2.2 | 适配 | app: HistoricalRoute 字段 + 播放循环收尾逻辑 |
| P0-3 | KernelSU 模块交付 + boot-guard 防砖 | 4.1/4.2 | 直接+适配 | 新 `module_template/` + `build_module.py` |
| P0-4 | Zygisk PLT hook 替代 Dobby（sensor 对抗路径） | 1.3 | 适配（用户指定） | xposed/native: zygisk companion + PLT hook 移植 |
| P0-5 | WiFi/基站/BLE 重写为 Kuroko 保守模式 | 3.2/3.3 | 适配（用户指定） | xposed: WlanHook/TelephonyHook/BluetoothHook 语义改造 |
| P1-1 | OU 配速波动 + 相关 accuracy 呼吸 | 2.3 | 适配 | app: MockServiceViewModel；xposed: CorrelatedLocationModel |
| P1-2 | 手持/裤兜姿态切换 | 2.4 | 适配 | xposed: GaitData/SensorData/GaitSimulator + Settings UI |
| P1-3 | GNSS 原始测量抑制 | 3.3 | 适配 | xposed: GnssHook 增加 GnssMeasurementsEvent 拦截 |
| P1-4 | HyperOS GpsProxyManager 豁免 | 3.4 | 适配 | xposed: hooks/miui/ 新增 |
| P1-5 | 按 UID 门控（LOCATION/STEPS/IMU/HIDE_WIFI/HIDE_CELL） | 1.2/3.1 | 适配 | xposed: UID 策略表 + RemoteCommandHandler 下发 + 各 hook 门控 |
| P2 | 主动位置泵 / 传感器自检 | 1.4/2.6 | 适配 | 观察 P0/P1 真机表现后决定 |

**不移植**（理由已列）：Zygisk PLT sensor hook (1.3)、root daemon/memfd (1.5)、版本锁 (4.3)、诊断中心 (4.4)、已具备项 (1.1/2.5/3.2/4.5)。

**约束遵守**：所有改动不触碰 LSPosed scope；目标第三方 App 保持不勾选；不修改与移植无关的模块（nmea/system-api/bdmap/UI 主题等）。

## 6. 远端真机环境（验证用）

| 项 | 值 |
|---|---|
| 设备 | MEIZU 20 Pro (meizu20Pro, 391QJFD8FES2V) |
| 系统 | Flyme 12.5.0.0A / Android 15 (SDK 35) / arm64-v8a / 补丁 2025-11-05 |
| Root | KernelSU 3.3.0 (uapi 2)，临时 root (`su -c`) |
| Zygisk | Zygisk Next 1.5.3 (880) |
| LSPosed | v2.2.1 (7912) |
| 已装模块 | dex2oat_clean, hma_oss_zygisk, probezyg, zygisk_lsposed, zygisksu |
| Portal | tv.kyshu 已安装 |
| 构建机 | edaserver (RHEL 9.6, 20C/30G)，JDK 17，Android SDK（platforms 34/35/36/37.2，NDK 30，build-tools 36/37），/root/portal-build |
| 接入 | SSH `edazt` (ZeroTier 10.53.116.187)，adb 经远端 USB（隧道脚本 access/adb-remote.ps1） |
