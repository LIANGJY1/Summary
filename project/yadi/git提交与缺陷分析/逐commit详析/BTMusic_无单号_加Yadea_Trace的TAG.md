# 无单号 · 加Yadea_Trace的TAG（BTMusic）

- **提交**：`998e9edb` | 2026-07-15 | daizhecheng | BTMusic | feature
- **关联单**：无

## 需求/目标
为蓝牙音乐应用补齐 `Yadea_Trace` 启动埋点：Application 启动、MainActivity 启动与首帧完成（success）两点，纳入全车 `Yadea_Trace` 启动时序体系。

## 实现结构
改动 2 个文件、纯新增 5 行：`App.kt` 的 Application 入口打 `BTMusic Application_start`；`MainActivity.kt` 打 `MainActivity_start` 与 `MainActivity_success` 两点。注意 TAG 用 `Log.i("Yadea_Trace", ...)` 裸字符串 + `android.util.Log`（未走 `Constants`/`LogUtils` 收口）。

## 关键代码
```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt
+        Log.i("Yadea_Trace", "BTMusic MainActivity_start")
+        Log.i("Yadea_Trace", "BTMusic MainActivity_success")
```
实现讲解：start/success 成对、命名 `<应用> <组件>_<阶段>`，与 `88ebe5e9` 的 Setting 版式一致；但实现上退回了裸字符串硬编码，说明"常量收口"规范尚未传导到所有模块。

## 复盘与要点
- `success` 点放在 Activity 渲染就绪处（而非 onCreate 结尾）才有耗时意义，埋点位置的语义要在团队层面统一，否则各应用 success 不可横向比较。
- 建议用 lint/CodeStyle 模板固化 `Yadea_Trace` 常量引用，避免同一天内 `88ebe5e9`（Constants 收口）与本提交（裸字符串）两种风格并存。
