# Portal × Kuroko-Teleport 移植真机验证报告

> 日期：2026-11-25
> 设备：Meizu 20 Pro（Android 15 / Flyme，arm64-v8a）
> Root：KernelSU 3.3.0 · Zygisk Next 1.5.3 · LSPosed v2.2.1 (7912)
> 构建机：RHEL 9.6 `edaserver`（ZeroTier），工作目录 `/root/portal-build`
> 代码通道：本地 D:\Portal → git push → github.com/KrOik/kunyushu `portal-sync` 分支 → 远端 pull（按用户要求，不走大流量直传）
> 安全约束全程遵守：临时 root（`su -c` 逐命令）、无硬重启、远端 adb 全程存活、未勾选任何目标三方 App。

---

## 1. 构建与安装

| 项 | 证据 |
|---|---|
| 版本 | v56（`1.0.8-5605030-20261125-0243`，56 commits）→ v57（`Portal-1.0.8-d3ff59f-20261125-0303-all`，57 commits） |
| 构建 | `:app:assembleAppRelease` + `:app:testAppDebugUnitTest` BUILD SUCCESSFUL（5m18s，187 tasks） |
| 单元测试 | **RouteSmootherTest 7/7 通过**（远端 JVM）：Catmull-Rom 稠密化、cumDist 单调性、90° 弯道 cornerFactor 下降（<0.95）、sampleAt 插值、里程收尾（50m 停车速度归零）、2 圈循环收尾、OU 配速上限钳制、haversine 精度 |
| 安装 | `adb install -r -d` → Success（INSTALL_FAILED_VERSION_DOWNGRADE 已通过 unshallow 修复 rev-count 解决） |
| 应用存活 | 启动后 pid 存活，0 FATAL exceptions |

关键提交：
- `d3ff59f` feat: drive route playback with RouteSmoother walker — 路线 tick 循环从逐航点测地推进改为驱动 Walker（OU 配速波动 + 弯道减速 + 惯性加速 + 里程/圈数收尾），完成 P0-1/P0-2 的运行时闭环。
- `ed16e69` fix: customize.sh APK discovery robustness for ksud install context。

## 2. system_server 软重启安全性（架构验证）

| 步骤 | 结果 |
|---|---|
| killall system_server（pid 2380 → 13843） | boot_completed=1，adb 存活（devices=2），0 FATAL |
| `stop; start` 框架重启（13843 → 19210） | boot_completed=1，adb 存活，0 FATAL |
| 软重启循环风险 | 无 — 三次不同 pid 均正常完成 boot，无 crash buffer 记录 |

结论：v56/v57 hook 代码（UID 门控、WiFi/基站/BLE 隐藏路径、MiuiGpsProxyHook、carry-mode 透传）已随 APK 安装，且未在 system_server 侧引发任何崩溃/软重启回归。

## 3. 零注入约束验证（用户红线）

- `modules_config.db`（/data/adb/lspd/config/）strings 确认 Portal 模块 scope **精确为 `android` + `com.android.phone`**（`tv.kyshuandroid` / `tv.kyshucom.android.phone`）。
- `com.probe.ta` 为设备上既有的独立 probe 模块（scope: com.android.settings / com.chunqiunativecheck），与 Portal 无关。
- 目标三方 App 未被勾选 ✅。

## 4. KernelSU 模块交付（P0-3）

| 项 | 证据 |
|---|---|
| 打包 | `build_module.py` → `dist/portal-ksu-module.zip` 41.7MB，ZIP_STORED + 全文件 SHA-256 校验通过（内嵌 APK sha256=f6cbca9e…） |
| 首次安装失败 | `ksud module install` 报 "portal.apk missing" — 根因：ksud 在自身解压完成前运行 customize.sh，`$MODDIR/portal.apk` 尚不存在 |
| 修复 | `ed16e69`：customize.sh 增加 APK 发现回退链（`$MODDIR` 未设时从 `$0` 推导；依次搜索 MODDIR/cwd/modules_update；最后从 `$ZIPFILE` 重新解压） |
| 复装 | **`ksud module install` → "- Module installed successfully!"**，`/data/adb/modules/portal_ksu` 建立（`update` 暂存布局） |
| Policy A2 | 流式 `pm install -r -d -S` 重装 APK；**tv.kyshu 保留，versionCode=57，用户数据不动** ✅（`uninstall.sh` 永不调用 `pm uninstall`） |
| boot-guard | `post-fs-data.sh` 连续 ≥2 次未完成 boot 即停用自身（trip 计数器 `/data/adb/kyshu/boot_guard`）；`service.sh` boot_completed+3s 后复位 |

## 5. 已知限制：需要一次真机重启完成激活（必须现场执行）

本会话所有验证均在设备运行态完成，**未硬重启**（遵守远端约束）。以下路径只能在真实重启（post-fs-data 阶段）激活：

1. **portal_ksu 模块从 `modules_update` 迁移生效**（boot-guard 计数器、延迟安装、service.sh 脚本）。
2. **LSPosed daemon 复活**：kill system_server / stop;start 均不经过 post-fs-data，Zygisk Next 的 `zygiskd`（及其拉起的 lspd）无法在运行态重生（`lspctl status` → Connection refused；新 system_server maps 无注入痕迹属预期）。因此 v57 hook 代码在 system_server 内的**运行时激活**（hook 安装日志、UID 门控生效、隐藏路径返回空/脱敏）需一次真机重启后验证。
3. P0-4 Zygisk PLT hook 的 native 库随模块同样在重启后进入 system_server。

**现场操作清单（一次重启完成）**：
```
# 在设备上直接重启（非远程）后：
su -c ls /data/adb/modules/portal_ksu        # 确认模块已从 update 迁移
su -c ls /data/adb/kyshu                     # 确认 boot_guard 状态
adb logcat | grep -F "[PTL]"                 # hook 安装日志（WlanHook hide-path 等）
# LSPosed 管理器确认 Portal 启用 + scope 仍为 android/com.android.phone
```

## 6. 延后验证项（依赖上述重启）

| 验证项 | 方法 | 判据 |
|---|---|---|
| UID 门控 | `set_uid_targets` 下发目标表 → 非 UID 应用请求位置 | 非 UID 拿真实数据；epoch=0 时回退全局行为 |
| WiFi/基站/BLE 隐藏 | 目标 UID 调 getConnectionInfo/getScanResults/getAllCellInfo/onScanResult | 空/脱敏返回（Kuroko 保守语义，无伪造对象） |
| 轨迹平滑 | 启动平滑路线回放 | logcat `route tick speed=… cf=…`；无航向瞬变 |
| 步数单调 | 步数 App 观察 | 只增不减、无硬件跳变 |
| 目标 App maps | `grep zygisk /proc/<pid>/maps` | 无残留 |
| boot-guard | 连续重启两次注入失败场景 | 第三次自动停用 |

## 6.5 无需重启项实测补充（11-25 第二轮）

| 检查 | 结果 |
|---|---|
| 模块完整性（portal.apk 设备实解压 SHA-256 = `f6cbca9e…d0af`，与 apk_meta.prop 一致） | ✅ |
| 4 个脚本 `sh -n` 语法 | customize/post-fs-data/service/uninstall 全 syntax-ok ✅ |
| uninstall.sh 含 `pm uninstall` | 0 处（仅注释提及），Policy A2 成立 ✅ |
| boot-guard 沙箱演练（设备端真实路径 /data/adb/kyshu/boot_guard） | before=0 → inc=1 → inc=2 → **trip 判定 ≥2 hooks refuse** → reset=0 → restored-absent ✅ |
| Portal 应用运行时 | MainActivity 启动 pid 26706，NotificationChannel "Portal Location" 创建，无 crash ✅ |
| 系统 mock 通道（无需 LSPosed） | `cmd location set-location-enabled true` + `appops mock_location allow` + `add-test-provider gps` → dumpsys 出现 `gps provider [mock]` + `mock provider override` 日志 ✅ |

结论：mock 通道在无 LSPosed 的情况下独立可用（Portal 以 test-provider 模式驱动 mock）；这验证了 app 侧 mock 链路健康。但 v57 的 **system_server 侧 hook 强化**（UID 门控、WiFi/基站/BLE 隐藏、hook 安装日志、boot-guard 正式 trip 行为）仍依赖一次真机重启后的 post-fs-data 阶段。

## 6.6 无需重启项实测补充（11-25 第三轮：字段级一致性联动落地）

| 检查 | 结果 |
|---|---|
| 设备 `portal_ksu` 布局 | 仅 `module.prop` + `update` 标记（ksud 暂存布局，payload 待重启迁移）— 与 §5 预期一致 ✅ |
| `/data/adb/kyshu/` 状态目录 | 存在（0700），`boot_guard` 缺席 = 复位态，无残留 trip ✅ |
| uninstall.sh 设备侧内容 | 暂存布局下不可见，待重启迁移后核对；zip 层 SHA-256 + 本地读取确认 0 处活跃 `pm uninstall` ✅ |
| **字段级一致性代码落地**（commit `58f7dae`） | `LocationServiceHook.currentGnssConsistency()` 快照 API（5s 过期门控）；LocationFactory extras（satellites/maxCn0/meanCn0）与星群同源；hideMock 时剥离演示性 extras；NMEA GGA/GSA/GNS 的 used-in-fix 数与星群 `usedInFixCount` 同源 ✅ |
| 远端构建验证 | `:app:assembleAppRelease` + `:app:testAppDebugUnitTest`（见下方补记） |

补记：远端构建（`consistency_build2.log`，11-24 23:02）—— 首轮 `consistency_build.log` 因 `meanCn0` Double→Float 编译错误失败（后由 `7ba1859` 修复）；第二轮 **BUILD SUCCESSFUL in 1m 7s**（187 tasks，含 `:app:testAppDebugUnitTest`），产出 `Portal-1.0.8-7ba1859-20261125-1141-all.apk`（versionCode=62）。

## 6.8 Task 13 运行时验证：extras↔星群↔NMEA 字段级一致性（11-25，v58 真机）

> 本节证据全部来自 v58（`1.0.8-7ba1859`，含 `58f7dae`+`7ba1859` 一致性代码）**安装并加载后**的运行态。此前在 v57 上收集的证据仅证明 mock 链路健康，不覆盖一致性代码路径。

### v58 部署与激活

| 步骤 | 结果 |
|---|---|
| 安装 | `adb install -r -d` Success，versionName=`1.0.8-7ba1859-20261125-1141`（21:40:50） |
| hook 加载 | ss 软重启（pid 26156）后，`com.android.location.fused/cache/.svc_tel_*.jsonl` 新增 HOOK_INSTALL 记录（t=21:41:35）→ v58 模块在 ss 运行 ✅ |
| 配置修复 | 发现 domain config `location` 回落 (0,0) 且 `preserve_mock_flag=false`（隐藏 mock 态）；经 UI 停止→选择 BeijingTest→启动 完整重放 `update_location→put_config→start→is_start→broadcast_location` 序列后恢复 39.9042/116.4074 + `preserve_mock_flag=true` ✅ |

### 探针（com.probe.nmea，未注入三方消费者）实测

探针 APK 演进 v1→v3（远端 `/root/portal-build/nmeaapk/`）：v2 增加 extras/mock/speed 逐字段 dump；v3 再增加 `registerGnssStatusCallback`。

| 验证项 | 结果 |
|---|---|
| 位置投递 | LOC 39.9042±漂移, 116.4074±漂移，acc≈25，1s 节奏，`_inj=true` ✅ |
| mock 标志 | `preserve_mock_flag=true` 时 `mock=true`；`hideMock` 态（`preserve_mock_flag=false`）时 `mock=false` 且 `maxCn0/meanCn0` extras 被剥离 —— **两态均按 LocationFactory 设计执行** ✅ |
| **extras↔星群同源（核心）** | 探针注册 GnssStatus 回调后：`GNSSSTAT#2 sats=56 maxCn0=39 meanCn0=30` 与同刻 `LOC#388 satellites=56 maxCn0=40 meanCn0=29` —— **同一星群快照源（`currentGnssConsistency()`），数值差 ≤2 dB 在刷新节奏内一致**；随机回退分支（satellites=8~40 抖动）不再出现 ✅ |
| GNSS 注入对三方 | 探针自身 GnssStatus 回调收到 hook 注入的 56 卫星星群（GPS 31·GNS 0·北斗 0 口径）✅ |
| NMEA | 0 句（室内无真实 GNSS 定位，HAL 不产句子，改写钩子无从触发）—— 维持 §6.7 记录的既有局限 |

### 机制说明（写入代码注释的语义）

`lastMockGnssData` 仅在 `registerGnssStatusCallback` 的 GnssStatus 分发 `beforeHook` 内更新（LocationServiceHook.kt:1097）。因此 `currentGnssConsistency()` 快照的存在前提是**有外部 GnssStatus 消费者在收**；无消费者时 extras 走独立随机采样回退分支（8~45 卫星），避免 extras 长期冻结在过期星群。本次用探针自身充当消费者验证了同源分支；纯 extras 消费（不注册 GnssStatus）走回退分支属预期设计，非缺陷。

### 证据文件（远端 `/root/portal-build/evidence_t13/`）

`ev_gnss_v58.png`（v58 GNSS 屏）、`ev_probe_v2_extras.png`（探针 extras 投递屏）、`probe_v3_logcat.txt`（549 行，GNSSSTAT↔LOC 同源对照）、`ev_mock.png`/`ev_toast.png`（v57 期 mock 卡片与状态 toast）。

**结论：Task 13 的 extras↔星群 联动在真机 v58 上闭环验证通过（同源 + 回退两分支均符合设计）；NMEA 改写腿受室内无星限制，维持代码路径 + 单测 + smoke 证据。**

## 6.7 真机重启实测（11-25，用户授权后执行）

| 步骤 | 结果 |
|---|---|
| 前置核查 | USB 直连（`usb:1-4`，lsusb 确认 Meizu 20 Pro 挂在 RHEL 总线上）、`persist.sys.usb.config=adb`、adb_keys 持久化 2 条公钥、电量 100% USB 供电、`ro.adb.secure=1` |
| `adb reboot` | 全程 USB adb 存活；boot_completed=1 恢复（<90s）✅ |
| 重启后 bootreason | `reboot,shell`（干净软件重启；对比重启前基线 `kernel_panic,oops:_fatal_exception` 为 11-21 历史遗留）✅ |
| 内存 root 清除 | `su: inaccessible or not found` —— GhostLock/KernelSU 内存态 root 如预期失效 ✅ |
| adb_keys 授权 | 无弹框直接恢复连接（密钥白名单生效）✅ |

**结论**：`adb reboot` 在当前拓扑（设备 USB 连 RHEL，ZeroTier 承载 ssh）下安全 —— adb 不依赖网络态、授权持久化、init 自动恢复 USB 枚举。远端无人值守重启验证通过。

### 远端临时 root 恢复流水线（flyroot 适配）

- 位置：RHEL `/root/flyroot-pipeline/`（自 `C:\Users\29668\Documents\flyroot` 适配：去 cygpath/Windows adb 路径、`adb -s` 显式串号、新增 `05-wake.sh` 息屏唤醒、APK 预置 `apks/`）。
- 关键事实修正：KernelSU 为 GhostLock late-load **内存态 root，重启即失效** —— 重启后必须重跑 `30-start-shizuku.sh` + `40-run-ghostlock.sh`（UI 自动化导入 profile → 强制攻击 → 执行）恢复 `u:r:ksu:s0`，portal_ksu 模块的 post-fs-data 流程需在 root 恢复后由 ksud 补执行。

## 7. 远端环境备注

- `/root/portal-build`：完整 clone（unshallow 必须，否则 versionCode 退化为浅计数）、`.env`（BAIDU_MAP_KEY、KEYSTORE_PATH）、`release.jks`。
- `gradle.properties` 中 `org.gradle.java.home`（Windows 路径）已删除并提交（02d75d5），远端构建从此干净。
- 任务脚本沉淀在远端 `*.sh`（soft_restart / post_restart_verify / pack_ksu / flash_ksu2 等），未同步 C:\Users\29668\Documents\RHEL 的任何内容到远端持久存储。
