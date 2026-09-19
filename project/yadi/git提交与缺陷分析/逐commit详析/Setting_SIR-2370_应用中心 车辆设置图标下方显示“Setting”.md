# SIR-2370 · 应用中心车辆设置图标下方显示"Setting"字样
- **提交**：`9e7e797c` | 2026-07-10 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
应用中心里"车辆设置"应用图标下方显示英文名"Setting"，而非"车辆设置"。

## 根因分析
`application/Setting/src/main/res/values/strings.xml`（默认 locale 资源）中 `<string name="app_name">Setting</string>` 一直保留着工程模板默认值，从未替换为产品文言。应用中心读取的正是该 `app_name`，于是展示英文占位名。属于典型的"默认资源文案未随本地化清单核销"问题，与缺陷库记录"设置文言不对→修改文言"完全一致。

## 关键代码修改
改动文件：application/Setting/src/main/res/values/strings.xml（+30/-30，实质改动 1 行，其余为缩进对齐）
```diff
--- application/Setting/src/main/res/values/strings.xml
-<resources >
-    <string name="app_name">Setting</string>
+<resources>
+    <string name="app_name">车辆设置</string>
```
（其余 58 行为该文件内注释/条目缩进的纯格式化调整，无文案变化）

## 为什么能修复
`app_name` 是应用中心展示名的唯一来源，改为"车辆设置"后图标下方文案即刻正确。附带把 `<resources >` 的多余空格与全文缩进规整，无行为影响。风险：若其他多语言 values-xx 目录未同步维护该键，切语言后可能再次露出英文——本次 diff 未涉及多语言目录。

## 复盘与经验
- **`app_name` 是最容易被遗忘的模板占位符**：工程脚手架生成的默认值要在提测清单里专项核对，它直接暴露给用户。
- **默认资源（values）是所有语言的兜底**：默认目录里放英文占位而产品只出中文，等于把兜底变成了展示面。
- **顺手格式化要控制范围**：本提交 60 行改动里仅 1 行是修复，其余为缩进——建议拆分纯格式化提交，避免污染追溯。
