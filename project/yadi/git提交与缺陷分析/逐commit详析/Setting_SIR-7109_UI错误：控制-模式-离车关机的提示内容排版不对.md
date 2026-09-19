# SIR-7109 · 离车关机提示内容排版不对

- **提交**：`d91ba696` | 2026-09-03 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（注：缺陷库 rc"信号乌龙"/sol"改回原来信号"与本 diff 完全不符，以 diff 实际为准）

## 问题
控制-模式-离车关机的"问号"提示弹窗文案排版不对：长段文字居中显示、行距过密，且文案内容与 UI 稿不一致。

## 根因分析
该提示复用 `SentinelDialogSmall`，其内容 TextView 在 `dialog_sentinel_small.xml` 中默认 `gravity="center_horizontal"` 且未设行距——长段落文案居中排布观感差（对齐问题与 `f252ed71` 中给该组件加 `contentGravity` 参数的动机一致，但此处调用方未传参，走默认居中）；同时 `parkShutdown_tips` 文案本身与 UI 稿不符（多余"时间=设置值"字样、半角标点）。提交 [why] "SRS文本错误"、[how] "按照UI修改"。

## 关键代码修改
改动文件：SceneModeFragment.kt、dialog_sentinel_small.xml、values/strings.xml
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt
             ivParkShutdownTips.setOnClickListener {
-                SentinelDialogSmall(title = getString(R.string.parking_shutdown), content = getString(R.string.parkShutdown_tips)).show(...)
+                SentinelDialogSmall(title = getString(R.string.parking_shutdown), content = getString(R.string.parkShutdown_tips), Gravity.START).show(...)
```
```diff
// application/Setting/src/main/res/layout/dialog_sentinel_small.xml
         android:gravity="center_horizontal"
         android:layout_marginTop="@dimen/dp_24"
+        android:lineSpacingExtra="8sp"
```
```diff
// application/Setting/src/main/res/values/strings.xml
-    <string name="parkShutdown_tips">开启后,系统检测到脚撑放下且人离开座椅时,会启动您设定的倒计时(时间=设置值，3~300秒),倒计时结束后自动关机。…可随时关闭此功能</string>
+    <string name="parkShutdown_tips">开启后，系统检测到脚撑放下且人离开座椅时，会启动您没定的倒计时（3~300秒），倒计时结束后自动关机。…可随时关闭此功能</string>
```

## 为什么能修复
调用点传 `Gravity.START` 使长文案左对齐（复用 f252ed71 给 `SentinelDialogSmall` 新增的 `contentGravity` 能力），`lineSpacingExtra=8sp` 拉开行距，文案按稿精简并改全角标点，排版恢复设计。**但本次改动引入了新问题**：新文案把"您设定的倒计时"误写成"您没定的倒计时"（错别字，真实存在于 diff 中）；且 strings.xml 中 `parkShutdown_tips` 的 `</string>` 与下一条 `slip_mode_switch_open_fail_tip` 被挤在同一行，格式破坏虽不影响编译，却暴露修改粗糙。另外全局 `dialog_sentinel_small.xml` 加行距会影响所有共用该布局的提示弹窗（湿滑模式、极致续航等），需回归确认。

## 复盘与经验
- 文案修改必须逐字与 UI 稿核对——本例修排版却带入错别字，"修 A 引入 B"在纯文案提交里同样会发生。
- 改共享布局属性（lineSpacing）前先列全使用方（本布局被多个 Dialog 复用），评估外溢影响。
- 缺陷库的 rc/sol 与实际 diff 不符时（本单"信号乌龙"云云），应以 diff 为准并在复盘记录中纠正，否则缺陷库会污染后续根因统计。
