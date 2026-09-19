# SIR-7213 · 黑夜模式充电上限弹窗背景仍为白色（背景 drawable 漏夜间版）

- **提交**：`fb8572d3` | 2026-09-02 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C（提交标注 B）· 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
同一单号 SIR-7213 的后续补丁：黑夜模式打开充电上限弹窗，文字已适配但弹窗整体背景仍是白天的浅色圆角矩形。

## 根因分析
弹窗背景引用的 shape 资源 `drawable/dialog_smal.xml` 只存在日间版本（`solid #EAEDF2` 浅灰白）。夜间模式匹配资源时，`drawable-night/` 目录下没有同名文件，回落到默认 `drawable/` 的浅色背景，于是弹窗底板呈白天样式。这是典型的"资源只做了一份限定符版本"漏配——文字颜色在 `3d3d7a15` 已 token 化，但背景 drawable 被遗漏，属于同一缺陷的第二段修复。

## 关键代码修改
改动文件：drawable-night/dialog_smal.xml（新增，二进制无关、纯 XML）
```diff
// application/EnergyManagement/src/main/res/drawable-night/dialog_smal.xml（新增）
+<?xml version="1.0" encoding="utf-8"?>
+<shape xmlns:android="http://schemas.android.com/apk/res/android"
+    android:shape="rectangle">
+    <size
+        android:width="660dp"
+        android:height="344dp" />
+    <corners android:radius="30dp" />
+    <solid android:color="#222325" />
+</shape>
```

## 为什么能修复
新增 `drawable-night/dialog_smal.xml`（深色 #222325）后，夜间模式资源查找命中同名夜间版，弹窗底板随主题切换为深色；日间仍走原 `drawable/dialog_smal.xml`，互不影响。改动仅一个资源文件，零逻辑风险；隐患是尺寸/圆角在两份 shape 中重复维护，改动圆角时需两边同步。

## 复盘与经验
- "白天正常、夜间错"的 UI 缺陷，排查顺序：布局色值 token → style → drawable 是否有 `-night` 版本，三者缺一即漏。
- 弹窗背景常以独立 shape drawable 承载，最容易在适配夜间时被整组遗漏；建立"新增 drawable 必须同步评估 -night"清单可根治。
- 同一单号多次提交（3d3d7a15 修文字、fb8572d3 修背景）说明首轮修复只按缺陷截图修了可见部分，缺少对该弹窗全部视觉元素的系统性核对。
