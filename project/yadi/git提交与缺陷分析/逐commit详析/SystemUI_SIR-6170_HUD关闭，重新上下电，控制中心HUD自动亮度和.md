# SIR-6170 · HUD关闭重新上下电后控制中心HUD开关/亮度条异常高亮

- **提交**：`58ef7197` | 2026-08-23 | dufan | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
HUD 处于关闭状态，车机重新上下电后，控制中心里"HUD自动亮度"开关和 HUD 亮度条呈高亮（可用/开启）显示，与实际关闭状态不符。

## 根因分析
缺陷库根因："L2A初始化慢了，导致SystemUI的初始化状态丢失"。SystemUI 启动时会向 L2A 查询 HUD 各信号初值，但此刻 L2A 尚未连接，查询请求丢失；UI 只能停留在默认的高亮态。`SystemSettingsControllerService` 里有一个补偿机制：检测到 HUD 连接状态由非 1 变为 1（`hudState == 1 && mPreHudState != 1`，即重新上下电后 L2A 连上）时会补查一批 HUD 信号，但补查清单里只有 `ID_HUD_AUTO_SWITCH`（自动亮度）和 `ID_HUD_BRIGHT_ADJUST`（亮度），漏了 `ID_HUD_SWITCH`（HUD 总开关），导致自动亮度/亮度条被刷新而总开关状态仍是错的。另外 `HUDBrightnessTile` 中 `updateButtonState()` 写在状态回调外面，开关状态回调到达时没有随之刷新按钮可用态。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt、application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/HUDBrightnessTile.kt
```diff
--- application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt
@@ L2A/HUD 连接后的补偿查询
                 } else if (hudState == 1 && mPreHudState != 1) {
                     // HUD已连接
                     mPreHudState = hudState
+                    getHud(SysUIConfig.ID_HUD_SWITCH)
                     getHud(SysUIConfig.ID_HUD_AUTO_SWITCH)
                     getHud(SysUIConfig.ID_HUD_BRIGHT_ADJUST)
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/HUDBrightnessTile.kt
@@ 状态回调内
                 } else {
                     ivSwitch.isSelected = false
                 }
+                updateButtonState()
             }
-            updateButtonState()
```

## 为什么能修复
第一处改动把总开关信号加入"HUD 连上后补查"清单，消除了因 L2A 慢启动丢掉的初始化查询——重新上下电后 UI 拿到真实的 `ID_HUD_SWITCH` 值，高亮状态恢复正确；第二处把 `updateButtonState()` 移进状态回调内，保证开关状态到达后按钮立即按最新状态刷新，两个改动闭环。隐患：补偿机制依赖 `mPreHudState` 的跳变检测，若 L2A 在 SystemUI 启动前就已连接（无 0→1 跳变），仍依赖启动时查询路径，属既有设计未变。

## 复盘与经验
- 跨进程/跨系统初始化时序不确定时，必须有"连接建立后重查全量状态"的补偿路径，且清单要与初始化查询清单对齐——漏一项就出一个必现 UI 状态 bug。
- 上下电、睡眠唤醒这类重走初始化链路的场景，是"启动时序竞态"类 bug 的高发区，测试要专门覆盖。
- UI 组件里"状态刷新"与"按钮可用态刷新"要放在同一回调里，避免状态更新了而交互态滞后。
