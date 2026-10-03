# Android 知识库重构 — 基线报告（Task 1）

- 快照时间：2026-10-03（本报告生成时的工作树）
- 分支/commit：`main` @ `ca0b09b703375e770410d9c060dd78e540209155`
- 工作树状态：**含未提交改动**（atlas/PRD.md、atlas AppStore.kt、LearningView.kt、01-android 下 11 个文档、README、面试索引），全部保留为迁移基线，不做 checkout/reset。

## 总量

- 目录：18 个编号主题目录
- Markdown：128 个（125 问答文档 + `README.md` + `面试高频索引.md` + `13-audio/README.md`）
- 题目（Atlas 兼容解析，粗体/标题/裸 Q 标记、围栏感知）：**2303**
- 空答案：**2**
  - `17-car-app/01-Launcher.md` Q2「CarLauncher是什么时候启动的？被谁拉起的？」
  - `18-build-system/02-soong-modules.md` Q10「我现在会写Android.bp，说说写完之后有什么用是怎么生效的？」
- 缺 H1：`17-car-app/01-Launcher.md`
- 题号连续性：全部 125 个问答文档题号自 Q1 连续递增，无重复/跳号。
- 编码：全部 UTF-8 合法。

## 分目录基线

| 目录 | 文档数 | 题数 | 体积 |
|---|---:|---:|---:|
| 01-architecture | 23 | 279 | 379KB |
| 02-rendering | 3 | 85 | 116KB |
| 03-input | 7 | 129 | 191KB |
| 04-storage | 1 | 22 | 26KB |
| 05-memory | 2 | 49 | 68KB |
| 06-system | 3 | 80 | 122KB |
| 07-performance | 8 | 218 | 296KB |
| 08-cpu-power | 2 | 42 | 54KB |
| 09-app-practice | 19 | 456 | 676KB |
| 10-tools | 8 | 236 | 380KB |
| 11-defects | 8 | 197 | 334KB |
| 12-platform-native | 3 | 51 | 80KB |
| 13-audio | 12+README | 92 | 112KB |
| 14-network | 10 | 141 | 120KB |
| 15-ui | 8 | 140 | 121KB |
| 16-project-architecture | 1 | 1 | 7KB |
| 17-car-app | 1 | 6 | 6KB |
| 18-build-system | 6 | 79 | 117KB |

文件级 SHA-256 与 H1 见 `file-baseline.json`；每题清单（题号/题面/状态/标签/答案哈希/答案长度）见 `inventory.json` 与 `question-ledger.tsv`（2303 数据行）。

## 解析口径（与 Atlas SourceQuestions.kt 对齐）

- 标记：`**Qn: …**`（整行）、`#{1,6} Qn: …`、裸 `Qn: …`；`/` 或 `~~~` 围栏内不识别。
- 状态前缀 `[done] [learning] [todo]`，标签前缀 `[tags:…]`。
- 答案边界：下一个 Q 标记或下一个「第 N 章/节」标题。
- 与 Atlas 的差异：Atlas `Entry.id` 还含题面 md5；本台账以 `old_path + old_q` 为键，题面哈希仅用于防丢失。

## 链接与引用扫描（links.json）

- 全仓相对 Markdown 链接 3089 个；指向 01-android 的链接目标全部存在，无真断链。
- 11 处「断链」实为 `<绝对路径:行号>` 形式的源码引用（AAOS13_study），目标文件全部存在，不属于断链。
- 指向 01-android 路径的外部引用文件：`AGENTS.md`、`knowledge-base/ROUTING.md`、`knowledge-base/05-os/README.md`、`knowledge-base/04-exp/README.md`、`knowledge-base/04-exp/00-项目缺陷画像与复盘方法.md`、`knowledge-base/02-testing/README.md`、`knowledge-base/android-provider.md`、`knowledge-base/sdk-design.md`、`atlas/questions.md`、`docs/others/Launcher3_Technical_Document.md`、`docs/others/framework/Android时间/Android时间同步.md`、`docs/网络/CAN_LIN_UDS_车载总线与诊断详解.md`、`ai/OpenCode/opencode-usage-guide.md`、`research/android-four-components-interview.md`、`tools/skills/android-cli/{SKILL,references/interact}.md`、`tools/skills/android-intent-security/SKILL.md`、`project/yadi/pet/08-调试注入命令清单.md`。
- Atlas 配置：`knowledge-base/atlas/config/settings.properties` `sourceQuestionPaths` 含目录规则 `knowledge-base/01-android/`（另含 `knowledge-base/` 与 kotlin 语法册）→ 目录内改名不需改配置；Task 8 仍需核对 Atlas 侧已保存的选中路径与树导航。

## README 计数核对

`01-android/README.md` 全册速览表与实测题数逐册比对（用于 Task 8 重算，不逐个手改）：

实测与表中不一致的册：02（44→实测 44✓）…（完整差异清单由 `regen-readme` 步骤自动重算，不在此手工维护）。已知计划快照称 12 处不匹配；以 Task 8 重算结果为准。

## 迁移台账

- `question-ledger.tsv`：2303 行骨架已建（old_path/old_q/old_title/old_status/old_tags/old_body_sha256/core_question）；action/new_* 列待 Task 2 逐题审定。
- `path-map.tsv`：骨架已建。

## 试点评审纪要（Task 3 回填区）

（待试点完成后回填具体 reject/fix 记录。）

## 批次日志

（每个批次开始前记录受影响文件哈希；发现外部改动先重盘点。）

## 试点评审纪要（Task 3）

试点三文件的裁决（后续批次沿用同一判据）：

- `01-architecture/15-安装归档与资源配置.md`：Q1–Q9（安装事务/dexopt/增量/分阶段/归档）→ 新册 `15-package-management.md`；Q10–Q13（ResourcesImpl/Configuration/免重建/泄漏取证）→ `15-ui/03-resources.md`。判据：Q4 的"查询归因"主体是 PMS 内部锁而非性能方法论，随包管理册。
- `18-build-system/04-linux-kernel-drivers.md`：Q1–Q9/Q13/Q14（VFS 分发、fops、设备号、copy_from_user、MMIO、ioctl/mmap、内核容器、/dev 节点、用户态 ABI 验证）→ `12-platform-native/05-driver-runtime.md`；Q10–Q12/Q15（Kconfig/Kbuild、外部模块编译、.ko 部署验证、迭代流程）→ 留 `18-build-system/04-kernel-modules.md`。判据："在哪构建/怎么部署"归构建册，"运行时行为/设计"归原生层。
- `09-app-practice/18-应用开发机制与常用API.md`：按机制五分（SparseArray→16-app-framework/05、View tag→15-ui/02、Parcelable×2→16-app-framework/04、Retrofit×2→09/12、通知渠道→06-platform-services/01、SharedPreferences×2→04-storage/01）。判据：题面的读者问题决定归属，不保留"杂项 API 册"。

 reject/fix 记录：迁移以整题搬运为默认，题目与答案同步修订限于（a）跨册引用重指向、（b）合并题的补充段、（c）前言/链接修复；未对 2300 题做逐题重写（见完成报告的残余工作）。

## 批次日志

- 2026-10-03 迁移执行：128 源文件 → 138 目的地文件（含 3 个库级外部目的地）。执行时再次解析工作树（含并行会话对 `02-Android系统启动流程.md` 的新增 tags 与 10-Kernel/18bs/06 的新增题——均已带入目的地，无丢失；基线 inventory 与执行时点之间该 3 文件被并行会话改动，按计划"先重盘点再编辑"原则以执行时点为准）。
- 引用修复：答案内跨册 Q 引用按台账重映射；面试高频索引全量重写；README 重建；13-audio/README、04-exp 两文件、research 一文件、AGENTS.md 更新。审计（audit_refs.py）发现并修复：5 处裸题号被误挂他册名（20-selinux×2、13-audio/04×2、13-audio/03、06-jni、04-app-sandbox、04-storage/02×2）、9-hal 两处代表路径断链、若干链接后冗余册名。
- 已知解释性差异（verify.py provenance 11 项）：全部为（a）HEAD 与工作树差异（并行会话未提交改动：02-启动 Q1–Q3/Q34、10-Kernel Q6、18bs/06 Q8、18bs/05 Q2、06-JNI Q3 部分句、09-HAL Q3 部分句）或（b）已记录的引用编辑/合并补充段（13-audio/03 Q2、06/02 Q30/Q40、09/08 Q24）。无内容丢失（dup-normalized=0，题数 2300+3 处置=2303 对平）。
