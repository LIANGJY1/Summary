# 无单号 · Vlog 多页面设置 10dp 顶部间距（附带清理无用布局）

- **提交**：`0e84c7c1` | 2026-08-06 | daizhecheng | Vlog | bugfix（UI 调整类）
- **缺陷库**：未关联单号

## 问题
Vlog 应用各页面内容顶到屏幕上边缘，不符合 UI 稿要求的 10dp 顶部间距；另有两份已废弃布局文件残留。

## 根因分析
纯 UI 样式问题，与 BTMusic 的 `1b758453` 同批同因（`[why]UI要求`）：`activity_home.xml` 的根触摸布局 `touch_layout` 完全没有顶部间距；`camera_pair_activity.xml` 原有 `layout_marginTop=@dimen/dp60`，间距规格调整后需要变为 70dp（60+10，顶部多让出 10dp）。同时 `activity_preview.xml`、`fragment_camera.xml` 两份无人引用的旧布局被整体删除，属于顺带的资源清理。

## 关键代码修改
改动文件：`application/Vlog/src/main/res/layout/activity_home.xml`、`application/Vlog/src/main/res/layout/activity_preview.xml`（删除）、`application/Vlog/src/main/res/layout/camera_pair_activity.xml`、`application/Vlog/src/main/res/layout/fragment_camera.xml`（删除）
```diff
// application/Vlog/src/main/res/layout/activity_home.xml
         android:id="@+id/touch_layout"
         android:layout_width="match_parent"
         android:layout_height="match_parent"
+        android:layout_marginTop="@dimen/dp_10"
         android:clickable="true"
         android:focusable="true">
```
```diff
// application/Vlog/src/main/res/layout/camera_pair_activity.xml
-        android:layout_marginTop="@dimen/dp60"
+        android:layout_marginTop="@dimen/dp70"
```

## 为什么能修复
`activity_home.xml` 直接给根布局加 10dp 上边距使内容整体下移；配对页在原 60dp 基础上加到 70dp 满足新 UI 规格。删除的 `activity_preview.xml`/`fragment_camera.xml` 是旧相机预览实现残留，清理后避免维护歧义。无逻辑改动；唯一注意点是两个页面用了 `marginTop`（BTMusic 用 `paddingTop`），手段不统一，若背景需要延伸到顶部则 marginTop 才是对的，需按视觉稿确认。

## 复盘与经验
- 同一 UI 规范（10dp 顶部间距）落地时应确认每个页面用 padding 还是 margin，取决于背景是否需要顶满屏幕，不能机械复制。
- 顶部间距规格变化时，用 `dp_10` 这类维度资源做增量，比逐页手调魔法数字可靠。
- 顺带清理无引用布局可减小包体与 lint 噪音，但要在提交信息中说明，避免他人误以为有行为变化。
