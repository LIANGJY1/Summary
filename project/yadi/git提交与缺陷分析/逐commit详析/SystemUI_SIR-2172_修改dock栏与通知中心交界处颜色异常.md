# SIR-2172 · dock 栏与通知中心交界处颜色异常

- **提交**：`d024a177` | 2026-07-08 | duanlonglong | SystemUI | bugfix（一行参数修正）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
通知中心（下拉快捷面板）与底部 dock 栏交界处出现颜色异常（重叠区域的渲染缝隙/色差）。

## 根因分析
缺陷库 rc："通知中心和导航栏重叠了一部分导致"。`QuickSettingActor.initView()` 创建通知中心窗口时把高度参数写死为 `QuickSettingWindowManager(mContext, 720)`，而底部 dock/导航栏区域高度按 700 规划——通知中心窗口比可用区域多出 20dp，**直接压到 dock 栏上沿**，两个窗口在该条带内叠加绘制（各自带背景色），交界处出现颜色异常。这是典型的"两个独立窗口的高度约定没有共享常量"导致的硬编码漂移。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/actor/QuickSettingActor.kt`（1 行）
```diff
// --- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/actor/QuickSettingActor.kt
-        mWindowManager = QuickSettingWindowManager(mContext, 720)
+        mWindowManager = QuickSettingWindowManager(mContext, 700)
```

## 为什么能修复
通知中心高度缩小 20dp 后不再侵入 dock 栏区域，叠绘条带消失，交界颜色恢复正常。方案与 rc 一致（"将通知中心高度缩小 20dp，显示在导航栏上方"）。隐患：700/720 仍是魔法数字，dock 高度若再调整（多分辨率/多形态）又会错位；两个窗口的高度契约应提取共享 dimens 资源或由布局系统统一测量。

## 复盘与经验
- **多窗口拼接布局的高度必须单一事实来源**：dock 高度与通知中心高度分别硬编码在不同类里，是窗口类 UI 错位的常见根源，宜用共享资源/常量约束。
- **窗口叠绘类"颜色异常"先算高度账**：SystemUI 中状态栏/下拉面板/dock 各自 WindowManager.addView，边界异常第一反应是核对相邻窗口的尺寸参数是否重叠。
- 一行修复也应有 UI 走查覆盖窗口交界场景（全拉起/半拉起/动画中间态）。
