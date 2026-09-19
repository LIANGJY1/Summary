# SIR-1429 · 车设弹窗显示时切回home再返回，弹窗仍存在（SystemUI 侧：home 点击标识）

- **提交**：`6b9b198a` | 2026-06-27 | dufan | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（rootCause："未判断是否需要关闭"，方案："添加关闭逻辑"）

## 问题
车设（Setting）里 WLAN/蓝牙/热点弹窗打开时按 home 键回桌面，再重新进入车设，弹窗依然挂着，与"离开页面即收起浮层"的交互预期不符。

## 根因分析
缺陷库根因"未判断是否需要关闭"与 diff 一致：按 home 只是切走 Activity，弹窗是 Setting 内的 `DialogFragment`，宿主 Activity 不销毁、状态原样保留，返回时自然原样恢复——整个链路上没有任何人负责收起它们。修复采用"跨模块点击标识"方案：导航栏（SystemUI）在 home 键点击时写一个全局设置项 `is_click_home=1` 作为事件信号，200ms 后复位为 0；车设侧在 `onPause` 读标识决定是否收弹窗（见配套提交 `a102acb6`）。本提交即 SystemUI 侧的信号埋点，顺带删除了 `WHAT_INIT_VEHICLE` 等未用的 handler 常量与 `onDriverAddDownTime` 等死字段。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java

```diff
--- application/SystemUI/.../navbar/ui/NavBarFragment.java
@@ home 键点击分支埋"点击标识"
                     if (currentPosition == 4) {
                         sendBroadcastExitAppListPage();
                     } else {
+                        SettingsUtils.INSTANCE.setGSetting("is_click_home", 1);
                         ActivityStarter.INSTANCE.goLauncher(requireContext(), view);
+                        handler.postDelayed(() -> SettingsUtils.INSTANCE.setGSetting("is_click_home", 0), 200);
                     }
```

## 为什么能修复
SystemUI 与 Setting 是两个应用，无法直接调用彼此方法；用 `SettingsUtils.setGSetting` 全局键值做"home 刚被按下"的信号，让车设在自己 `onPause` 时能区分"回 home"与"跳其他页面"，从而只在必要时收弹窗。隐患有三：其一，200ms 定时复位是竞态窗口——若车设 `onPause` 晚于复位则漏关；其二，全局键值无归属者文档化，其他页面读到 `is_click_home=1` 可能误伤；其三，信号粒度只有 home，未来侧边栏、语音返回等入口需要各自埋点。

## 复盘与经验
- **DialogFragment 的生命周期长于页面可见性**：宿主不销毁时弹窗会跨"离开/返回"存活，离开路径（`onPause`/`onStop`）必须显式收浮层。
- **跨应用通信缺接口时就地取材要留痕**：全局 setting 当信号量能用，但键名、写入方、复位时机应集中定义，否则变成隐形协议。
- **定时器复位是软实时**：用"写 1 → delay 200ms → 写 0"传递事件，天然存在丢失窗口，更稳的是"读取即清除"（消费型标志）。
