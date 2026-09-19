# SIR-8109 · 通知中心天气与时间信息距离略近

- **提交**：`05a103c5` | 2026-09-11 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
通知中心里天气信息行与上方时间信息行垂直距离过近，与设计稿不一致。

## 根因分析
`notification_center.xml` 中天气容器（`layout_height="@dimen/dp_42"`、位于 `@id/tv_notification_date` 之下）的 `layout_marginTop` 被写成 `@dimen/dp_167`，比设计默认间距大了 30dp。缺陷库根因"ui尺寸设计不统一、按照24小时制UI来调整"表明：该布局此前为适配 24 小时制时间样式单独调过间距，与其它（默认）样式没有统一，导致当前样式下天气行被推得离时间行过近（表现为 167 這一超出常规的值）。

## 关键代码修改
改动文件：`application/SystemUI/src/main/res/layout/notification_center.xml`
```diff
         android:layout_height="@dimen/dp_42"
         android:layout_below="@id/tv_notification_date"
         android:orientation="horizontal"
-        android:layout_marginTop="@dimen/dp_167"
+        android:layout_marginTop="@dimen/dp_137"
```

## 为什么能修复
`marginTop` 从 167dp 恢复为 137dp，天气行与时间行的垂直间距回到设计默认值，消除"略近"的视觉问题。一行布局数值调整，无逻辑副作用；隐患仅在于如果 24 小时制与 12 小时制确实需要不同间距，应使用两套布局/尺寸资源区分，而不是共用了为特例调整的值。

## 复盘与经验
- 同一布局被多种显示形态（12h/24h 制）复用时，为特例硬调的 margin 会污染默认样式；应抽取 dimen 资源或用多布局文件按形态区分。
- "距离略近/略远"类像素级缺陷，评审时对照设计稿标注值逐项核对 margin 即可低成本拦截。
- dimen 值用 `@dimen/dp_137` 这类原子化引用便于全局统一调整，但注意引用值本身是否有语义。
