# SIR-6167 · 控制中心热点未打开时热点图标高亮

- **提交**：`53eb8607` | 2026-08-23 | liujinfeng | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
控制中心（快捷面板/状态栏）在热点实际未开启的情况下，热点图标仍然显示为高亮（打开）状态。

## 根因分析
热点客户端数量回调 `onHotspotClientsChanged`（`SystemSettingsControllerService` 实现 `IWxBluetoothListener`）在收到连接设备数 `clients.size` 变化时，向 UI 回调 `updateHotspot(...)` 传递的第一个参数（热点开关状态）被硬编码为 `true`。也就是说，只要发生一次设备数回调，无论热点真实开关状态如何，都会把热点"标记为打开"。缺陷库根因为"控制中心热点状态判断逻辑问题"——开发者当时默认"有回调即热点开启"，忽略了热点关闭后仍可能触发数量回调的场景，属于把"事件发生了"误当成"状态为真"的典型错误。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt（2 处）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt
@@ 热点客户端数量回调内（updateHotspot 调用处，共两处）
                 uiHandler.post(Runnable {
                     mStatusBarUICallback!!.updateHotspot(
-                        true,
+                        hotspotState,
                         clients.size
                     )
                 })
                 uiHandler.post(Runnable {
                     mQuickSettingUICallback!!.updateHotspot(
-                        true,
+                        hotspotState,
                         clients.size,
                         ""
                     )
```

## 为什么能修复
将硬编码的 `true` 替换为类内维护的真实热点状态变量 `hotspotState`，使状态栏与快捷面板的图标状态始终由实际开关状态驱动，客户端数量回调只负责更新连接数，不再反向"点亮"图标。副作用很小：需确保 `hotspotState` 在热点开关变化时被正确同步更新，否则回调时可能传递过期状态；本提交未改动该变量的维护逻辑，依赖既有开关监听路径。

## 复盘与经验
- 回调参数里不要硬编码状态值：事件回调往往只代表"数据有变化"，不代表某个布尔状态为真，必须回查真实状态源。
- "默认给 true"这类写法在 UI 状态同步中极易造成高亮残留类 bug，且必现、影响主交互观感。
- 同一份状态要喂给多个 UI（StatusBar / QuickSetting）时，应收敛为一个统一的状态查询入口，避免多处硬编码漂移。
