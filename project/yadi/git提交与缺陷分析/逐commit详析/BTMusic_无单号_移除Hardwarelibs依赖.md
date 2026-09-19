# 无单号 · BTMusic 移除 Hardwarelibs 依赖

- **提交**：`8b266ebd` | 2026-08-24 | dufan | BTMusic | feature（依赖治理）
- **关联单**：无

## 需求/目标
蓝牙音乐应用与 Hardwarelibs（AnW 蓝牙扩展库 `com.anwExt.carui.bt.anwBt.BtAnwManager`）解耦：删除模块依赖与全部调用点（AVRCP 控制上报、初始化预加载）。

## 实现结构
4 个文件、1 增 8 删：`build.gradle` 移除 `implementation project(':component:Hardwarelibs')`；`App.kt` 删除启动时 `BtAnwManager.getInstance()` 预初始化；`MainActivity.kt` 删除停止播放时 `setAvrcpControl(2)`（暂停上报）；`MediaForegroundService.kt` 删除播放状态观察里的 `setAvrcpControl(if (isPlaying) 0 else 2)`。

## 关键代码
```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
             LogUtils.d(tag, "mIsPlay setAvrcpControl->$isPlaying")
-            BtAnwManager.getInstance().setAvrcpControl(if (isPlaying) 0 else 2)
```
```diff
--- a/application/BTMusic/build.gradle
     implementation project(':component:Carlib')
-    implementation project(':component:Hardwarelibs')
```
实现讲解：BTMusic 对 BtAnwManager 的使用仅两处 AVRCP 播放状态上报 + 一处启动预载，删除后功能面收窄到系统蓝牙 API（Carlib/framework_bluetooth）本身。与同日 `b5135b54`（BTPhone 同款移除）构成一次统一的依赖治理，推测目的是砍掉 AnW 扩展库的传递依赖或规避其副作用。

## 复盘与要点
- 删除 `setAvrcpControl` 上报会改变仪表/多媒体卡片对蓝牙音乐播放态的感知来源，需确认 Carlib 侧已接管该职责，否则出现状态不同步。
- "App 启动时 Manager.getInstance() 预载"这类隐式初始化散落各应用，移除时要全局搜索调用点（本提交即 4 文件联动），漏一处即 NoClassDefFoundError。
