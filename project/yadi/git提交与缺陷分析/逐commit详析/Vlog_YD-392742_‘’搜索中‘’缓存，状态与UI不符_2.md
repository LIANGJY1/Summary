# YD-392742 · ""搜索中"缓存，状态与UI不符（重复提交，diff 与 YD-392741 提交完全相同）

- **提交**：`90941406` | 2026-06-27 | daizhecheng | Vlog | 特殊类型：重复提交（与 `98763f37` diff 逐字节一致，已用 `git show` 对比确认）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
标题为 YD-392742（""搜索中"缓存，状态与UI不符"），但 diff 内容（`build.gradle` 引入 NsrCommonUI.aar、新增 main_bg2/main_group/start_main 三张 PNG、重排 `activity_home.xml`、新增 `main_start_vlog*` 三条字符串）与 2 小时前的 `98763f37`（YD-392741"进行控制"字体被挡住）**完全相同**，而与 YD-392742 的真实修复（`5b443500`：连接中文案、按钮底色、loading 图标）无关。

## 根因分析
经 `diff` 校验两者 patch 一致、仅提交信息不同：这是 98763f37 的重复提交，且单号又串成了 YD-392742。至此本批 4 个 Vlog UI 提交形成"交叉串单"：YD-392741 的修复被提交了两次（`98763f37` 正确标注 + `b6771acc` 错标为 YD-392741 的 5b443500 内容），YD-392742 的修复也被提交了两次（`5b443500` 正确 + 本提交错标）。推测是开发者本地分支 cherry-pick/合并后未去重，或两次 `git add -A` 全量提交把彼此的工作区改动互相带入。

## 关键代码修改
改动文件（与 `98763f37.md` 完全一致）：application/Vlog/build.gradle、res/drawable-mdpi/main_bg2.png、res/drawable-mdpi/main_group.png、res/drawable-mdpi/start_main.png（均为二进制图片新增）、res/drawable/btn_up_p.xml、res/layout/activity_home.xml、res/values/strings.xml、res/values-en/strings.xml

```diff
（与 98763f37 相同的核心 hunk）
--- application/Vlog/src/main/res/values/strings.xml
+    <string name="main_start_vlog">开始旅拍生活</string>
+    <string name="main_start_vlog2">支持连接 Insta 360  运动相机，可进行控制、回放等功能</string>
+    <string name="main_start_vlog3">相机需先开机，并在车辆附近。</string>
```

## 为什么能修复
对 YD-392742（按钮状态文案/底色）没有额外修复作用，属于把首页布局改版重复入库。由于两次提交内容完全一致，第二次合并时 Git 通常按"无变化"处理，不会产生代码回退，但会让缺陷库-提交映射出现一对多混乱。YD-392742 的真正修复见 `5b443500`。

## 复盘与经验
- **串单+重复是这个团队 UI 修复期的系统性问题**：同一天 4 个提交两两成对重复、单号交叉错标，说明缺少"提交前 diff 与单号核对"的卡点。
- **全量 `git add -A` 是串单根源**：一次提交只应包含一个工单的 stage；`git status`/`git diff --cached` 核对是最低成本防线。
- **工具化校验**：可用 `git log --format=%H --grep=<单号>` 与 patch-id 去重脚本在 CI 里检测重复 patch 与错标单号。
