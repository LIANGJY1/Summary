# 无单号 · 黑夜模式下充电限制 SeekBar 背景色提交

- **提交**：`d1ed4d73` | 2026-09-16 | liqingqing | EnergyManagement | bugfix/UI
- **缺陷库**：未关联单号

## 问题
黑夜模式下能量中心的充电限制 SeekBar（`ChargeLimitSeekBar` 样式）背景显示发暗/发灰，与 UI 稿不符。

## 根因分析
Seek/进度条类控件在 Android 上默认带 `disabledAlpha`（系统默认约 0.5）的半透明处理，能量中心此前没有 `values-night` 资源目录，黑夜模式下沿用白天的样式，进度槽被 alpha 减半渲染，在深色背景上呈现为一块发灰的"背景色错误"。修复新增 `values-night/style.xml`，对 `ChargeLimitSeekBar` 样式覆写 `android:disabledAlpha` 为 1.0，黑夜模式下进度条不再被降透明度。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/values-night/style.xml（新增）
```diff
--- /dev/null
+++ application/EnergyManagement/src/main/res/values-night/style.xml
@@ 新增夜间资源
+<?xml version="1.0" encoding="utf-8"?>
+<resources>
+    <style name="ChargeLimitSeekBar">
+        <item name="android:disabledAlpha">1.0</item>
+    </style>
+</resources>
```

## 为什么能修复
`disabledAlpha=1.0` 使黑夜模式下该 SeekBar 不做半透明衰减，背景/轨道以全不透明度渲染，视觉上恢复 UI 稿颜色。夜间资源按限定符自动生效，白天模式不受影响；副作用是若该样式还被复用在"需置灰"的场景，置灰视觉强度会减弱，需确认使用范围仅充电限制条。

## 复盘与经验
- 深色模式下控件"发灰"先查 `disabledAlpha`/alpha 叠加，不一定是颜色值配错。
- 夜间适配应走 `values-night` 限定符覆写样式，而不是代码里判日/夜切换颜色——本提交示范了最小改法。
