# 无单号 · 移除无用代码资源（SystemUI 大扫除）

- **提交**：`e3b8d098` | 2026-07-15 | dufan | SystemUI | feature
- **关联单**：无

## 需求/目标
**提交类型：资源/死代码清理（超大粒度）**。对 SystemUI 做资源大扫除：1098 个文件、+4/-2249 行，其中 966 个 .png、128 个 .xml、少量 .kt/.java——删除旧车型遗留的 DVR 图标组、启动图、失效布局与对应的状态栏图标代码。

## 实现结构
- 资源（约 967 个二进制/布局）：`drawable-mdpi` 下 `app_dvr_n_*`（DVR 仪表图标）、`app_page_panel_*`、`ic_launcher.webp`、`main_bg2`、`layout_volume_brightness_control.xml`（172 行）等整批删除。
- 代码（3 个文件，-172 行）：
  - `PhoneStatusBarPolicy.java`：删除 `mSlotHotspot/mSlotBluetooth/mSlotWifi` 等字段与其 `setIcon` 注册（141 行）；
  - `StatusBarActor.kt`：删除对应槽位初始化（18 行）；
  - `StatusBarFragment.java`：引用清理（13 行）；`text_styles.xml` 删 9 行样式。

数据流：无业务新增；状态栏图标槽位注册减少后，`StatusBarIconController` 不再为已删图标预留 slot。

## 关键代码
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/statusbar/icon/PhoneStatusBarPolicy.java（节选）
-    private final String mSlotHotspot;
-    private final String mSlotBluetooth;
-    private final String mSlotWifi;
-        mIconController.setIcon(mSlotHotspot, "home_top_link", ...)
-        mIconController.setIcon(mSlotBluetooth, "home_top_bt_on", ...)
```
实现讲解：先删引用方（Actor/Policy/Fragment），再批量删资源——由于 png 数量巨大（966 张），必须以"代码不再引用"为前提一次性脚本化清理，否则任何一处 `R.drawable.app_dvr_n_fa_night` 残留都会编译失败，编译器即验收集。

## 复盘与要点
- 千级文件的资源清理提交，验证手段就是"能编译 + 状态栏回归"：`R` 类强引用让漏网引用在编译期暴露，这是 Android 资源清理比 Java 死代码清理更安全的根本原因。
- 注意区分 `getIdentifier()` 动态引用：状态栏 slot 字符串若由配置下发（`status_bar_hotspot` 之类 string 资源），删除 string 前要确认没有反射/动态拼名调用。
- 该提交与 `7f7ae494`、`78942903`、`df2db5c6` 构成 7 月中旬的"瘦身周"，团队用四个提交完成全应用死资源清理——批量+按模块推进是合理的组织方式。
