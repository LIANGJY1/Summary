# A. CMP 技术栈与打包（调研日 2026-09-19）

## 1. Compose Multiplatform Desktop 成熟度
- 【事实】稳定版 v1.12.0（2026-08-25 发布），v1.13.0-alpha01（2026-09-10）；主仓 JetBrains/compose-multiplatform 19.4k stars、pushed_at=2026-09-19，持续活跃。Desktop(JVM) 为官方一级目标（打包插件随主仓维护）。
- 【事实】Linux 已知问题：CMP-6570「desktop 性能差」、CMP-5100「内存偏高且主要来自 native/Skia」（均 open）；CMP-2002 聚焦 TextField 高 CPU（2025-11 修复）；CMP-9528 多屏拖动绘制偏移（2026-02 修复）。
- 【事实+推断】基础 app 内存 ~100–150MB（CMP-5100 及 2021 年基准 146MB/Linux）；社区共识比 Electron 轻（无 Chromium，slack-chats.kotlinlang.org 2025-11 讨论）。【推断】稳态 ≤500MB 的 NFR 无压力，但冷启动/首帧要调优。

## 2. Wayland 支持现状 → **只能 XWayland**
- 【事实】skiko 仓库代码搜索 "wayland" 命中 0 条；Compose Desktop 窗口层基于 AWT/Swing。OpenJDK 生产版 AWT 无原生 Wayland 后端。
- 【事实】Wayland 相关 issue 均为 XWayland 行为：CMP-2475 缩放卡顿（2026-08 修复）、CMP-8927 KDE Plasma 崩溃（2025-10 修复）、CMP-8522（2025-08 修复）、CMP-5101 偶发崩溃（**open**）、CMP-4518 wlr-layer-shell 请求（open）、CMP-1847 GNOME 托盘（open）；多屏 CMP-7787（open）。
- 【结论】PRD 的「X11+Wayland 双支持」必须改写为「X11（Wayland 会话经 XWayland 运行）」；高分屏缩放/多屏拖动需实测。

## 3. 中文输入法（致命项核查）
- 【事实】Desktop 目标的历史 IME bug 均已关闭：CMP-6131 fcitx 无法切换（2022-03 修复）、CMP-2628 JBR 下无法输中文（2023-10 修复）、CMP-3221 无法输入中文（2024-08 关闭，复现失败/环境问题）、CMP-9380 五笔首字母丢失（2026-02 修复）、CMP-2092 中文退格异常（2026-08-31 修复）。检索范围内 Desktop 无 open 的中文输入致命 bug（open 问题集中在 Web/Wasm，如 CMP-8872）。
- 【结论】fcitx/ibus 中文输入当前可用、有官方维护响应，**非致命阻塞**；但 2023 年曾出现数月不可用窗口（CMP-2493），回归风险真实存在。【未核实】本机 fcitx5/ibus 实测——强烈建议立项第 0 周 spike 验证，并把「fcitx5+ibus 中文输入冒烟」写入验收清单。

## 4. Markdown 渲染库
- **推荐：mikepenz/multiplatform-markdown-renderer**。【事实】1080 stars、v0.45.0（2026-08-28）、月度发版（pushed 2026-09-08）；README 原文「Full GFM: tables, task lists, strikethrough, autolinks, GitHub alerts, out of the box」，表格自 0.30.0 内建；代码高亮走可选 `-code` 模块（MarkdownHighlightedCodeFence）；图片经 Coil2/Coil3；链接点击/内链跳转有自定义组件扩展点。纯 Compose，Desktop 直接可用。
- 备选：halilozercan/compose-richtext【事实】990 stars，仍停 1.0.0-alpha05（2026-06；此前 alpha03=2025-07、alpha02=2024-12），发版慢、稳定性欠奉，不推荐做主依赖。

## 5. SQLDelight / SQLite FTS5
- 【事实】SQLDelight 2.4.0（2026-09-18 发布）；官方 JVM 文档：依赖 `app.cash.sqldelight:sqlite-driver:2.4.0`，`JdbcSqliteDriver("jdbc:sqlite:test.db")` → JDBC → xerial sqlite-jdbc。
- 【事实】xerial/sqlite-jdbc 3.53.4.0（2026-08-26，pushed 2026-09-15）根 Makefile 明确 `-DSQLITE_ENABLE_FTS5`（L116），另有 FTS3/RTREE/STAT4/LOAD_EXTENSION。
- 【事实+推断】trigram tokenizer 属 SQLite 上游 FTS5 模块（sqlite 仓库 ext/fts5/fts5_tokenize.c + fts5trigram.test），随 FTS5 一并编译，3.53 ≫ 3.34（trigram 引入版）。【结论】FTS5 与 trigram 均可用，无需替代驱动。

## 6. 打包与体积/启动
- 【事实】`packageDistributionForCurrentOS` 基于 jpackage（插件源码 AbstractJPackageTask；TargetFormat 含 Deb/Rpm/AppImage）。deb/rpm 路径成熟；AppImage 有 open bug（CMP-7101「AppImage target breaks 打包」、CMP-3814）→ deb 为主，AppImage 后置。
- 【事实】jpackage 自 JDK 18 默认生成 AppCDS 归档（JDK-8275303），社区实测启动提速 20–54%（morling.dev 基准 54%）；插件 DSL 提供 jvmArgs 可调 -Xmx 等。启动风险证据：CMP-9429「hello world exe 启动 5s」（Windows，open）；CMP-2645 首帧优化已有关闭实践。【未核实】Linux 精确启动秒数无公开基准——「≤3s」需 AppCDS+调优后实测验收。
- 【事实】GraalVM native image 对 CMP Desktop 无官方支持（CMP-2312「single binary?」官方答复即用 jpackage）；【推断】不现实，不纳入方案。安装包体积（自带 JRE，约百 MB 级）【未核实】无实测，立项后测量。

## 7. 备选栈（一句话）
- Swing+FlatLaf（AWT 输入法最稳、开发效率低）；Tauri+Web 前端（体积小、换整条技术栈）；JVM+Compose HTML 需浏览器容器、与本地图库形态不符。CMP 若 IME spike 失败首选退 Swing+FlatLaf。

## 对 PRD 的净结论
**判定：有条件可行。** 技术栈成立，无致命阻塞；三个条件必须落入 PRD：
1. 立项第 0 周 spike：fcitx5/ibus 中文输入 + Wayland(XWayland) 缩放/多屏实测，通过才锁定 CMP。
2. PRD 假设修改：①「X11+Wayland 双支持」→「X11 原生 + Wayland 经 XWayland」，多屏 HiDPI 列为已知风险；② AppImage 降级为 P2/观望（deb 为唯一承诺格式）；③「启动 ≤3s」验收方式注明「jpackage+AppCDS+jvmArgs 调优后实测」。
3. 验收清单加：中文输入法冒烟、1 万文件库滚动流畅性、稳态内存实测。

| 用途 | 库/版本 | 理由 |
|---|---|---|
| UI 框架 | org.jetbrains.compose 1.12.0 | 2026-08-25 稳定版，官方 Desktop 支持（Kotlin 版本按 release notes 配套） |
| Markdown 渲染 | com.mikepenz:multiplatform-markdown-renderer(-code,-m3) 0.45.0 | GFM 表格开箱、代码高亮可选模块、月度发版、Desktop 可用 |
| 数据库 | app.cash.sqldelight:sqlite-driver 2.4.0 | 官方 JVM 驱动，SQL 型 API |
| SQLite 运行时 | org.xerial:sqlite-jdbc 3.53.4.0 | 编译期含 FTS5+trigram（Makefile 证实），上游活跃 |
| 打包 | CMP Gradle 插件 → jpackage deb（+AppCDS） | deb 路径成熟；AppImage 有 open bug；GraalVM 不支持 |
