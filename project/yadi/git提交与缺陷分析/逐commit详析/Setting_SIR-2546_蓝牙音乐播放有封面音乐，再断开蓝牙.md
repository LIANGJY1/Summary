# SIR-2546 · 断开蓝牙后音乐页仍保留封面背景色
- **提交**：`41bb766b` | 2026-07-13 | daizhecheng | BTMusic | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
蓝牙音乐播放有封面的歌曲后断开蓝牙，界面未恢复初始白色背景，仍显示带封面时提取的背景色。

## 根因分析
`MainActivity` 的连接状态回调里，断开分支（`mIsConnected=false`）只做了停服务（`ServiceUtils.stopService(MediaForegroundService)`），没有复位任何封面相关 UI：`ivPic` 仍显示上一首歌的封面图，`clContent` 背景仍是根据封面动态提取的颜色，`BluetoothPlayerService.mBtMusicModel.mAlbumArtUri` 也残留旧值。下次/断开状态下页面展示的是"有封面会话"的遗留视觉状态。根因是断开事件只回收了服务资源，漏掉了 UI 与数据模型的状态清理——典型的生命周期清理不完整。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt（+5/-1）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt
@@ 连接状态回调断开分支
             if (it.mIsConnected.value == true) {
                 startService()
             } else {
+                mBinding!!.ivPic.setImageResource(R.drawable.def_img)
+                mBinding!!.clContent.setBackgroundResource(R.drawable.bg_main)
+                mBluetoothPlayerService.mBtMusicModel.mAlbumArtUri.postValue(null)
                 ServiceUtils.stopService(
                     Intent(
                         applicationContext, MediaForegroundService::class.java
```

## 为什么能修复
断开时三步复位：封面 ImageView 置回 `def_img`、内容区背景置回默认 `bg_main`、`mAlbumArtUri` postValue(null) 清掉数据源，UI 与数据模型同步回到初始态，遗留背景色消失。`postValue(null)` 保证订阅封面变化的观察者也能收到清空事件，避免下游（如通知栏/歌词组件）继续持有旧封面。隐患：`mBinding!!` 非空断言在视图已销毁的极端时序下有 NPE 风险；若断开瞬间恰在动画中，背景突变无过渡。

## 复盘与经验
- **断开/销毁路径要与初始化路径镜像**：有封面→提取背景色的每一步，断开时都要有对应的逆操作；建议建立"进入态/退出态"清单成对 review。
- **清 UI 同时清数据源**：只复位控件不清 `LiveData`，观察者侧（服务、通知）会继续渲染旧数据，复活已"清除"的状态。
- **连接类功能的"断开态"常被当成不可能出现的展示态**，实测断开后页面仍长期可见，必须按独立状态设计。
