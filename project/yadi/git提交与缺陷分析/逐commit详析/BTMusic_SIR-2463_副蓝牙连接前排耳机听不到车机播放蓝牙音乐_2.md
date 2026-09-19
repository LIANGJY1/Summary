# SIR-2463 · AVRCP 控制改经 Settings.Global 中转（同单第二次提交）
- **提交**：`aa9f9d8d` | 2026-07-16 | daizhecheng | BTMusic | bugfix（架构修正）
- **缺陷库**：等级 A · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（与 4f5cb42f 同单）

## 问题
同 SIR-2463：副蓝牙连接前排耳机听不到车机蓝牙音乐。前一提交 `4f5cb42f` 让 BTMusic 直接调用 `BtAnwManager.setAvrcpControl`，本次改为经 `Settings.Global` 键 `custom_avrcp_event` 中转，由 Setting 进程的观察者真正执行 AVRCP 控制。

## 根因分析
`4f5cb42f` 的直连方案存在跨进程陷阱：`BtAnwManager` 是单例，其 `mPairedDevices` 等状态在**Setting 应用进程**中初始化和维护；BTMusic 作为另一个进程，编译期虽然能依赖 `component:Hardwarelibs` 通过，运行期拿到的是自己进程里一份全新的、配对列表为空的 `BtAnwManager` 实例——`setAvrcpControl` 遍历空列表，一个 AVRCP 命令都发不出去。本次提交撤掉 BTMusic 对 Hardwarelibs 的依赖（build.gradle -1 行），改为 BTMusic 写 `Settings.Global`，Setting 侧 `SettingVehicleService` 用 `ContentObserver` 监听该键并调用本进程的 `BtAnwManager.setAvrcpControl(value)`，让命令在持有真实蓝牙状态的进程里执行。

## 关键代码修改
改动文件：application/BTMusic/build.gradle（-1）、application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt（+2）、application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt（+2/-2）、application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt（+42/-1）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
-            BtAnwManager.getInstance().setAvrcpControl(if (isPlaying) 0 else 2)
+            LogUtils.d(tag, "setAvrcpControl->$isPlaying")
+            SettingsUtils.setGSetting("custom_avrcp_event", if (isPlaying) 0 else 2)
--- application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt（断开分支补发暂停控制）
+                SettingsUtils.setGSetting("custom_avrcp_event", 2)
--- application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
+    class GlobalSettingsObserver(handler: Handler, private val context: Context, private val key: String)
+        : ContentObserver(handler) {
+        override fun onChange(selfChange: Boolean, uri: Uri?) {
+            val value = Settings.Global.getInt(context.contentResolver, key, 2)
+            BtAnwManager.getInstance().setAvrcpControl(value)
+        }
+    }
+    fun initObserver() {
+        avrcpEventObserver = GlobalSettingsObserver(uiHandler, this, mAvrcpEvent)
+        contentResolver.registerContentObserver(Settings.Global.getUriFor(mAvrcpEvent), false, avrcpEventObserver!!)
+    }
```
（onRelease 中对应 `unregisterContentObserver`）

## 为什么能修复
命令执行点从"空状态的单例副本"搬回"持有配对列表的真实单例"，AVRCP 控制字真正到达前排设备；Settings.Global 作为进程间解耦总线，BTMusic 不再需要依赖 Hardwarelibs。断开蓝牙时 MainActivity 补写 `custom_avrcp_event=2`，保证停播/断开状态也下发。隐患：Settings.Global 是全局命名空间，键名 `custom_avrcp_event` 无归属前缀，易冲突；ContentObserver 默认值 2（暂停）意味着监听建立前写入的值会丢失，冷启动时序下首条命令可能被吞。

## 复盘与经验
- **单例不跨进程**：`getInstance()` 返回的"全局实例"只在本进程全局。跨进程调用要么走 AIDL/广播，要么像本例用系统设置/属性做信箱；编译能过不代表运行正确。
- **"修好了"要验证命令真的到达对端**：上一提交若在真机上核对过 AVRCP 日志（对端 mac 为空），当场就能发现空列表问题，不必返工一轮。
- **观察者注册/反注册成对**：本提交在 initObserver/onRelease 中正确配对，可作为模板。
