# SIR-8150 · 白天切黑夜模式后播放状态图标与应用不同步

- **提交**：`ca2acd16` | 2026-09-11 | dufan | BTMusic | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
白天模式处于播放状态时切换到黑夜模式，多媒体卡片/蓝牙音乐应用内播放/暂停按钮显示状态与真实播放状态不一致（按钮状态显示不对）。

## 根因分析
`WidgetMusicPlayControl` 在换肤（昼夜模式切换）刷新方法里，对播放/暂停按钮执行了 `ivPlayAndPause.setImageResource(R.drawable.music_play_selector)` 强制重置。该控件同时用于多媒体卡片与应用内控制条，昼夜切换时这条重置会把按钮图标无条件刷回默认（播放态）资源，覆盖掉由真实播放状态驱动的图标——正在播放的会话被显示成"可播放"，与其它入口的播放状态产生分歧。前后、下一首按钮的重置无状态语义问题，唯独播放/暂停按钮是双态控件，不能在换肤时盲目重置。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/view/WidgetMusicPlayControl.kt`
```diff
         mBinding.tvTotalTime.setTextColor(resources.getColor(R.color.text_default_default, null))
         mBinding.tvProgressTime.setTextColor(resources.getColor(R.color.text_default_default, null))
         mBinding.ivPre.setImageResource(R.drawable.music_previous_selector)
-        mBinding.ivPlayAndPause.setImageResource(R.drawable.music_play_selector)
         mBinding.ivNext.setImageResource(R.drawable.music_next_selector)
```

## 为什么能修复
移除换肤路径中对双态按钮的强制图标重置后，播放/暂停图标完全由播放状态回调驱动，昼夜切换只更新颜色等皮肤属性，不再篡改状态图标，卡片与应用状态保持一致。上一首/下一首为单态按钮，保留重置无副作用。隐患：若播放/暂停图标本应随昼夜切换配色，需确认 selector 资源内部已按皮肤适配，否则可能出现配色不随肤的情况。

## 复盘与经验
- 换肤/主题切换的刷新方法里，只能重置"单态"视觉资源；播放/暂停、勾选等双态控件的状态图标必须由状态源统一驱动，禁止在通用刷新里硬编码。
- 同一控件被卡片（widget）与主界面复用时，任何一处强刷状态都会造成跨入口状态分叉。
- "资源加载错误"类缺陷，审 diff 时重点看 `setImageResource/setBackgroundResource` 出现在哪些路径、是否有状态语义。
