# 无单号 · 修改 Launcher 关键日志等级便于统计
- **提交**：`472e3f10` | 2026-09-01 | caohongliang | Launcher | 日志等级调整（非功能 bugfix）
- **缺陷库**：未关联单号

## 问题
无用户可见问题。Launcher 两条关键生命周期/状态日志原为 debug 级别，线上按 info 以上采集时统计不到。

## 根因分析
属于日志治理改动：`Myapplication.onCreate` 的 "Myapplication onCreate" 与 `MainActivity.initView` 的 "Main_currentNightMode"（日夜模式）均用 `LogUtils.d`，在线上日志采集/统计口径中被过滤，无法用于启动链路与模式分布分析。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/Myapplication.kt`、`.../function/main/view/MainActivity.kt`
```diff
--- .../com/yadea/launcher/Myapplication.kt
-        LogUtils.d(TAG, "Myapplication onCreate")
+        LogUtils.i(TAG, "Myapplication onCreate")

--- .../function/main/view/MainActivity.kt
-        LogUtils.d(
+        LogUtils.i(
             TAG,
             "Main_currentNightMode = " + ...
```

## 为什么能修复
debug → info 一字改动，使两条关键日志进入 info 及以上采集范围，支撑后续统计。无功能影响。

## 复盘与经验
- 关键生命周期、关键状态（如日夜模式）日志应显式提升级别，并保持文案稳定可 grep，这是无单号维护类提交的典型形态。
- 提交标题用 SIR-XXXXX 占位说明团队允许无单号工程改动，但应在提交信息里写清动机（本条仅"方便后续统计"，信息偏少）。
