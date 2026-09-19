# SIR-3252 · 座椅位置重命名时不显示已保存名称（名称加载时序晚于按钮装订）

- **提交**：`67ccc508` | 2026-07-23 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
车控车设页点击座椅位置"编辑/重命名"时，弹出的输入框里没有回显先前保存过的位置名称。

## 根因分析
`VehicleControlFragment.kt` 中旧代码在 `setupPositionEditButtons()` 装订按钮监听时就把当时的 `seatPositionName1/2/3` **按值捕获**传入：`setupSinglePositionEdit(ivEditPosition1, 1, seatPositionName1)`。而真正给这三个变量赋值的 `loadSeatPositionNames()` 被放在了靠后的 `initSwitchState()` 里执行——装订发生在加载之前，捕获进闭包的是空/默认值；此后即使名称加载完成，点击弹窗里回显的仍是捕获时的旧值。提交 [why]"获取名字时序问题"与代码完全对应。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ initView（提前加载名称）
         updateSeatAdjustmentDividerVisibility()
+        loadSeatPositionNames()
         setListener()
@@ setupPositionEditButtons（去掉按值捕获）
-            setupSinglePositionEdit(ivEditPosition1, 1, seatPositionName1)
-            setupSinglePositionEdit(ivEditPosition2, 2, seatPositionName2)
-            setupSinglePositionEdit(ivEditPosition3, 3, seatPositionName3)
+            setupSinglePositionEdit(ivEditPosition1, 1)
+            setupSinglePositionEdit(ivEditPosition2, 2)
+            setupSinglePositionEdit(ivEditPosition3, 3)
-    private fun setupSinglePositionEdit(button: android.view.View, position: Int, name: String) {
+    private fun setupSinglePositionEdit(button: android.view.View, position: Int) {
         button.setOnClickListener {
             if (!isSeatControlEnabled) return@setOnClickListener
             if (isInvalidClick(it)) return@setOnClickListener
-            showEditPositionDialog(position, name)
+            val currentName = when (position) {
+                1 -> seatPositionName1
+                2 -> seatPositionName2
+                3 -> seatPositionName3
+                else -> "位置$position"
+            }
+            showEditPositionDialog(position, currentName)
         }
     }
@@ initSwitchState()（移除迟到的加载）
-        loadSeatPositionNames()
         mBinding.apply {
```

## 为什么能修复
双保险消除时序依赖：① `loadSeatPositionNames()` 提前到 `initView` 中 `setListener()` 之前，保证监听器装订时名称已就绪；② 更关键的是点击回调里**实时读取** `seatPositionName1/2/3`，即使名称在装订之后才异步加载完成，点击时拿到的也是最新值。从"装订时快照"改成"使用时读取"，彻底切断了对加载顺序的依赖。隐患很小：`else -> "位置$position"` 是新的兜底文案，非法 position 会显示中文默认名。

## 复盘与经验
- 回调/监听器捕获变量时传的是"当时值"，异步加载的字段要在**使用点**读取成员，而不是在装订点传参——这是 Kotlin/Java UI 代码最高频的时序 bug 之一。
- 装订监听器之前先把其依赖的数据加载好（顺序：加载数据 → 装订监听 → 刷新 UI），双管齐下最稳。
- Review 时看到 `setupXxx(view, staleValue)` 这类把可变状态当参数传进监听器的写法，应直接标记为风险点。
