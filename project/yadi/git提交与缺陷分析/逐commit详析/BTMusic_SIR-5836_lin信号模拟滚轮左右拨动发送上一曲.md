# SIR-5836 · LIN 滚轮拨动切歌一次跳两首（BTMusic 侧第一次修复）
- **提交**：`9d0c2dd8` | 2026-08-14 | daizhecheng | BTMusic | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
LIN 信号模拟滚轮左右拨动发送上一曲/下一曲时，一次拨动会跳过两首歌。

## 根因分析
缺陷库根因："FWK 和应用都实现了切换"。代码上 BTMusic 存在双通道：通道一是 `MusicCarService` 监听 `VehiclePropertyIds.ROLLER_UP_SWITCH/ROLLER_DOWN_SWITCH` CAN 信号，经 `handleUpSwitch/handleDownSwitch` LiveData 到 `MainActivity` 观察者调用 `setPrevious()/setNext()`；通道二是 FWK 把滚轮事件转成媒体会话命令，走到 `MediaForegroundService` 的 `onSkipToNext/onSkipToPrevious` 回调（同样调用 `setNext()/setPrevious()`，见后续提交 `e036cb01` 所注释的代码）。两条链路各切一次，即"跳两首"。本提交的意图是屏蔽应用侧的车辆属性读写通道。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/service/MusicCarService.kt`
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/service/MusicCarService.kt
                 override fun onLifecycleChanged(isReady: Boolean) {
-                    mIsReady = isReady
+                    mIsReady = false
+                    LogUtils.d(TAG, "Car service initialized successfully 0")
                     if (isReady) {
                         registerPropertyCallbacks(
                             callbackPropertyIds,
                             carPropertyEventCallback,
                             true
                         )
-                        LogUtils.d(TAG, "Car service initialized successfully")
+                        LogUtils.d(TAG, "Car service initialized successfully 1")
                     }
                 }
```

## 为什么能修复
（如实说明：本 diff 的直接效果有限。）`mIsReady` 恒为 false 后，`MusicCarService.sendVehicleProperty()/readVehicleProperty()` 永远直接返回 false/-1，应用的车辆属性写入/读取通道被关死，与元数据"屏蔽应用逻辑"意图一致；但在该提交版本的全部 BTMusic 源码中未检索到这两个方法的调用点，CAN 回调→MainActivity 的切歌路径也未被本提交拦截，因此真正消除"跳两首"的是 4 天后 `e036cb01` 对 `MediaForegroundService.onSkipToNext/onSkipToPrevious` 的移除。本提交可视为同问题修复尝试的一部分/前置铺垫。

## 复盘与经验
- 同一输入（LIN 滚轮）被 FWK 媒体命令与应用 CAN 监听两条链路同时消费，是车机多媒体最常见的"动作翻倍"模式；接入新硬件信号前必须先确认 FWK 是否已有同义实现。
- 用恒 false 的就绪标志"软关闭"一个服务通道是隐晦做法（无调用点时甚至无效果），不如直接删除注册或注释调用链，让意图在 diff 中可见。
- 修复要收敛到一条责任链：本问题的最终方案是"应用只留 CAN 直控路径、媒体会话回调置空"，前后两个提交共同完成，复盘时应合并看待。
