# 无单号 · 媒体卡片无数据时点击没有提示

- **提交**：`d40342a3` | 2026-08-23 | liujinfeng | SystemUI | bugfix（需求变更类）
- **缺陷库**：未关联单号（提交标记 SIR-XXX，元数据 why="变更"，how="增加提示"，影响等级 D）

## 问题
dock 栏媒体卡片无媒体数据时，点击播放/上一曲/下一曲按键毫无反应，没有任何提示，用户不知道为什么按了没效果。

## 根因分析
这是交互设计缺陷而非逻辑错误：`NavBarFragment.resetMediaView()`（无媒体时的卡片重置路径）之前调用 `setViewEnabled(false, previousBtn/playBtn/next)` 把三个按钮 `setEnabled(false)`，禁用状态的 View 不响应点击，`sendMediaAction()` 里 `mCurrentMediaEntry == null` 的分支只是打了条 `Log.w` 就 return——用户侧表现为"静默失败"。提交说明 why 标注为"变更"：期望无音源时点击给出"当前无音源播放，需要在手机端选择音源播放"的提示。要出提示就必须让按钮可点击，因此把"禁用+置灰"改为"可点+半透明"。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java、application/SystemUI/src/main/res/values/strings.xml、application/SystemUI/src/main/res/values-en/strings_en.xml
```diff
--- application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
@@ sendMediaAction()
     private void sendMediaAction(int actionIndex) {
         if (mCurrentMediaEntry == null) {
             Log.w(TAG, "sendMediaAction: no active media notification");
+            ToastUtils.showCenterToast(requireContext(), requireContext().getString(R.string.no_music_source_hint),
+                    null, null, android.view.Gravity.CENTER, android.view.Gravity.CENTER);
             return;
         }
@@ resetMediaView()
-        setViewEnabled(false, previousBtn);
-        setViewEnabled(false, playBtn);
-        setViewEnabled(false, next);
+        setNoMediaSourceState(previousBtn, playBtn, next);
         playBtn.setImageResource(R.drawable.vector_music_play);
     }
+
+    private void setNoMediaSourceState(View...  views){
+        for (View view : views) {
+            view.setEnabled(true);
+            view.setAlpha(0.3f);
+        }
+    }
```
字符串资源新增 `no_music_source_hint`："当前无音源播放，需要在手机端选择音源播放"（中英文各一条）。

## 为什么能修复
按钮从 `setEnabled(false)` 改为"可点击 + alpha 0.3"：视觉上仍是置灰不可用观感，但点击事件能到达 `sendMediaAction()`，命中 `mCurrentMediaEntry == null` 分支弹出居中 Toast，用户得到明确反馈。副作用：无音源状态下按钮变为可聚焦/可点击，若后续逻辑依赖 `isEnabled()` 判断可用态需注意；另外英文文案在 `strings_en.xml` 中仍是中文内容，存在国际化遗漏。

## 复盘与经验
- "静默失败"是交互大忌：吞掉用户操作至少要给一条提示，`Log.w` 只对开发者可见。
- "禁用按钮"与"置灰观感"可以解耦：用 `alpha` 表达视觉态、用 `enabled` 表达交互态，两者按需求组合。
- 无数据/空态（empty state）的点击行为应作为设计项明确，而不是开发时顺手 disable。
