# SIR-1429 · 车设弹窗显示时切回home再返回，弹窗仍存在（Setting 侧：onPause 收弹窗）

- **提交**：`a102acb6` | 2026-06-27 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（rootCause："未判断是否需要关闭"，方案："添加关闭逻辑"）

## 问题
同 `6b9b198a`：车设 WLAN/蓝牙/热点弹窗打开时按 home 键回桌面再返回，弹窗仍存在。本提交是同一缺陷在 Setting 模块的消费端修复。

## 根因分析
弹窗 `mWlanDialogFragment`/`mBluetoothDialogFragment`/`mHotspotDialogFragment` 由 `ConnectFragment.showConnectDialog(type)` 弹出后，只在用户主动操作时关闭；切 home 不触发任何关闭路径。修复在 `ConnectFragment.onPause()` 增加判断：读取 SystemUI 埋下的全局标识 `getGSetting("is_click_home", 0)`，等于 1（home 刚被按下，见 `6b9b198a` 埋点）时对三个弹窗逐一 `safeDismiss()`。`onPause` 正是"页面将失去前台"的最早可靠回调，能覆盖按 home 的场景。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/ConnectFragment.kt

```diff
--- application/Setting/.../ui/fragment/ConnectFragment.kt
@@ onPause 判断 home 标识并收起全部弹窗
+    override fun onPause() {
+        super.onPause()
+        if (getGSetting("is_click_home", 0) == 1) {
+            mWlanDialogFragment?.safeDismiss()
+            mBluetoothDialogFragment?.safeDismiss()
+            mHotspotDialogFragment?.safeDismiss()
+        }
+    }
```

## 为什么能修复
缺陷链路"按 home → Setting Activity 失去前台 → 触发 `ConnectFragment.onPause` → 标识为 1 → 三个 DialogFragment 全部 `safeDismiss()`"被打通，返回车设时弹窗已随离开动作收起，消除"弹窗常驻"现象。`safeDismiss()` 封装了 `isAdded`/状态检查，避免 `onPause` 阶段 dismiss 崩溃。隐患：依赖 SystemUI 200ms 内不复位的时序窗口（见 `6b9b198a` 分析）；且只覆盖 ConnectFragment 一处，其他页面若有同类弹窗需要复制同样逻辑，属于逐点修复而非机制修复。

## 复盘与经验
- **收浮层挂在 `onPause` 而非 `onStop`/`onDestroy`**：`onPause` 是失前台必经点，`onDestroy` 在 Activity 常驻回收场景可能永远不来。
- **两端协作修复要同批合入**：本缺陷 SystemUI（埋标识）与 Setting（消费标识）3 分钟内先后提交，缺一半即无效——跨模块方案要有原子性意识。
- ** DialogFragment 引用要集中管理**：三个弹窗字段逐一 safeDismiss 的写法，容易在新增弹窗时漏掉；可用"当前弹窗栈"统一关闭。
