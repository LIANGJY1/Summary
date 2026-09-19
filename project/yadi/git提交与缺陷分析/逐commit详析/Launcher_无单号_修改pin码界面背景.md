# 无单号 · [SIR-XXX] 修改 pin 码界面背景
- **提交**：`184cea17` | 2026-09-14 | dufan | Launcher | feature（UI 样式调整）
- **关联单**：无（SIR-XXX 占位单号）

## 需求/目标
手车互联 PIN 码界面（LinkActivity）背景由"顶部 36dp 圆角"改为纯色直角，对齐新一轮 UI 规范（与 `c474cf9e` 弹窗直角化同批次的视觉统一动作）。

## 实现结构
- `function/link/LinkActivity.kt`：`onConfigurationChanged` 中 `clContent` 背景引用由 `bg_main_round_top` 改为 `R.color.bg_application`。
- `res/layout/activity_link.xml`：布局静态背景同样改为 `@color/bg_application`，外层容器加 `paddingTop=10dp`。
- `component/CommonTools/res/drawable/bg_main_round_top.xml`：删除该圆角 drawable 资源。

## 关键代码
```xml
<!-- application/Launcher/src/main/res/layout/activity_link.xml -->
 <LinearLayout
     android:id="@+id/cl_content"
     android:layout_width="match_parent"
     android:layout_height="match_parent"
-    android:background="@drawable/bg_main_round_top"
+    android:background="@color/bg_application"
     android:orientation="vertical">
```
（`LinkActivity.kt` 的 `onConfigurationChanged` 内同步替换引用，保证横竖屏/日夜配置切换后背景仍正确。）

实现讲解：三处联动——布局静态值、代码动态重设值、被弃用资源本体——一起改干净，避免"引用悬空"。代码与布局双写背景的界面，改样式时两处必须同步，这是本次改动的核心工作量。

## 复盘与要点
- **类型标注**：纯 UI 资源调整，无逻辑变更。
- **改背景要排查动态重设点**：`onConfigurationChanged` 里 `setBackgroundResource` 会覆盖布局静态值，只改 XML 会在配置切换后回退成旧样式；此类双写点建议全局搜索资源名排查。
- **删除公共资源需谨慎**：`bg_main_round_top` 位于 CommonTools 公共组件，本提交确认无其他引用后整体删除，是资源治理的正确收尾动作。
