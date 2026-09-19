# 无单号 · 新增调试log方便追踪问题（WiFi 信号等级）
- **提交**：`5a5605d2` | 2026-09-05 | sgh | Setting/HardwareLibs | 日志类提交（无行为修复）
- **缺陷库**：未关联单号

## 类型说明
纯日志增强提交，为追踪"WiFi 信号等级显示"类问题补充观测点：
- `component/Hardwarelibs/.../network/wifi/WifiTracker.java`：`updateAccessPoints()` 排序前对每个 `AccessPoint` 打印 `ssid / rssi(dBm) / level`；
- `component/Hardwarelibs/.../network/wifi/WxWifiManagerI.java`：`getCurrentWifiLevel()` 先取 `rssi` 存局部变量并打印，最终同时输出 `level` 与 `rssi`，且把原先走 `LogService.WIFI.logD` 的日志改为 `android.util.Log.d`（便于用标准 logcat 过滤），并顺带消除了重复调用 `getWifiInfo().getRssi()` 的写法。

## 复盘与经验
- "等级显示不对"类问题的排查依赖 rssi→level 的映射输入输出同时可见，日志同时打印 rssi 与 level 是最小够用的观测设计。
- 项目自定义 LogService 与系统 Log 并存时，调试期改用系统 Log 更利于现场用 logcat 抓取，但要注意统一出口，避免长期分叉。
