# SIR-8756 · 未知来电时 HUD 悬浮窗未显示完整号码

- **提交**：`4ba7df8e` | 2026-09-18 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 解决方案 · 域 HUD

## 问题
蓝牙电话未知来电（无联系人姓名、显示原始号码）时，HUD 悬浮窗上的号码显示不全。

## 根因分析
HUD 来电悬浮窗布局 `float_hud_window.xml` 根容器 `ConstraintLayout` 宽度固定为 `@dimen/dp_148`（148dp），其中号码 `TextView tv_user` 为 `match_parent + singleLine=true + ellipsize="marquee"`、字号 24sp。完整电话号码（无姓名可替代显示时）超过 148dp 可容纳宽度后被截断。缺陷库与提交信息一致：HUD 显示尺寸设计发生变更（改版后窗口应更宽），旧布局未同步，属于"UI 规格变更未落地"类缺陷，不涉及逻辑代码。

## 关键代码修改
改动文件：application/BTPhone/src/main/res/layout/float_hud_window.xml（+1/-1）
```diff
--- application/BTPhone/src/main/res/layout/float_hud_window.xml
 <androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
     xmlns:app="http://schemas.android.com/apk/res-auto"
-    android:layout_width="@dimen/dp_148"
+    android:layout_width="@dimen/dp_164"
     android:layout_height="@dimen/dp_144"
```
（`dp_164` 为 `application/BTPhone/src/main/res/values/dimens.xml` 中既有 dimen，无新增资源。）

## 为什么能修复
窗口加宽 16dp 后，`tv_user`（match_parent）可用宽度随之增加，24sp 字号下完整号码可在一行内放下；高度 144dp 与接挂按钮、时长、提示行的纵向约束链未动，其他内容不受影响。隐患在于这是"按最宽内容定死窗口宽"的解法，若号码带国际区号等超长格式仍可能触发 marquee 滚动，但不再静默截断首屏内容。

## 复盘与经验
- HUD/浮窗类"显示不全"优先查根容器固定宽高与内容实际宽度的关系；`singleLine + ellipsize` 只决定截断方式，不保证信息完整，来电号码这类关键信息应以完整显示为先。
- UI 规格变更（尺寸改版）必须以资源清单方式同步到所有引用点，`@dimen/dp_xxx` 语义化命名的集中 dimen 文件让本次修改收敛为一行，是好的工程习惯。
