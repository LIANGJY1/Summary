# YD-392987 · 黑夜模式拨号按钮点击变灰、无正确按压反馈

- **提交**：`a9ad472d` | 2026-07-30 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联缺陷库记录（单号 YD-392987，defs 无条目）

## 问题
黑夜模式下点击拨号盘按钮，UI 出现不明的"变灰"效果，而非设计期望的按压态颜色变化（设计上应有明确的点击效果）。

## 根因分析
`DialPadButton` 自定义控件完全接管了 `onTouchEvent()`（ACTION_DOWN 调 `scaleUp()`、ACTION_UP/CANCEL 调 `scaleDown()` 做缩放动效），但**从未调用 `setPressed(true/false)`**——控件背景是 `dial_button_selector.xml`（`state_pressed` 切换到 `bg_dial_button_press`），没有 pressed 状态，selector 永远停在普通态，设计的按压背景从未生效；黑夜模式下用户看到的"变灰"来自其他默认反馈而非设计的按压效果（缺陷描述"实际未变化颜色"即指设计按压色未出现）。此外原 `bg_dial_button_press.xml` 只是一个与普通态同色的 shape（`@color/bg_segmentbutton` + 60dp 圆角），即使 pressed 生效也与普通态无视觉差异。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/view/DialPadButton.java；application/BTPhone/src/main/res/drawable/bg_dial_button_press.xml

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/view/DialPadButton.java
     public boolean onTouchEvent(MotionEvent event) {
         switch (event.getAction()) {
             case MotionEvent.ACTION_DOWN:
+                setPressed(true);
                 scaleUp();
                 break;
             case MotionEvent.ACTION_UP:
             case MotionEvent.ACTION_CANCEL:
+                setPressed(false);
                 scaleDown();
                 break;
```

```diff
--- application/BTPhone/src/main/res/drawable/bg_dial_button_press.xml
-<shape xmlns:android="http://schemas.android.com/apk/res/android"
-    android:shape="rectangle">
-    <solid android:color="@color/bg_segmentbutton" />
-    <corners android:radius="60dp" />
-</shape>
+<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
+    <!-- 底层：和普通状态一样的背景 -->
+    <item>
+        <shape android:shape="rectangle">
+            <solid android:color="@color/bg_segmentbutton" />
+            <corners android:radius="60dp" />
+        </shape>
+    </item>
+    <!-- 顶层：半透明黑色遮罩，让按钮看起来"变暗" -->
+    <item>
+        <shape android:shape="rectangle">
+            <solid android:color="#8CFFFFFF" />
+            <corners android:radius="60dp" />
+        </shape>
+    </item>
+</layer-list>
```

## 为什么能修复
`setPressed(true/false)` 使 `dial_button_selector` 的 `state_pressed` 分支真正生效，按压时切换到新背景；新背景改为 layer-list（普通底色 + `#8CFFFFFF` 覆盖层），按压态与普通态有明确视觉差异，点击反馈清晰。**注意两处如实记录的疑点**：① 代码注释写"半透明黑色遮罩/变暗"，但 `#8CFFFFFF` 是半透明白色，黑夜模式下实际效果是"提亮"而非"变暗"，注释与色值不符（以色值实际渲染为准）；② 白色覆盖在白天模式会让按钮"变白"，昼间观感需验收。`sos_button_selector` 也引用同一按压资源，SOS 按钮的按压态会同步变化。

## 复盘与经验
- **自定义 onTouchEvent 必须维护 pressed 状态**：覆写 `onTouchEvent` 而不调用 `super` 时，框架默认的按压逻辑被绕过，selector 的 `state_pressed` 全部失效——要么手动 `setPressed`，要么在 super 之上叠加逻辑。
- **按压态背景必须与普通态有可感知差异**：本例旧按压 drawable 与普通态完全同色，是"没有点击效果"的直接原因；layer-list"同底色+遮罩"是保持形状统一又体现状态的最小改动方案。
- **注释与色值要一致**："黑色遮罩"配 `#FFFFFF` 色值， EITHER 注释错 EITHER 色值错，都会误导下一个改色的人；遮罩意图（变暗/变亮）应写明目标观感。
- **共用 drawable 的按压改动影响所有引用 selector**（本例波及 SOS 按钮），改前应 grep 引用面。
