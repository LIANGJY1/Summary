# SIR-7165 · 来电浮窗取消扬声器后仍有语音播报（移除多余静音按钮）
- **提交**：`67a99d1d` | 2026-09-07 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话（根因：UI 变更 → 修改 UI）

## 问题
来电时点击浮窗上的扬声器/静音按钮取消后，车机依然播放来电语音播报，行为与预期不符。

## 根因分析
`FloatCallWindow` 来电浮窗上挂了一个 `btnSounder`（静音按钮），其点击逻辑自行维护 `isRingtoneEnabled` 布尔态并调用 `UiCallManager.get().muteCallVolume(true/false)` 尝试静音。但提交信息明确写"无 UI 对应的需求"——这套按钮和静音逻辑没有对应的产品需求支撑，`muteCallVolume` 与车机实际铃声/播报通路也不闭环（取消扬声器后播报继续），属于一处"半实现的遗留 UI"。修复策略不是修补静音链路，而是按 UI 变更整体移除：代码里注释掉 `btnSpeaker`/`btnSounder` 的 findViewById 与点击监听，布局 `float_incoming_window.xml` 中把 `btnSounder` 置为 `visibility="gone"`。其余改动（`fragment_contacts.xml` 同步进度条尺寸抽成 `progress_bar_width/height` dimens、`bg_main.xml`/`progress_sync_style.xml` 样式微调）是顺带的 UI 规整，与本缺陷无直接因果。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java、res/layout/float_incoming_window.xml、res/layout/fragment_contacts.xml、res/drawable/bg_main.xml、res/drawable/progress_sync_style.xml、res/values/dimens.xml、res/layout/activity_main.xml
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
@@ -258,29 +258,29 @@
         ivHangup.setOnClickListener(this);
         ivAnswer.setOnClickListener(this);
 
-        btnSpeaker = view.findViewById(R.id.btnSpeaker);
-        btnSpeaker.setOnClickListener(this);
-
-        btnSounder = view.findViewById(R.id.btnSounder);
-        btnSounder.setOnClickListener(v -> {
-            LogUtils.i(TAG, " Speaker switch clicked");
-            UiCallManager uiCallManager1 = UiCallManager.get();
-            if (isRingtoneEnabled) {
-                uiCallManager1.muteCallVolume(true);
-                updateSounderButton(false);
-                isRingtoneEnabled = false;
-            } else {
-                uiCallManager1.muteCallVolume(false);
-                updateSounderButton(true);
-                isRingtoneEnabled = true;
-            }
-        });
+//        btnSpeaker = view.findViewById(R.id.btnSpeaker);
+//        btnSpeaker.setOnClickListener(this);
+//        ...（btnSounder 静音切换逻辑整段注释）
--- application/BTPhone/src/main/res/layout/float_incoming_window.xml
+        <!-- 静音按钮 -->
+        <ImageView
+            android:id="@+id/btnSounder"
+            ...
+            android:visibility="gone"
```

## 为什么能修复
既然静音按钮链路无需求依据且与播报通路不闭环，直接移除入口（隐藏按钮 + 注销监听）后，用户无法再触发这条错误路径，"取消扬声器但仍有播报"的状态不一致现象随入口消失。副作用：若后续需求恢复"来电静音"，需要重新打通 muteCallVolume 与音频通路的完整链路，而不是恢复这段 UI；注释掉的代码块和保留的字段（btnSounder/btnSpeaker/isRingtoneEnabled）属于技术债。

## 复盘经验
- "无需求支撑的遗留 UI"出 bug 时，先评估是补链路还是删入口；没有需求背书的功能修复成本高于删除成本。
- 自维护布尔态（isRingtoneEnabled）+ 直接调底层音量的静音实现，很容易与系统音频策略脱节，做音频类开关必须确认真正生效通路。
- 顺手改动（进度条尺寸 dimens 化）混在 bugfix 提交里，会模糊提交意图，建议拆分提交。
