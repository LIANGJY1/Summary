# SIR-8063 · 预约充电弹窗 UI 修复"文件漏提"（补交取消按钮背景）

- **提交**：`08541a90` | 2026-09-10 | liqingqing | EnergyManagement | bugfix（SIR-8063 的补交提交）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
前置提交 `e1a8c0ca`（SIR-8063 弹窗 UI 对版）漏提交了取消按钮的新背景资源，导致引用该资源的编译/显示不完整（提交自述"文件漏提"）。

## 根因分析
`e1a8c0ca` 重写了 `dialog_reservation_charging.xml`（取消按钮背景指向新的 `@drawable/reservation_cancel_bg`），但该 drawable 文件未随提交入库，属于"改了引用、漏了资源"的典型半成品提交。本提交补上 `reservation_cancel_bg.xml`：白底、1dp #1A1F222A 描边、12dp 圆角，与同单已提交的确认按钮背景 `reservation_confirm_bg.xml`（12dp 圆角 #545F72）构成新设计稿的按钮对。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/drawable/reservation_cancel_bg.xml（新增，+6）
```diff
--- /dev/null
+++ application/EnergyManagement/src/main/res/drawable/reservation_cancel_bg.xml
+<?xml version="1.0" encoding="utf-8"?>
+<shape xmlns:android="http://schemas.android.com/apk/res/android">
+    <corners android:radius="12dp" />
+    <solid android:color="#FFFFFF" />
+    <stroke android:width="1dp" android:color="#1A1F222A" />
+</shape>
```

## 为什么能修复
资源文件补齐后，布局引用可以解析，取消按钮按新 UI（白底描边圆角）正确渲染，编译告警/资源缺失问题消除。无逻辑改动、无副作用；价值在于记录了"漏提"这一流程事故本身。

## 复盘与经验
- 新增 drawable 引用与资源文件必须同提交入库；提交前用 `git status`/构建校验可 100% 拦截此类漏提。
- 大型 UI 对版改动（6 文件 118+/94-）拆分不当时极易漏件，"一个 UI 单元一个提交"配合提交前编译是最小防线。
- 两个 Change-Id（I026830db 与 I96d815f）混在提交信息里，说明是 gerrit amend 复用导致的痕迹，追溯单号时应以文件内容为准。
