# SIR-XXX · 移除无用副蓝牙设置

- **提交**：`4bf0fa86` | 2026-08-26 | dufan | Setting | feature（清理无用代码）
- **关联单**：SIR-XXX（占位单号）

## 需求/目标
删除设置应用里已废弃的"副蓝牙 AVRCP 控制"监听代码——`SettingVehicleService` 中通过 `Settings.Global` ContentObserver 驱动 `BtAnwManager.setAvrcpControl` 的整段逻辑。

## 实现结构
仅改动 `application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt`（-17 行）：删除内部类 `GlobalSettingsObserver`（ContentObserver，onChange 时读 Global 设置并转发 `setAvrcpControl(value)`）。配合 7 分钟后的 `cd685d82`（库侧把 setAvrcpControl 置为 Deprecated 空实现），上下两层同批拆除副蓝牙通道。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
-class GlobalSettingsObserver(
-    handler: Handler,
-    private val context: Context,
-    private val key: String
-) : ContentObserver(handler) {
-
-    override fun onChange(selfChange: Boolean, uri: Uri?) {
-        super.onChange(selfChange, uri)
-        val value = Settings.Global.getInt(
-            context.contentResolver,
-            key, 2
-        )
-        Log.d("SettingsObserver", "$key changed -> $value")
-        BtAnwManager.getInstance().setAvrcpControl(value)
-    }
-}
```
实现讲解：纯死代码清除。被删链路（Global 设置变化 → AVRCP 控制）依赖库方法已废弃为空实现，保留 observer 只会白耗 ContentObserver 注册并误导阅读。删除时序上库先空实现、应用后摘除调用，属安全顺序。

## 复盘与要点
- 与 `cd685d82` 演示了"废弃 API 两步拆除法"：先库内置空（兼容存量调用方），再逐应用清理调用点，最后删 API。
- SIR-XXX 是占位单号，说明内部需求单管理不严格，追溯时只能靠 Change-Id。
