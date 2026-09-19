# SIR-6311 · 无媒体数据时 Dock 音乐卡片仍高亮
- **提交**：`869cbdcd` | 2026-08-31 | caohongliang | BTPhone（实际改动在 SystemUI） | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
蓝牙已连接且当前音源为蓝牙音乐、但没有媒体数据（无封皮/无曲目）时，Dock 栏音乐卡片的封皮、按钮使用的是高亮可用状态与错误缺省图，而不是置灰禁用状态。

## 根因分析
`NavBarFragment.updateMediaControlState()` 原逻辑把"两种不可用"混为一谈：入口条件是 `mCurrentMediaEntry == null || unavailable`，即"无媒体数据"和"音源未连接"都走 `setNoMediaSourceState`；但该方法签名为 `setNoMediaSourceState(boolean unavailable, View...)`，内部 `view.setAlpha(unavailable ? 0.3f : 1.0f)`——只有 `unavailable`（蓝牙未连接或手机互联未连接）才置灰。于是"蓝牙已连接 + 音源为蓝牙音乐 + 无媒体数据"的组合下 `unavailable==false`，按钮 alpha 恢复 1.0 高亮，视觉上像有音乐可播，封皮缺省图也停留在亮色态。"无音源"与"无媒体数据"是两个不同状态，处理却只区分了前者。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
```diff
--- a/.../navbar/ui/NavBarFragment.java
@@ updateMediaControlState
         if (mCurrentMediaEntry == null || unavailable) {
-            setNoMediaSourceState(unavailable, previousBtn, playBtn, next);
+            setNoMediaSourceState(previousBtn, playBtn, next);
         } else {
             setViewEnabled(mPreviousActionIndex >= 0, previousBtn);
@@
-    private void setNoMediaSourceState(boolean unavailable, View...  views){
+    private void setNoMediaSourceState(View... views) {
         for (View view : views) {
             view.setEnabled(true);
-            view.setAlpha(unavailable ? 0.3f : 1.0f);
+            view.setAlpha(0.3f);
         }
     }
```
（同提交还顺带在 `displayState` observer 中补了 `setBackGround(displayState != 0 && displayState != 2 && displayState != 3)` 的背景切换调用。）

## 为什么能修复
只要进入"无媒体条目或音源不可用"分支，就无条件置灰（alpha 0.3），不再依赖连接状态反推视觉态；`mCurrentMediaEntry == null` 这一原先漏掉的路径被统一覆盖，卡片与按钮呈现一致的禁用观感。副作用很小：真有媒体数据时仍走 else 分支按 action 可用性恢复；潜在注意点是 `setEnabled(true)` 保留点击能力，仅靠 alpha 表达状态，若产品要求"无媒体时不可点"还需配合点击守卫。

## 复盘与经验
- "状态 A（无连接）"与"状态 B（有连接无数据）"经常被一个布尔参数折叠处理，参数化 `alpha(unavailable ? ...)` 的写法让第二个状态悄悄落到 else 分支——拆开显式处理更安全。
- UI 控件状态应从"数据是否存在"直接推导，而不是从"连接状态"间接推断，二者不是一一映射。
- 方法名 `setNoMediaSourceState` 语义与实际行为（无媒体数据 + 无音源两态）不符时，重构签名本身就是修复的一部分。
