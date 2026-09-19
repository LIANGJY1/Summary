# YD-392741 · "进行控制"字体被挡住与UI不符（重复提交，diff 与 YD-392742 提交完全相同）

- **提交**：`b6771acc` | 2026-06-27 | daizhecheng | Vlog | 特殊类型：重复提交（与 `5b443500` diff 逐字节一致，已用 `git show` 对比确认）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
标题为 YD-392741"进行控制"字体被挡住，但 diff 内容与 40 分钟前的 `5b443500`（YD-392742 ""搜索中"缓存，状态与UI不符"）**完全相同**：同一批文件、同样的增删行数（8 files, +88/-8）。同一作者在同一天先后以两个单号提交了同一份改动。

## 根因分析
经 `diff <(git show 5b443500 --format=) <(git show b6771acc --format=)` 校验两者 patch 一致，仅提交信息（单号、标题、模块标注）不同。真实情况是：YD-392741（"进行控制"字体被挡）的实际修复在 `98763f37`（重排 `activity_home.xml`）；而本提交只是把"连接中…"文案 + 按钮底色 + loading 图标那套 YD-392742 的修复**再次提交**并打上了 YD-392741 的标签。属于工单与提交的错配（串单），不是独立修复。

## 关键代码修改
改动文件（与 `5b443500.md` 完全一致）：application/Vlog/src/main/java/com/yadea/vlog/main/ui/CameraPairedActivity.kt、res/drawable/btn_bg_radio18_circle.xml、res/drawable/ic_black_loading.xml、res/drawable/ic_white_loading.xml、res/layout/activity_home.xml、res/layout/camera_pair_activity.xml、res/values/strings.xml、res/values-en/strings.xml

```diff
（与 5b443500 相同的核心 hunk）
--- application/Vlog/src/main/java/.../CameraPairedActivity.kt
-                    R.drawable.btn_bg_radio18_blue
+                    R.drawable.btn_bg_radio18_circle
--- application/Vlog/src/main/res/values/strings.xml
-    <string name="btn_search_device">搜索中</string>
+    <string name="btn_search_device">连接中…</string>
```

## 为什么能修复
对 YD-392741（首页文案遮挡）**没有修复作用**——改动全部集中在配对按钮状态展示，与首页布局无关。该单真正修复见 `98763f37`。本提交的价值仅在于说明流程问题：串单提交会让缺陷库与代码历史的映射失真，复盘时须以 diff 内容归因，而非提交标题。

## 复盘与经验
- **提交标题与 diff 必须互证**：本例标题说"字体被挡住"，diff 却是按钮底色/文案，两者风马牛不相及，review 一眼可拦截。
- **同一 diff 两个单号 = 流程信号**：多半是开发者在两个工单间复用了未拆分的暂存区（`git add` 全量提交），应按工单拆分 stage 或使用 `git add -p`。
- **复盘统计要按 patch 内容去重**：否则"28 个 bugfix"里会混入同一修复的两次计数。
