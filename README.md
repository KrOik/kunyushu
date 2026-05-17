# 坤舆枢 (KunYuShu)

> 秋夜长，殊未央，月明白露澄清光，层城绮阁遥相望。  
> 星图算，羁尽扬，虚位感枢摹万象，咫尺乾坤任我行。

**坤舆枢** 是一个基于 Xposed 框架的高级 Android 定位模拟与多项传感器注入工具。它旨在提供精确、隐蔽且高度可定制的地理位置和传感器数据模拟功能，适用于开发测试、隐私保护等场景。

## 核心功能

*   **高精度定位模拟**: 支持 GPS, GLONASS, Galileo, Beidou 等多星座卫星信号模拟。采用星图+云图解算确保拟真性
*   **传感器注入**: 模拟加速度计、陀螺仪、步数传感器，支持步态模拟与抖动生成。
*   **WiFi/BLE/基站模拟**: 深度 Mock WiFi 列表 (SSID, BSSID, RSSI) ,蓝牙信息和基站信息 (CID, LAC)。
*   **隐蔽性设计**: 采用 Xposed 底层 Hook，支持系统服务级的数据篡改，难以被应用层检测。

## 技术架构演进 (v1.0.4+)

*   **IPC 通信升级**: 采用 **ContentProvider** 实现 App 与 Xposed 模块的跨进程配置同步，完美适配 Android 14+ 严格的文件权限隔离策略，摒弃传统的文件轮询机制。
*   **Native 对抗增强**: 集成 **Dobby** 框架下沉关键 Hook 逻辑 (Inline Hook)，在 Native 层监控并拦截底层文件访问 (`open`) 与 IO 控制 (`ioctl`)，有效对抗深层 Root/Hook 检测。
*   **现代化架构**: 全面引入 **Hilt** 依赖注入与 MVVM 模式，解耦业务组件，提升代码可维护性与测试覆盖率。

Due to some compliance risks, no release is available
