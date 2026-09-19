# 无单号 · 将 527 修复的 bug 与主分支同步（代码同步类）
- **提交**：`008541b6` | 2026-07-01 | hedeyuan | BTPhone | bugfix（分支同步快照）
- **缺陷库**：未关联单号

## 类型说明：分支代码同步
本提交为"将 527（版本分支）上已修复的 bug 批量合回主分支"的代码同步快照，无独立根因可剖析。改动面约 50 个文件、数百行，横跨 `application/BTPhone` 的 Java 与 res：
- 通话核心：`InCallServiceImpl.java`、`UiCallManager.java`、`CallTimeManager.java`、`FloatCallWindow.java`/`FloatWindowManager.java`（悬浮窗通话）
- 联系人链路：`ContactsFragment.java`、`ContactsViewModel.java`、`ContactRepository.java`、`ContactData.java`、`T9SearchUtils.java`、`StringUtil.java`、`ContactIndexBar.java`（含新增 `LetterTipBubble.java` 字母提示气泡）
- 通话记录/收藏：`CallLogFragment.java`、`CallLogAdapter.java`、`FavoritesFragment.java` 等
- 资源与配置：`build.gradle`、`AndroidManifest.xml`、大量 drawable/xml
- 二进制更新：`res/drawable/app_bt_n.png`（Bin 2416 -> 8492 bytes）

具体各 bug 的根因已在 527 分支的原始提交中体现，不在此重复剖析。
