# SIR-7647 · 热点关闭仍显示"无连接设备"（cherry-pick 同步）
- **提交**：`ef031938` | 2026-09-07 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设
- **类型**：cherry-pick 提交 —— diff 与 `817ee8d0` 完全相同（`cherry picked from commit 817ee8d0bbc48b58ff44b61f0b0d5a69cda7390e`），把 `HotspotDialogFragment.kt` 3 处隐藏逻辑与 `dialog_connect_child.xml` 默认 gone 同步到另一分支。

改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt、application/Setting/src/main/res/layout/dialog_connect_child.xml

说明：完整机制剖析见 817ee8d0.md。
