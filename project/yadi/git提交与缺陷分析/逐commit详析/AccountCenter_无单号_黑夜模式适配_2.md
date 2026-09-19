# 无单号 · 黑夜模式适配（回退 346f3de1 的令牌化方案）

- **提交**：`b1bf5cfd` | 2026-07-15 | hedeyuan | AccountCenter | feature（实为同日回退提交）
- **关联单**：无

## 问题（回退性质说明）
经逐行比对，本提交与 5 小时前的 `346f3de1`（cherry-pick 自 c073ecd2，经 `cherry-pick-c073ecd2` 分支合入 main）改动完全互逆：78 行改动一一对应、符号相反。效果是撤回该次"黑夜模式令牌化适配"——`configChanges` 重新加回 `uiMode`、布局/drawable 恢复硬编码色值、删除新增的 `more.xml` 矢量图。

## 为什么回退（根因推断）
两提交同名"黑夜模式适配"、同一作者，却方向相反，说明令牌化方案在 main 分支不成立：`346f3de1` 移除了多个 Activity `configChanges` 中的 `uiMode`，昼夜切换会触发 Activity 重建，账号中心的登录表单/弹窗状态（`LoginDialogActivity` 为 `singleInstance` 供第三方拉起）可能因此丢状态或闪屏；也可能该适配本属另一车型分支（c073ecd2 所在分支），被误 cherry-pick 到 main。[inferred：提交信息未写回退原因，以上由 diff 逆关系与组件特性推断]

## 关键代码修改（回退内容示例）
```diff
--- a/application/AccountCenter/src/main/AndroidManifest.xml
-            android:configChanges="screenLayout|screenSize|smallestScreenSize|orientation|locale|layoutDirection|touchscreen"
+            android:configChanges="screenLayout|screenSize|smallestScreenSize|orientation|uiMode|locale|layoutDirection|touchscreen"
```
```diff
--- a/application/AccountCenter/src/main/res/layout/dialog_qr_code_login.xml（节选）
-                    android:textColor="@color/text_default_default"
+                    android:textColor="#20232B"
```

## 为什么能修复（效果）
精确逆向保证回退后 main 分支的 AccountCenter 与适配前二进制等价，消除重建换肤带来的行为风险；title 未用 `Revert` 字样而是沿用"黑夜模式适配"，从追溯角度是败笔（依赖 diff 比对才能确认性质）。

## 复盘与经验
- cherry-pick 跨分支特性前必须确认目标分支的组件形态一致：`configChanges` 组合是每个应用自己定的生命周期契约，直接搬运会踩中重建语义差异。
- 回退提交应使用 `git revert` 保留原提交引用（或至少在 message 写明"回退 <hash>，原因 X"），同名同作者的"反向同名提交"会让日志考古成本翻倍——本案例即需 patch-id 比对才能定性。
- 账号中心后续真正落地黑夜模式（`346f3de1` 的令牌化思路本身是好的）时，应保留 uiMode 在 configChanges 中改走 `applyOverrideConfiguration`/手动刷新，或确认重建无状态丢失后再摘。
