# SIR-7917 · 天气已授权但通知中心不显示天气
- **提交**：`d47ca09e` | 2026-09-14 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 主交互

## 问题
天气 App 已授权，通知中心却不显示天气信息；偶发于页面进出之后，连接被"清空"。

## 根因分析
天气服务连接器 `WeatherServiceConnector` 的生命周期原来由 `NotificationCenterFragment` 管理：`onCreateView` 里 `WeatherServiceConnector.init(requireContext())`，`onDestroyView` 里 `WeatherServiceConnector.release()`。问题有两层：其一，`onDestroyView` 一执行就把整个连接器 release 掉，而连接器是跨页面共享的单例，通知中心视图销毁（如通知栏收起、页面切换）后服务连接被拆掉，再次进入时若 init 时序或绑定状态不对，天气数据就拉不回来——即提交信息所说的"天气服务被清空了"；其二，传入的是 `requireContext()`（Activity 级 Context），页面重建后旧 Context 失效，绑定在旧 Context 上的服务连接同样失效。生命周期归属错配：共享单例被最短生命周期的视图持有。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/SystemUIApplication.kt；application/SystemUI/src/main/java/com/android/systemui/dropdownbar/notification/ui/NotificationCenterFragment.kt
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/SystemUIApplication.kt
+++ b/application/SystemUI/src/main/java/com/android/systemui/SystemUIApplication.kt
@@ -91,6 +92,8 @@
         LogUtils.e(TAG, "onCreate:### country=$country, lang=$language")
 
         registerSignal()
+        // 初始化天气服务
+        WeatherServiceConnector.init(this)
     }
@@ -269,7 +272,8 @@
     override fun onTerminate() {
         super.onTerminate()
-        // 注销can报文组状态监听
+        // 注销天气服务
+        WeatherServiceConnector.release()
     }
```
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/dropdownbar/notification/ui/NotificationCenterFragment.kt
+++ b/application/SystemUI/src/main/java/com/android/systemui/dropdownbar/notification/ui/NotificationCenterFragment.kt
@@ -148,7 +148,6 @@
         WeatherServiceConnector.addListener(checkNotNull(weatherListener))
-        WeatherServiceConnector.init(requireContext())
         mWeatherData = WeatherServiceConnector.getWeatherData()
     }
@@ -417,7 +416,6 @@
         weatherListener?.let { WeatherServiceConnector.removeListener(it) }
         weatherListener = null
-        WeatherServiceConnector.release()
         super.onDestroyView()
     }
```

## 为什么能修复
init/release 上移到 `SystemUIApplication.onCreate` / `onTerminate`，用 Application（进程级）Context 初始化，天气服务连接与进程同寿命；Fragment 只保留 `addListener`/`removeListener` 的观察者注册，视图销毁只摘监听不拆连接，页面任意进出后数据通道始终在，重进时 `getWeatherData()` 能立刻取到缓存数据。副作用：服务连接常驻进程，多一次空闲连接开销，但对车机单进程 SystemUI 可忽略。

## 复盘与经验
- 共享型服务连接器/单例的 init 与 release 必须放在与其实际生命周期等长的层（Application/进程），不能交给最短命的 Fragment View 管理。
- 初始化 Context 优先用 applicationContext，Activity/Fragment Context 传入单例是泄漏与失效双重隐患。
- "偶现不显示 + 进出页面后复现"类问题，优先排查共享组件被视图生命周期误释放。
