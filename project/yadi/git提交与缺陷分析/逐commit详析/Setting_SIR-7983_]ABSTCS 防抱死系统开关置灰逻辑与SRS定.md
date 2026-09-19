# SIR-7983/7978 · ABS/TCS 开关置灰逻辑与 SRS 不符（权限信号未注册）

- **提交**：`a71e8dfc` | 2026-09-10 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
ABS（防抱死）/TCS（牵引力控制）模式切换开关的置灰逻辑与 SRS（需求规格）定义不符：车端不允许切换模式时应置灰的场景没有置灰，开关表现与预期相反。

## 根因分析
权限置灰依赖两个车端权限信号：`CarPropertyIds.MTC_MODECHANGEPERMISSION`（TCS 模式切换权限）与 `CarPropertyIds.ABS_MODECHANGEPERMISSION`（ABS 模式切换权限）。`SettingVehicleService` 的"车控需要监听的信号列表"`callbackPropertyIds`（listOf，注册后才回调 `propertyHandlerMap` 更新 LiveData）中**漏掉了这两个权限信号**，导致 `DrivingSafetyFragment` 的权限回调永远不触发。而 Fragment 里 `isTcsPermissionEnabled`/`isAbsPermissionEnabled` 默认值是 `true`（默认可切换），于是无论车端权限如何，开关永远不被置灰。修复同时把默认值改为 `false`（未收到权限信号前先置灰），并在各回调里统一 `setGray = if (功能CAN使能) 权限位 else false` 的计算并加日志。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt、application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingSafetyFragment.kt（2 文件 +16/-6）
```diff
--- application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
@@ callbackPropertyIds 列表尾部
         CarPropertyIds.CCU_AL_IPMODESET,
+        CarPropertyIds.MTC_MODECHANGEPERMISSION,
+        CarPropertyIds.ABS_MODECHANGEPERMISSION,
     )
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingSafetyFragment.kt
@@ 权限状态默认值
-    private var isTcsPermissionEnabled = true
+    private var isTcsPermissionEnabled = false
@@
-    private var isAbsPermissionEnabled = true
+    private var isAbsPermissionEnabled = false
@@ CAN 状态/权限回调（4 处同构）
                 status?.let {
                     isAbsEnabled = (it == CAN_STATUS_ENABLED)
+                    val setGray = if (isAbsEnabled) isAbsPermissionEnabled else false
                     updateAbsTcsEnabledState(
-                        isAbsEnabled && isAbsPermissionEnabled,
+                        setGray,
                         isAbsPermissionEnabled,
                         mBinding.rgAbsMode, ...)
```

## 为什么能修复
权限信号注册后，车端权限变化经 callbackPropertyIds→propertyHandlerMap→LiveData 链路送达 Fragment，`isTcsPermissionEnabled`/`isAbsPermissionEnabled` 才能反映真实权限；默认值改 false 保证"信号未到"时按 SRS 语义先置灰而不是误放开。`setGray` 三元化写法逻辑上与原 AND 等价，但把"功能未使能则一律置灰"的意图显式化并补了日志。副作用：若车端从不广播权限信号，开关将一直置灰（从"一直可点"变成"一直灰"），符合安全侧倾向但需联调确认信号确实周期发送。

## 复盘与经验
- 新增依赖车端信号的功能，"信号是否注册进监听列表"是最易遗漏的一步：定义了常量、写了回调，但没进 callbackPropertyIds，整条链路静默失效。
- 布尔权限位默认值应选安全侧（默认禁止/置灰），等真实信号到达再放开，避免信号缺失时功能裸奔。
- 置灰类需求要对照 SRS 画出真值表（功能CAN状态 × 权限位 → UI 态），用表格驱动实现与用例，减少"与 SRS 定义不符"类返工。
