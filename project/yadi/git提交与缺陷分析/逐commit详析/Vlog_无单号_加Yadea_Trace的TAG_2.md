# 无单号 · 加Yadea_Trace的TAG（Vlog）

- **提交**：`d6736f9b` | 2026-07-15 | daizhecheng | Vlog | feature
- **关联单**：无

## 需求/目标
为 Vlog（行车记录仪）应用补齐 `Yadea_Trace` 启动埋点：Application 启动、HomeActivity 启动与首帧完成（success）两点，纳入全车启动时序日志体系。

## 实现结构
改动 2 个文件、纯新增 9 行：`init/App.kt` 打 `Vlog Application_start`；`main/ui/HomeActivity.kt` 打 `HomeActivity_start`，并新增 `onResume` 覆写打 `HomeActivity_success`（用 onResume 作为"页面可见"的 success 时刻）。

## 关键代码
```diff
--- a/application/Vlog/src/main/java/com/yadea/vlog/main/ui/HomeActivity.kt
+    override fun onResume() {
+        super.onResume()
+        Log.i("Yadea_Trace", "Vlog HomeActivity_success")
+    }
```
实现讲解：与 `998e9edb`（BTMusic）同日同作者的姊妹提交，版式一致；差异在于 success 点的挂法——Vlog 放在 `onResume`，比 onCreate 尾部更接近"真正可交互"，语义更准，但 onResume 每次回前台都会触发，做"启动耗时"统计时需以首次为准（日志平台侧去重或配合 start 点配对）。

## 复盘与要点
- 各应用 success 点位置不一（onResume / onCreate 尾 / 渲染回调），是启动耗时横向对比的最大噪音源，规范应明确唯一锚点（推荐首帧绘制完成 `onWindowFocusChanged` 或 `reportFullyDrawn`）。
- 至此 `Yadea_Trace` 已覆盖 Setting、SystemUI、AccountCenter、BTMusic、Vlog 五个应用（7/8-7/15 共 5 个埋点提交），全车启动时序链路成形；后续新增应用应把埋点纳入提测 checklist。
