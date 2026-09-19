# SIR-7908 · 通知中心通知列表与状态栏重叠显示
- **提交**：`74180547` | 2026-09-09 | caohongliang | SystemUI（BTPhone 场景） | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（根因：边距错误）

## 问题
通知中心下拉后，通知列表顶部与状态栏区域重叠显示，内容顶到状态栏上，视觉错乱。

## 根因分析
`notification_center.xml` 中通知容器（`layout_alignParentRight`、含 `empty_notification_text` 的列表容器）的 `layout_marginTop` 只给了 `@dimen/dp_46`，不足以避开状态栏高度（本 UI 中日期标题区域已调整为 dp_133 高、距顶 dp_98），列表自然上探与状态栏重叠。同时 `CarNotificationView.setNotifications` 以 `setRecyclerViewListHeaderAndFooter=true` 给 RecyclerView 加了 header/footer，header 的占位与布局边距叠加关系错误，进一步加剧首条通知顶入状态栏区域。属布局边距/列表头部双重错误。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/layout/notification_center.xml；application/SystemUI/src/main/java/com/android/systemui/notification/CarNotificationView.java
```diff
--- application/SystemUI/src/main/res/layout/notification_center.xml
@@ -25,7 +25,7 @@
         android:layout_width="wrap_content"
         android:layout_height="@dimen/dp_133"
         android:layout_marginLeft="@dimen/dp_60"
-        android:layout_marginTop="@dimen/dp_81"
+        android:layout_marginTop="@dimen/dp_98"
         android:fontFamily="MiSans"
@@ -248,7 +248,7 @@
         android:layout_height="match_parent"
         android:layout_alignParentRight="true"
         android:paddingBottom="@dimen/dp_20"
-        android:layout_marginTop="@dimen/dp_46"
+        android:layout_marginTop="@dimen/dp_110"
         android:layout_marginEnd="@dimen/dp_30">
```
```diff
--- application/SystemUI/src/main/java/com/android/systemui/notification/CarNotificationView.java
@@ -392,7 +392,7 @@
         mNotifications = notifications;
-        mAdapter.setNotifications(notifications, /* setRecyclerViewListHeaderAndFooter= */ true);
+        mAdapter.setNotifications(notifications, /* setRecyclerViewListHeaderAndFooter= */ false);
         refreshVisibility();
```

## 为什么能修复
通知容器 marginTop 从 dp_46 提到 dp_110，列表起点移到状态栏/日期区之下，不再重叠；日期区 marginTop 同步微调（dp_81→dp_98）保证标题与新列表间距符合 UI 稿。关闭 RecyclerView 的 header/footer 注入（setRecyclerViewListHeaderAndFooter=false）后，首条通知不再被 header 顶到异常位置，滚动定位与边距计算恢复一致。副作用：去掉 header/footer 后若依赖 header 做占位动画/分组头的逻辑需要回归；marginTop 为定值，状态栏高度变化（不同分辨率/定制）时需复核。

## 复盘与经验
- 全屏下拉面板内嵌列表时，列表容器 marginTop 必须 ≥ 状态栏+面板标题区高度，用 dimens 语义化命名（如 statusbar_offset）比裸 dp_46 更可审。
- RecyclerView 的 header/footer 注入会改变 item 定位与边距叠加，开启与否要在布局边距设计时统一考虑，两处独立调整极易出现本例的"叠加错误"。
- 布局重叠类 UI 缺陷用层级视图（Layout Inspector）比对 marginTop 实际生效值，比肉眼猜快得多。
