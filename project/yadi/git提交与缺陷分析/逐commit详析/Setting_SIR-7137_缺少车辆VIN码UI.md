# SIR-7137 · 系统设置页缺少车辆 VIN 码 UI
- **提交**：`0e5e62f6` | 2026-09-01 | dufan | Setting | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
系统设置页首次进入时车辆 VIN 码不显示（VIN 行直接被隐藏）。

## 根因分析
`SystemFragment.initObserve()` 里在主线程同步调用 `settingVehicleService.getProperty(CarPropertyIds.VEHICLE_VIN_CODE)` 读取车控属性。该属性走 Binder/车控服务同步查询，首次调用时服务端尚未就绪或查询未完成，返回 null/空值；代码随后走 `?: run { mBinding.llVin.visibility = View.GONE; ... }` 分支把 VIN 行隐藏，且之后不会再重试——表现为"缺少 VIN 码 UI"。本质是把异步数据源当同步数据源用，并以一次失败结果做出永久性 UI 决策。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt`、`.../utils/VoiceOperationUtil.kt`
```diff
--- .../ui/fragment/SystemFragment.kt
-        val vinProperty = settingVehicleService.getProperty(CarPropertyIds.VEHICLE_VIN_CODE)
-        vinProperty?.takeIf { it.value.toString().isNotEmpty() }?.let {
-            mBinding.tvVin.text = it.value.toString()
-        } ?: run {
-            mBinding.llVin.visibility = View.GONE
-            mBinding.vinDivider.visibility = View.GONE
-        }
+        lifecycleScope.launch(Dispatchers.IO){
+            val vinProperty = settingVehicleService.getProperty(CarPropertyIds.VEHICLE_VIN_CODE)
+            log("vinProperty：$vinProperty")
+            withContext(Dispatchers.Main){
+                vinProperty?.takeIf { it.value.toString().isNotEmpty() }?.let {
+                    mBinding.tvVin.text = it.value.toString()
+                } ?: run {
+                    mBinding.llVin.visibility = View.GONE
+                    mBinding.vinDivider.visibility = View.GONE
+                }
+            }
+        }
```
另删除 `SystemFragment` 中已废弃的 `showDialog()` 及其注释代码，`VoiceOperationUtil` 对应调用同步注释。

## 为什么能修复
VIN 读取移入 `Dispatchers.IO` 协程：子线程给底层服务留出响应时间，首次读取能拿到返回值，再切回主线程更新 `tvVin`；拿不到仍隐藏 VIN 行，逻辑保持一致。隐患：仅是把同步调用换成"延迟一点的同步调用"，若服务迟迟未就绪依然会隐藏且无重试/属性监听，属概率性缓解而非事件驱动根治；Fragment 销毁时协程随 lifecycleScope 取消，无泄漏。

## 复盘与经验
- 车控 `getProperty` 类 Binder 同步查询在主线程首次调用经常拿不到值：耗时且不可靠，应放 IO 线程并尽量用属性变化监听而非一次性读。
- 用一次读取失败做永久 UI 决策（隐藏整行）时，必须配套重试或监听，否则表现为"功能缺失"。
- 清理死代码（showDialog 及注释块）值得肯定，但注意同步修改所有调用点（语音操作工具），避免留悬空调用。
