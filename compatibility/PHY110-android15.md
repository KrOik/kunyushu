# PHY110 Android 15 compatibility record

This record is based on files copied from the device on 2026-09-13 and analyzed
offline. It is not a claim that the revised APK has been installed or validated on
hardware. No framework binaries, LSPosed databases, or personal logs belong in
source control.

## Evidence

- Device: OPPO PHY110 / OP565FL1, Android 15 (API 35), arm64-v8a.
- Build: `OPPO/PHY110/OP565FL1:15/UKQ1.231108.001/U.51f1291-3243aba-3243abb:user/release-keys`.
- Full DEX was present in framework.jar, framework-location.jar, services.jar,
  oplus-framework.jar, oplus-services.jar and oplus-lbs-services.jar. OAT/VDEX was
  unnecessary. Local acquisition manifest: `log/device_preflight_20260913/manifest.json`.
- framework-location.jar SHA-256:
  `6bc2e0d714becf86e9f0c70c602b12958ac21f6bd7423ee3a087053389fb9048`.
- Decompiled critical classes and the detailed report are retained locally under
  `log/device_preflight_20260913/targeted/` and `REPORT.md`.

## Verified contracts

| Entry | Argument order | Return |
| --- | --- | --- |
| getLastLocation | provider, LastLocationRequest, packageName, attributionTag | Location |
| getCurrentLocation | provider, LocationRequest, ILocationCallback, packageName, attributionTag, listenerId | ICancellationSignal |
| registerLocationListener | provider, LocationRequest, ILocationListener, packageName, attributionTag, listenerId | void |
| registerGnssNmeaCallback | IGnssNmeaListener, packageName, attributionTag, listenerId | void |
| sendExtraCommand | provider, command, Bundle | void |
| IGnssNmeaListener.onNmeaReceived | long timestamp, String nmea | void |
| OnNmeaMessageListener.onNmeaMessage | String nmea, long timestamp | void |

The NMEA transport reverses argument order between the Binder and application
listener. Runtime installation must verify signatures; transaction numbers are
not an invitation to probe services. Oplus processes sendExtraCommand before the
framework permission checks and can return early. Hook installation is not proof
of command delivery or feature output.

## Required protections

### Proxy relay

The old watchdog captured a thread in pingBinder while holding the proxy list
lock, with other system_server Binder threads waiting for that lock. Registration
and snapshots must perform local collection operations only. The worker uses a
cached isBinderAlive check and the one-way transaction result to remove failed
peers; it never pings a peer. Stale queued sessions are discarded. A bounded worker
queue protects command threads; it does not guarantee delivery to a frozen peer
or make native Binder calls interruptible.

### NMEA consumers

DeepThinkerDelegate converts GPGSV commas to semicolons, removes line endings,
concatenates successive messages without a separator and calls Double.parseDouble
on selected fields. Thus `29*73$GPGSV` can be produced by the consumer itself;
single-sentence output validation alone is insufficient.

The NMEA gate suppresses identified internal consumers only while simulation is
active, and preserves callbacks when simulation is off. This deliberately pauses
NMEA delivery to those consumers while active; it is not simulated NMEA support
for DeepThinker. Application listeners remain independently eligible for rewrite.
Callback tracking uses weak identities, not identity hash codes. Unknown signature
shapes are skipped. Listener removal remains available even when raw GNSS data
registration is blocked. Generation exceptions fuse the NMEA rewrite domain.

### Location samples

Injection constructs a replacement without modifying the incoming Location.
Ordinary construction failures immediately fuse LOCATION_INJECTION and return the
original object. The fuse is process-local until reset/reload; it is separate from
the persisted installation guard and does not prove cross-reboot recovery.

- A configured speed of zero stays stationary, regardless of real speed or noise.
- Zero altitude is valid, without the former 80 m substitution.
- Unknown MSL altitude and MSL accuracy are cleared; ellipsoid altitude is not MSL.
- Fresh generated coordinates carry fresh wall and monotonic timestamps, and do
  not retain the real measurement's elapsed-time uncertainty.
- Location.isComplete checks provider, accuracy presence and nonzero timestamps;
  it does not certify physical correctness. A cached fix rewritten to the current
  simulated position now represents a newly generated sample, not its old age.

## Local verification and remaining limits

Run with JDK 21: `gradlew :xposed:testDebugUnitTest :xposed:testReleaseUnitTest`.
Xposed API is a compile-only dependency for production and a runtime dependency
for JVM tests. The library is no longer pre-shrunk: final application R8 still runs
with consumer keep rules and the application's own rules. This also keeps release
unit tests linked to their source class names. Test availability does not authorize
a device connection.

The current AndroidX Lint detectors fail with AGP 8.7.1's bundled Lint 31.7.1
(`KaCallableMemberCall` class/interface mismatch). `android.experimental.lint.version`
is set to `9.0.1`, which AGP translates to Lint artifacts `32.0.1`. Release checks
remain enabled; the failing detector is not suppressed. This is a build-time tool
override, not an Android runtime dependency or AGP plugin migration.

Regression coverage includes blocked peers, no synchronous pings, callback
signature drift, internal/application listener isolation, stop/passthrough,
zero speed/altitude, MSL flags, timestamp renewal, parcel round trips, and a
malformed profile that must preserve the input while fusing further injection.
I/O and permission failures in diagnostic file sinks disable the affected sink;
in-memory fuse state and telemetry remain usable. This is exercised with local
unwritable targets, not a claim of device SELinux validation.

No revised APK has been installed by this work. Strict SELinux persistence,
runtime hook coverage, actual target-app values, Native hooks and OEM wireless
interfaces still require separately scoped validation. Existing lspdoze broadcast
ANRs are baseline evidence, not proof of a kyshu conflict. Do not turn on all
system/native domains on the basis of these local tests.

### Verification on 2026-09-13

- `:xposed:testDebugUnitTest`: 170 tests, zero failures/errors/skips.
- `:xposed:testReleaseUnitTest`: 170 tests, zero failures/errors/skips.
- `:app:testAppDebugUnitTest`: 59 tests, zero failures/errors/skips.
- `:app:assembleAppDebug`: successful.
- `:app:assembleAppRelease`: successful, including vital Lint and application R8.
- Machine-readable results: `log/preflight_fix_validation.json`.
- APK paths, sizes and SHA-256: `log/preflight_fix_artifacts.json`. Both APKs
  preserve the Xposed entry and new protection classes, without packaging the
  test-only Xposed API classes.

APK version strings still derive from the last Git commit and do not identify
uncommitted changes. Use the artifact SHA-256 in the local validation bundle to
distinguish these builds from the older device APK. These changes were applied on
top of the existing worktree without committing unrelated work.
# 2026-09-13 真机启动后定位失败修复

真机能够重启并挂载模块，但这不能验证模拟开启后的定位链路。新提交的 LSPosed 压缩包没有模块运行日志；经用户授权，以 `adb exec-out` + `su -c tar` 只读获取 `/data/adb/lspd/log` 和应用日志/崩溃目录。未改变 SELinux、未启停模拟、未安装 APK、未重启。设备返回 Permissive。原始流与本地解包位于 `log/runtime_20260913/device/`；原始 tar 流带有一行 tar 路径提示，本地解析时剥离该前缀，并将文件名中的冒号映射为下划线。

## 已确认根因

`modules_2026-09-13T14_03_18.525916.log:2303` 记录 system_server（PID 3405）中的 `StackOverflowError`。调用链反复出现：

`BasicLocationHook(Location.set) -> injectLocation -> LocationFactory -> Location(origin) -> Location.set`。

本轮拉取的模块日志共出现 10 处 `java.lang.StackOverflowError` 文本记录，并非可据此推定 10 次独立系统崩溃。注入标记是在复制完成后设置，因此阻止不了复制构造期间的递归。普通异常熔断也不能预防递归；StackOverflowError 属于 VirtualMachineError，原注入保护会重新抛出，外层日志格式化还会在栈耗尽时再次失败。此调用链直接指向 kyshu 自身，尚无证据需要归因于其他模块冲突。

## 修复

- BaseLocationHook 使用所有实例共享、各线程隔离的重入标记；递归调用直接保留原对象，finally 清除标记。正常非致命错误仍按原策略熔断。
- 查询模拟状态增加 Boolean? 返回值，区分停止与通信/字段缺失。停止只有收到明确 false 才算确认；停止前不再以可能失败的状态查询阻止 stop 命令。
- 停止按钮遵循操作结果；启动先提交坐标，再启用并广播。部分成功时查询远端状态，保留停止入口，不把广播失败误报为完全未启动。
- 启停协程通过 finally 完成 UI 回调；异常不再让 busy 持续卡住。静态位置模拟不再依赖仅摇杆需要的悬浮窗权限。

## 验证边界

新增 Robolectric shadow 模拟真实 `Location(Location) -> set()` 路径，并断言此路径实际执行、输出位置正确、输入未修改、后续注入可继续。另有状态响应缺失、明确停止及通信失败测试。具体构建与测试结果见 `log/runtime_20260913/final_verify.log` 和 `validation.json`。

本轮修复尚未安装到真机；目标应用收到模拟坐标、厂商融合定位覆盖和真实启停回调仍需后续真机验证。不能从本地测试推断真机所有定位路径已恢复。
