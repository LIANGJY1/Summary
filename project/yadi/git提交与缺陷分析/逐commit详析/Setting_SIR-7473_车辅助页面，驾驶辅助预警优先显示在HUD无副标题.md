# SIR-7473 · 车辅助页"驾驶辅助预警优先显示在HUD"副标题文案缺"预警"二字
- **提交**：`233b8f38` | 2026-09-08 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（rc：需求变更 → 修改文本）

## 问题
车辅助页面中，主标题"驾驶辅助预警优先显示在HUD"与其副标题文案不一致：副标题写作"驾驶辅助优先显示在HUD，HUD关闭时，恢复至仪表显示"，少了"预警"二字，用户无法确认副标题描述的就是该开关。

## 根因分析
`values/strings.xml` 中 `assist_hud_priority`（主标题）与 `assist_hud_priority_sub`（副标题）是两个独立字符串资源。主标题已按需求写为"驾驶辅助**预警**优先显示在HUD"，但副标题仍是旧文案"驾驶辅助优先显示在HUD…"，需求变更（预警功能命名调整）只改了主标题、漏改了副标题，属典型的多字符串资源同步遗漏。

## 关键代码修改
改动文件：application/Setting/src/main/res/values/strings.xml（1 行）
```diff
--- application/Setting/src/main/res/values/strings.xml
@@ -92,7 +92,7 @@
     <string name="assist_hud_priority">驾驶辅助预警优先显示在HUD</string>
-    <string name="assist_hud_priority_sub">驾驶辅助优先显示在HUD，HUD关闭时，恢复至仪表显示</string>
+    <string name="assist_hud_priority_sub">驾驶辅助预警优先显示在HUD，HUD关闭时，恢复至仪表显示</string>
```

## 为什么能修复
副标题补上"预警"后与主标题 `assist_hud_priority` 对齐，语义一致，用户不再误读。纯文案改动无任何逻辑风险；唯一需要留意的是 values-en 等多语言目录的对应英文串是否也要同步加入"warning"语义（本提交未涉及，需多语言走查确认）。

## 复盘经验
- "主标题 + 副标题"成对出现的文案，需求变更时应把这对资源当原子组修改，评审时并排查看一眼。
- 功能更名（如"驾驶辅助"→"驾驶辅助预警"）要用资源全局搜索，把所有含旧名的 string（含多语言目录）一次改齐。
- 文案类缺陷等级低（C/D）但必现、直接影响用户对功能边界的理解，走查工具比对 UI 稿文案是最经济的拦截手段。
