# 无单号 · Vlog 跳转系统设置自定义页面

- **提交**：`5cc782fa` | 2026-07-01 | daizhecheng | Vlog | feature
- **关联单**：无

## 需求/目标
相机（Vlog）配对失败页的"去设置"按钮从调试用占位行为改为真实跳转：显式 Intent 拉起 Setting 应用主界面并携带 key/type 附加参数直达自定义设置页。

## 实现结构
单文件改动 `CameraPairedActivity.kt`（+33/-2）：
- 按钮 onClick 中占位代码 `mVlogCarService.sendVehicleProperty(ENERGY_EEM_CHARGING_POWER, 10)`（发一个充电功率属性做测试用）注释掉，替换为 `openSettings()`。
- 新增 `openSettings()`：先 `packageManager.getPackageInfo(SETTING_PACKAGE, 0)` 探测 Setting 是否安装，再构造 `ComponentName("com.yadea.setting", "com.yadea.setting.ui.activity.MainActivity")` 显式 Intent，putExtra `key="custom_setting"`、`type="ClickDialog"`，加 `FLAG_ACTIVITY_NEW_TASK` 后 startActivity；`NameNotFoundException` 捕获打日志。
- companion object 集中定义包名/类名/extra 常量。

数据流：Vlog 按钮 → 显式 Intent（extra 协议：key=custom_setting, type=ClickDialog）→ Setting MainActivity 解析 extra 路由到自定义设置页。

## 关键代码
```diff
# application/Vlog/src/main/java/com/yadea/vlog/main/ui/CameraPairedActivity.kt
         mBinding.btnToSetting.setOnFastClickListener {
-            mVlogCarService.sendVehicleProperty(CarPropertyIds.ENERGY_EEM_CHARGING_POWER, 10)
+            openSettings()
         }
...
+    private fun openSettings() {
+        try {
+            val info = packageManager.getPackageInfo(SETTING_PACKAGE, 0)
+            if (info == null) { LogUtils.i(TAG, "Setting app is not installed"); return }
+            val intent = Intent().setComponent(ComponentName(SETTING_PACKAGE, SETTING_CLASS_MAIN))
+            intent.putExtra(KEY_JUMP, JUMP_KEY_CUSTOM_SETTING)
+            intent.putExtra(JUMP_TYPE, JUMP_VALUE)
+            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
+            startActivity(intent)
+        } catch (e: PackageManager.NameNotFoundException) {
+            LogUtils.e(TAG, "Setting app is not installed: $SETTING_PACKAGE")
+        }
+    }
```

实现讲解：车机多应用间的页面级跳转普遍用"显式 ComponentName + 约定 extra"的私有协议；本提交示例了完整防御链——先查安装、再 try-catch 兜底、NEW_TASK 处理非 Activity 上下文。此前的 `sendVehicleProperty(ENERGY_EEM_CHARGING_POWER, 10)` 是拿车控信号当按钮反馈的临时调试手段，正式化后被注释保留。

## 复盘与要点
- 可复用手法：跨 App 跳转协议应像本提交一样把 extra key/value 收敛为常量（KEY_JUMP/JUMP_KEY_CUSTOM_SETTING），且两端共同维护；更进一步完善可抽成 `SettingRouter.jumpToCustom(context)` 工具供多应用复用。
- 遗留风险：显式 ComponentName 硬编码类名，Setting 重构包名即断链且编译期无感知；且 `getPackageInfo != null` 与 catch NameNotFound 重复防御（前者已足够），`info` 判空分支实际不可达。
- 注释掉的车控调试代码建议删除而非保留，避免后来者误以为有业务含义。
