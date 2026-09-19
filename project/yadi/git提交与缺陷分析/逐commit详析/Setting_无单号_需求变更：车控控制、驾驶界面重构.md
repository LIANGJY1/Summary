# 无单号 · 需求变更：车控控制、驾驶界面重构（补漏文件）

- **提交**：`91d9d21a` | 2026-08-07 | sgh | Setting | feature（同一需求的补提交）
- **关联单**：无（Change-Id I164a540e 与 fb2398df 完全相同）

## 需求/目标
补齐 `fb2398df`（同 Change-Id、同分钟提交）遗漏的两个文件：驾驶容器页布局 `fragment_driving_container.xml` 与新增 Tab 文案字符串。

## 实现结构
2 个文件、+54：新增 `fragment_driving_container.xml`（TabLayout + 分割线 + ViewPager2 的容器页骨架，tabMode=scrollable、复用 ControlTabTextAppearance 样式）；`values/strings.xml` 补 5 个 tab 文案（座椅&手把、灯光、安全监控、模式、驾驶操控、行车辅助等）。

## 关键代码
```diff
--- /dev/null  (application/Setting/src/main/res/layout/fragment_driving_container.xml)
+        <com.google.android.material.tabs.TabLayout
+            android:id="@+id/tab_layout"
+            ...
+            app:tabIndicator="@drawable/tab_indicator_img"
+            app:tabIndicatorColor="@android:color/transparent"
+            app:tabMode="scrollable" ... />
+
+        <View style="@style/SettingDividerStyle2" />
+
+        <androidx.viewpager2.widget.ViewPager2
+            android:id="@+id/view_pager" ... />
```
实现讲解：同一 Change-Id 下的文件遗漏补交——`fb2398df` 引用了 `R.layout.fragment_driving_container` 与 `tab_driving_control` 等资源，缺了即编译失败，本提交在 2 分钟内补上（实际是拆分/漏 add）。布局本身即容器页标准骨架。

## 复盘与要点
- 提交前 `git status` 全量核查 + 编译验证可避免此类补漏；同一 Change-Id 多提交需在合入前确认成组。
- 布局中 tabPadding 30dp 等写死值与代码侧指示器 60x3dp 是配套设计稿参数，改动需两处联动。
