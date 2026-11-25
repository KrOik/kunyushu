# 远端开发计划（Remote-Primary Development Plan）

> 生效日期：2026-11-25
> 结论：**Portal 后续开发全部在远端 RHEL `edaserver`（ZeroTier）进行，本机仓库 D:\Portal 停用（只读归档）。**
> 代码通道改版：kunyushu `portal-sync` 临时分支在远端 checkout 验证后**删除**；远端 `/root/portal-build` 成为唯一活动仓库。

---

## 1. 远端环境（唯一工作区）

| 项 | 值 |
|---|---|
| 主机 | RHEL 9.6 `edaserver`（ZeroTier，ssh 别名 `edazt`） |
| 仓库 | `/root/portal-build`（完整 clone，unshallow，versionCode = rev-list count） |
| 构建产物 | `app/build/outputs/apk/app/release/`；最新 v58 = `Portal-1.0.8-7ba1859-20261125-1141-all.apk`（versionCode 62，已装真机） |
| Android SDK | `/opt/android/sdk`（build-tools 36.0.0，platforms android-36/37.2，cmdline-tools d8） |
| Gradle/JDK | 远端 gradle 8.9 缓存；`org.gradle.java.home` 已清理（02d75d5） |
| 磁盘/内存 | 12G 可用 / 30G 内存（25G 空闲） |
| 设备 | Meizu 20 Pro USB 直连 RHEL（adb 串号 391QJFD8FES2V），KernelSU 3.3.0 + Zygisk Next 1.5.3 + LSPosed v2.2.1 |
| flyroot 流水线 | `/root/flyroot-pipeline/`（00-preflight → 05-wake → 10-capture-profile → 20-install-apps → 30-start-shizuku → 40-run-ghostlock → 90-verify） |

## 2. 代码同步通道（新）

旧通道（已退役）：本地 D:\Portal → push GitHub kunyushu `portal-sync` → 远端 pull。

**新通道（生效后）**：远端 `/root/portal-build` 直接开发 → 直接 commit/push GitHub（kunyushu 仓库 master 或特性分支，凭据已在远端 git 配置中）→ 必要时本地只读拉取查看。

- portal-sync 分支在远端 checkout + 验证后删除（见 §5）。
- 远端 git 凭据：仓库凭据已在远端配置（若 push 需要认证，首次 push 时在远端补 credential）。

## 3. 剩余路线图（承接 TODO.md §12 Phase 顺序与 verification-report.md 遗留项）

### 3.1 P0-4：Zygisk PLT hook 移植（下一主任务）

- 目标：传感器路径的 PLT/GOT hook（Zygisk 模块形态），替代 Dobby `sensor_hook.cpp` 作为隐身层。
- 远端参考库（见 §4）：`refer/NeoZygisk`（Zygisk API + PLT hook 实现）、`refer/ProcessHook`、`refer/Vector`（PLT hook 工程实践）、`refer/zeromount`、`refer/Portal`（上游 Kuroko 优点来源）、`refer/mockWalk`。
- 验收：PLT hook 在 ss/目标进程安装成功日志（FlightRecorder）；传感器读取被替换且 hook 不被内存扫描发现；无软重启回归。

### 3.2 现场重启清单（§5 of verification-report.md，一次性）

- portal_ksu 模块从 `modules_update` 迁移生效（boot-guard、延迟安装、service.sh）。
- NMEA 改写腿真卫星验证（需真实 GNSS 定位；室内无星，HAL 不产句子）。
- 操作清单见 docs/verification-report.md §5；重启后跑 post_restart_verify.sh。

### 3.3 TODO.md Phase 2–8（中长期）

- Phase 2 root 探查 harness：`RootProbeCommandPolicy` 已有 dry-run 基础，待接真机执行器（远端 su 通道）。
- Phase 4/5 WLAN/基站 mock 闭环、Phase 6 BLE 连接态、Phase 7 NFC 原型：均按 TODO.md 各节遗留项。
- UI 控制面重构（Phase 1 余项）：二级域面板、表格编辑、诊断详情页。

## 4. 远端参考库布局（/root/portal-build/refer/）

本机 `D:\Portal\refer`（4.1G，23 库）不迁移；远端按需 clone GitHub 源（小体积，浅克隆）：

| 库 | 用途 | 优先级 |
|---|---|---|
| `NeoZygisk` (JingMatrix) | P0-4 PLT hook + Zygisk API 参考主库 | P0 |
| `ProcessHook` (rifsxd) | 进程/hook 模式参考 | P0 |
| `Vector` (JingMatrix) | PLT/GOT hook 工程实践 | P0 |
| `zeromount` (Enginex0) | 挂载隐藏参考 | P1 |
| `Portal` (CrackerCat) | 上游优点来源（Kuroko 已吸收，保持同步参考） | P1 |
| `mockWalk` (KrOik) | 步态/轨迹参考（同为 kunyushu 名下） | P1 |
| `LocationSpoofer` (HuangZhuoRui) | 位置伪装架构参考 | P2 |
| hide-family (`FuseHide`/`HideBusybox`/`HideRecent`/`Amarok-Hider`/`HMA-OSS`/`ImNotADeveloper`) | 隐藏族参考，按需 | P2 |

NO-GIT 本地目录（`Ghostmaps`/`VoxLoction`/`cellular-z`/`jadx`）：无 upstream 可拉。jadx（静态分析工具，本机 4.1G refer 中占大头）不迁移——静态分析在本机做，远端只做构建/真机验证。其余 NO-GIT 目录若后续需要，从本机 tar 单独传输。

`D:\Portal\refs`（Android 各版本源码接口参考，51K 文本）随 plan 一起进 git 仓库，远端 checkout 即得。

## 5. 执行清单（本次远端化交接）

1. ~~本机写 plan 文档并推送 portal-sync~~（本文档）
2. 远端 `git fetch && git checkout portal-sync && git pull`（或等价 ff）到最新（含 plan 文档）→ 验证 `docs/remote-dev-plan.md` 在远端可见
3. 远端 clone §4 P0/P1 参考库到 `/root/portal-build/refer/`
4. flyroot 差异比对（本机 `C:\Users\29668\Documents\flyroot` vs `/root/flyroot-pipeline/`）——只比对脚本差异，**永不**同步 `C:\Users\29668\Documents\RHEL`（凭据红线）
5. 全部验证后：删除 GitHub kunyushu `portal-sync` 分支（远端 clone 已含全部历史，本地 D:\Portal 归档只读）
6. 本机 memory 更新为 remote-primary 工作流

## 6. 约束延续（不变红线）

- 临时 root（`su -c` 逐命令），授权的 GhostLock/root 恢复可随时执行
- **零注入**：LSPosed scope 永不勾选目标三方 App
- 无授权不硬重启；`killall system_server`/`stop;start` adb 安全
- 远端 adb 保持存活；ZeroTier 断链重试 30–60s×3 后报告
- `C:\Users\29668\Documents\RHEL` 内容永不写入远端持久存储
- 每个移植项需真机证据（probe APK / FlightRecorder / OCR）
- git 提交尾行：`Co-Authored-By: Claude Code <noreply@anthropic.com>`
