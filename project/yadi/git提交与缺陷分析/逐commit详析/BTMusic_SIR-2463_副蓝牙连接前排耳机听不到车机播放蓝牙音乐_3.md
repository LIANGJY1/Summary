# SIR-2463 · 移除 Setting 侧冗余的 AVRCP 全局 Settings 监听
- **提交**：`ccf1b551` | 2026-07-16 | daizhecheng | BTMusic | bugfix（配套清理）
- **缺陷库**：等级 A · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
同 SIR-2463（副蓝牙耳机听不到蓝牙音乐）。本提交是 `d392bb3b` 的后续清理：BTMusic 已改为直调 `BtAnwManager`，Setting 侧旧的 `custom_avrcp_event` 监听转发链路成为冗余代码。

## 根因分析
注：缺陷库根因为底层 dsp/tinymix 问题，与 diff 实际内容（应用层代码清理）不符，以 diff 为准。`SettingVehicleService` 中 `initObserver()` 注册了 `GlobalSettingsObserver` 监听 `Settings.Global` 的 `custom_avrcp_event` key，收到变更后由 Setting 代为转发 AVRCP 控制。`d392bb3b` 已让 BTMusic 直接调用协议栈接口，这条监听链路不再有写入来源，留着不仅无用，还可能与新链路竞争处理同一控制意图。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt`

```diff
--- application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
-    private var avrcpEventObserver: GlobalSettingsObserver? = null
     ...
         initL2A()
-        initObserver()
     ...
-    fun initObserver() {
-        avrcpEventObserver = GlobalSettingsObserver(uiHandler, this, mAvrcpEvent)
-        avrcpEventObserver?.let {
-            contentResolver.registerContentObserver(
-                Settings.Global.getUriFor(mAvrcpEvent),
-                false,
-                it
-            )
-        }
-    }
```
（另同步删除 `onRelease` 中对应的 `avrcpEventObserver` 注销与常量 `mAvrcpEvent`。）

## 为什么能修复
移除旧的中转监听，保证 AVRCP 控制意图只有 BTMusic 直调这一条通路，避免双通道竞争；属于前一修复的收尾，防止遗留 observer 在收到历史 key 写入时误触发控制命令。

## 复盘与经验
- 切换通信通道时，旧通道的"生产者+消费者"要成对清理，只改生产端会留下可被误触发的僵尸监听。
- 重构后的"移除冗余代码"单独成提交（8 分钟后跟进），便于回滚与审阅，是值得借鉴的提交拆分习惯。
