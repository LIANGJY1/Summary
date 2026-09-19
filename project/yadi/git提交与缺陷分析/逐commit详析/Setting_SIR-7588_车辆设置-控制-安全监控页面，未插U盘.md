# SIR-7588 · 安全监控页未插 U 盘时存储空间未显示"--"
- **提交**：`96bb299b` | 2026-09-07 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：需求变更 → 按最新 UX 修改）

## 问题
车辆设置-控制-安全监控页面，未插入 U 盘时存储空间区域仍显示旧的占位内容，最新 UX 要求该场景下整块隐藏（而非显示"--"）。

## 根因分析
`SafetyMonitorFragment` 原有逻辑只关心格式化按钮的灰置：`updateFormatGrayState()` 用 `dvrConnected && hasUsb` 控制格式化按钮可用态，但存储区域本身（容量汇总、使用百分比、用量条、图例、格式化入口）没有任何可见性控制。未插 U 盘时这些 View 用的是布局默认状态和 `clearUsbMemory()` 清零后的数据，显示效果与最新 UX 稿（整块隐藏）不符。这是 UX 规格变更（从"显示--"改为"全部隐藏"）后，页面缺少对应可见性分支导致的展示缺陷。另外布局里 `ll_storage_summary` 原来**没有 id**，代码层根本无法引用它，说明这块 UI 一直是"静态绘制"，从未纳入状态管理。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt、application/Setting/src/main/res/layout/fragment_safety_monitor.xml
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt
@@ -451,6 +451,7 @@
     private fun updateFormatGrayState() {
+        updateStorageVisibility()
         val enabled = dvrConnected && hasUsb
         if (lastFormatBtnEnabled == enabled) return
@@ -460,6 +461,18 @@
+    /**
+     * U盘未插入时隐藏
+     */
+    private fun updateStorageVisibility() {
+        val visible = if (hasUsb) View.VISIBLE else View.GONE
+        mBinding.llStorageSummary.visibility = visible
+        mBinding.tvStorageUsedPercent.visibility = visible
+        mBinding.storageUsageBar.visibility = visible
+        mBinding.llStorageLegend.visibility = visible
+        mBinding.rlFormatStorage.visibility = visible
+    }
--- application/Setting/src/main/res/layout/fragment_safety_monitor.xml
@@ -235,6 +235,7 @@
             <LinearLayout
+                android:id="@+id/ll_storage_summary"
                 android:layout_width="match_parent"
```

## 为什么能修复
新增 `updateStorageVisibility()` 以 `hasUsb` 为唯一判据统一控制 5 个存储相关 View 的显隐，并挂在 `updateFormatGrayState()` 刷新链路里复用已有的状态刷新时机（U 盘插拔/DVR 连接变化都会走到这里），未插盘时整块 GONE。布局补 `ll_storage_summary` 的 id 使代码可引用。副作用很小：显隐只跟随 `hasUsb`，与 `dvrConnected` 解耦，插着 U 盘但 DVR 未连时仍显示存储区（只是数据为 0），需确认是否符合 UX，但目前无反馈问题。

## 复盘经验
- 布局中"没有 id 的业务区块"往往意味着从未被状态化，需求一变就要连 XML + 代码一起补，评审时可专门扫描无 id 的业务容器。
- 空态策略（显示占位符 vs 整块隐藏）是 UX 常变点，建议抽成统一的 visibility 控制函数而不是散在各处。
- 把新的显隐逻辑挂进既有刷新函数（updateFormatGrayState）是低成本复用刷新时机的做法，但函数名会逐渐名不副实，需注意重构时机。
