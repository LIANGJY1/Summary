# SIR-5722 · 热点打开失败后控制中心未回退热点状态（SystemUI 侧）

- **提交**：`50db7e79` | 2026-08-06 | dufan | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（rc：状态未同步 / sol：同步状态）

## 问题
热点打开失败后，控制中心（SystemUI 快捷开关）里的热点图标仍处于"开启未连接"状态，与 Setting 侧弹窗（修复见 `dcdcbb9b`）是同一单号的两个改造点。

## 根因分析
`SystemSettingsControllerService` 中处理 `WifiManager.WIFI_AP_STATE_FAILED` 广播的分支原本只打印错误日志 `---Failed to enable hotspot---`，**没有向 UI 回调下发任何状态**。热点开关点击时控制中心会乐观地把图标置为开启，而开启失败事件被该分支吞掉，`mQuickSettingUICallback.updateHotspot(...)` 从未被调用，控制中心失去唯一一次状态纠偏机会，图标永久停留在"开启未连接"。缺陷库归因"状态未同步"即指这条回调链断裂。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt`
```diff
// application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt
-                    WifiManager.WIFI_AP_STATE_FAILED ->                         // 热点开启失败
+                    WifiManager.WIFI_AP_STATE_FAILED -> {
+                        // 热点开启失败
                         LogUtils.e(TAG, "---Failed to enable hotspot---")
+                        if (mQuickSettingUICallback != null) {
+                            mQuickSettingUICallback!!.updateHotspot(false, 0, "")
+                        }
+                    }
```

## 为什么能修复
失败广播到达时主动调用 `updateHotspot(false, 0, "")`，把控制中心热点图标拉回关闭态，UI 状态与真实 Wi-Fi AP 状态重新对齐。改动仅 6 行，风险极低。隐患：`mQuickSettingUICallback!!` 非空断言写在判空 if 内理论上安全，但回调若为弱引用/异步置空仍存在 NPE 窗口；另外"开启失败回退为关闭"假设了失败即回到关闭态，若系统失败后仍保持 ENABLING 中间态，图标可能与中间态短暂不一致。

## 复盘与经验
- "只记日志不通知 UI"的异常分支是状态不同步 bug 的高发地：失败/超时/取消这类"非正常路径"也要有对应的 UI 收敛动作。
- 同一个 bug 在 Setting 与 SystemUI 两个进程/模块各有一份状态副本时，必须两边都修（本单号 dcdcbb9b + 50db7e79 双提交即例证）；设计上更应推动控制中心订阅单一状态源而非各自维护。
- 快捷开关普遍采用"点击乐观置位 + 系统回调纠偏"模式，评审时要专门检查失败分支是否调用了纠偏回调。
