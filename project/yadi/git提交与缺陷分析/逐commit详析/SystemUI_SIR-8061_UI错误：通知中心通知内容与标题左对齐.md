# SIR-8061 · 通知中心通知内容与标题未左对齐

- **提交**：`37368cf9` | 2026-09-10 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（提交头标影响等级 D，以缺陷库 C 为准）

## 问题
通知中心里通知正文与标题左边线不齐（内容相对标题有肉眼可见的横向偏移），与 UI 设计稿不符。

## 根因分析
`car_notification_body_view.xml` 中正文 TextView 用 `android:layout_alignStart="@id/notification_body_title"` 与标题左对齐，但 `alignStart` 对齐的是两个视图的边界（padding 之外），标题 TextView 自带的字体侧边距（font padding/字形留白）会让标题的**可见字形**相对视图边界右移约几像素，正文却没有这层偏移，于是视觉上错开（提交 [why] 边距错误）。修复即给正文补 `layout_marginStart=dp_4` 抵消标题的字形内缩，使可见文字对齐。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/layout/car_notification_body_view.xml（核心）；另有 notification_center.xml、car_notification_actions_view.xml、vector_notify_center_bg.xml、dimens.xml 等 UI 对版调整（5 文件 +15/-30）
```diff
--- application/SystemUI/src/main/res/layout/car_notification_body_view.xml
         android:layout_width="wrap_content"
         android:layout_height="wrap_content"
         android:layout_alignStart="@id/notification_body_title"
+        android:layout_marginStart="@dimen/dp_4"
         android:layout_below="@id/notification_body_title"
         android:layout_marginTop="@dimen/notification_body_content_top_margin"
```
（同提交顺带对版：通知卡片背景由 720x120 矢量改为 `shape` + 18dp 圆角；动作按钮从 `layout_weight=1` 均分改为 wrap_content + min/max 宽（新增 action_button_max_width=404dp）、圆角 18→12dp；notification_center 标题/日期去掉硬编码 MiSans 与 alpha 0.6）

## 为什么能修复
4dp 的 marginStart 精确补偿标题字形左侧留白，正文与标题的可见左边缘重合，对齐问题消除。其余改动属于同一次通知中心 UI 对版（按钮宽度策略、圆角、字体族），与本缺陷无因果但同源。风险：4dp 是按当前字号/字体人工校准的值，若标题字号或字体变更，对齐量需重调——这是"视觉对齐像素补偿"的固有脆弱性。

## 复盘与经验
- `layout_alignStart` 对齐视图边界而非可见字形，TextView 的 font padding 会造成 2-6dp 视觉偏差；跨视图文字对齐要么用统一容器 padding，要么显式补偿 margin 并注明依据。
- 通知模板（car_notification_body_view 等）是全局共享模板，改边距影响所有通知，验收要多条真实通知验证。
- 同提交混入大量无关对版改动（背景、按钮、字体）会稀释缺陷修复的可读性，建议缺陷修复与 UI 对版分开提交。
