# SIR-8453 · 蓝牙配对弹窗灰色背景比底部设置界面高出一截

- **提交**：`bde4be68` | 2026-09-16 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 待测试验证 · 域 车控车设

## 问题
手机蓝牙连接触发配对弹窗时，弹窗的灰色半透明背景顶到状态栏之上，比底部的蓝牙设置界面高出一截，视觉穿帮。

## 根因分析
配对弹窗布局 `activity_require_pair.xml` 的根布局 `root`（背景为 `bg_dialog_dim` 灰色遮罩）是 `match_parent` 全屏且没有任何顶部偏移，而其下方真正的蓝牙设置界面存在状态栏/顶部栏高度。弹窗 Activity 覆盖在上层时遮罩从屏幕 0,0 开始铺满，下层的设置界面却从顶部栏之下开始，于是灰色遮罩在顶部多出一截。修复按 how 说明给根布局补 `layout_marginTop=dp_10`，与顶部栏对齐（该项目的对话框层统一预留 10dp 顶部间距）。

## 关键代码修改
改动文件：application/Setting/src/main/res/layout/activity_require_pair.xml
```diff
--- application/Setting/src/main/res/layout/activity_require_pair.xml
         android:id="@+id/root"
         android:layout_width="match_parent"
         android:layout_height="match_parent"
+        android:layout_marginTop="@dimen/dp_10"
         android:background="@drawable/bg_dialog_dim">
```

## 为什么能修复
遮罩下移 10dp 后与下层设置界面的可视顶部对齐，"高出一截"的错位消失。属纯视觉修正：弹窗内容、点击热区逻辑不变；需回归确认 10dp 数值在不同分辨率/状态栏隐藏场景下是否仍与其他弹窗对齐。风险点：若其他配对类弹窗（`activity_require_pair` 之外的 dialog）存在同样问题，本提交未覆盖，应横向排查同模式布局。

## 复盘与经验
- 弹窗遮罩类 Activity 与其底层界面的顶部基准（状态栏高度、透明状态栏 flags）必须一致，否则必出"背景高一截/矮一截"。
- 对话框层顶部间距最好抽成公共 style/dimen，逐个布局手写 margin 会出现本单这类遗漏。
