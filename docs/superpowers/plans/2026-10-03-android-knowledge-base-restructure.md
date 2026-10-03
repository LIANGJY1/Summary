# Android Knowledge Base Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this plan task by task. The checkboxes are handoff checkpoints; a human executor can use the same checkpoints without agent tooling.

**Goal:** Reorganize every directory, document, question, and answer under `knowledge-base/01-android/` so names are consistent, each document has a clear scope, and every question and answer stays within that scope.

**Architecture:** Freeze the current corpus and build a per-question migration ledger before changing content. Approve one topic owner for each question, then migrate in small batches, rewriting question boundaries and answer boundaries together. Rename paths once their final contents are stable, update all references, and verify the result with both structural checks and a complete editorial review.

**Tech Stack:** Markdown Q&A documents; Git; Python 3 for inventory and link checks; Atlas `SourceQuestions` parser for application compatibility.

**Spec:** The user's 2026-10-03 request; `AGENTS.md`; `knowledge-base/WRITING-GUIDE.md`; `knowledge-base/CONTEXT.md`; `knowledge-base/01-android/README.md`. The writing guide is the content authority. The directory rules in `AGENTS.md` govern destinations outside `01-android/`.

## 中文交接摘要

本计划覆盖 `01-android/` 的全部 18 个目录、125 篇问答文档和当前识别出的 2303 道题。执行顺序是：冻结现状 → 逐题建立迁移台账 → 审定每个目录与文档的收录边界 → 用三个混合主题文件试迁 → 分领域迁移与改写 → 统一改名及修复引用 → 全量内容和 Atlas 验收。不能先批量重命名，再按关键词猜测题目去向。

每题先用一句话写出唯一的复习目标，再核对标题、答案首段和正文是否回答同一问题。若正文出现另一个独立目标，就拆为另一题并移至它的主题文档；若两题只是同一判断的重复表达，就合并并记录原题去向。每个原题都要在台账中有最终位置或明确的合并记录，不能因重排而消失。

目录按“知识内核”归属：系统启动讲启动接力，内核文档讲 rootfs 等机制，构建系统讲源码如何变成可执行文件与镜像；性能文档讲指标和归因，工具文档讲采集操作，应用实践文档讲应用侧实施；真实项目个案与非技术项目治理按仓库边界迁出 Android 通用学习资料。下文给出逐目录职责、已发现的混合文档、九个执行任务和验收门槛。

## Global constraints

1. This is a content and information-architecture migration. Do not modify the external production projects under `/home/liang/Project/Reachauto/YaDi/`, `tools/skills/`, `LICENSE`, ignored build output, or credentials.
2. Preserve current uncommitted edits. The working tree already contains Android documentation and Atlas changes; a fresh checkout of `HEAD` alone is not the current content baseline. Record `git status --short` and file hashes before starting each batch.
3. `knowledge-base/WRITING-GUIDE.md` applies to every Q: one core question, a direct first paragraph, enough mechanism and conditions for a senior interview answer, and no answer content outside the title's promise. A list is mandatory when the answer can be expressed as a list; use explicit, increasing numbers and introduce each item with a named point such as `1. **适用条件**：`.
4. Preserve platform/version qualifiers and evidence levels. In particular, `12-platform-native/` currently labels its material as second-hand; a move or rename does not turn it into locally verified source analysis. Refer to the exact AOSP/AAOS branch for version-dependent statements. Treat actual YaDi source as read-only.
5. Preserve Q status and tags when moving content. A question cannot disappear merely because it overlaps another; record where each original claim went. Do not keep duplicate Q blocks as compatibility shims because Atlas indexes both.
6. No broad search-and-replace of `Qn` references. Each reference must be resolved against its source document and the migration ledger. Existing links affected by moves must be repaired; ordinary answer text should remain self-contained instead of sending the reader to another Q.
7. Update `AGENTS.md` when a directory's responsibility changes or a previously listed destination is actually created. Update `knowledge-base/ROUTING.md` only if its top-level routing contract changes; its existing table does not enumerate every `01-android/` subdirectory.

## Baseline and why this needs editorial review

At the 2026-10-03 working-tree snapshot, `01-android/` contains **18 numbered topic directories, 125 Q&A documents, and 2,303 bold `**Qn: ...**` questions**. The Q numbers are sequential in each source document, but this does not establish that the questions are well scoped.

- 104 of the 125 document stems contain Chinese or mixed-case text; the writing guide calls for short English topic stems. `17-car-app/01-Launcher.md` has no H1.
- `01-android/README.md` has 12 question-count mismatches. Regenerate its counts after content migration instead of editing individual stale values during every move.
- Two answers are empty: `17-car-app/01-Launcher.md` Q2 and `18-build-system/02-soong-modules.md` Q10. Each must be answered from evidence, merged into a covering Q, or explicitly removed with its destination recorded.
- At least 76 answers contain a `Qn` reference. Audit these for the writing guide's self-contained-answer rule and for stale numbering.
- Atlas parses a Q marker as an answer boundary, and `SourceQuestions.Entry.id` includes `sourcePath`, Q number, and a hash of the question text. Renames, renumbering, and title edits therefore change Atlas's visible identity. The source-file status and tags can be preserved in the marker; the migration ledger must also record old-to-new identity and any selected document path that changes.
- The current `knowledge-base/atlas/config/settings.properties` includes the whole `knowledge-base/01-android/` directory in `sourceQuestionPaths`. It does not need 125 per-file entries, but any persisted selected path and Atlas tree navigation must be checked after renames.
- `AGENTS.md` mentions `knowledge-base/网络/` and `knowledge-base/career/work-project-analysis/`, but these directories are absent in this checkout. Create and register a destination before moving material there; never delete the source first.

The counts above are a planning snapshot, not an acceptance target after splitting and merging. The acceptance target is **complete provenance**: every one of the 2,303 baseline questions has a recorded outcome, and every final Q has a source or a documented new reason for existence.

## Target directory contract

Use two-digit directory and file prefixes for reading order and lower-case English kebab-case topic stems, for example `01-architecture/02-system-boot.md`. Keep Chinese H1 titles and Chinese Q&A. Do not make a filename a full question or concatenate independent topics. A directory name may remain unchanged when it already satisfies these rules; this is a review of every directory, not a mandatory rename of every directory.

| Final directory | Action from current tree | Owns | Sends elsewhere |
| --- | --- | --- | --- |
| `01-architecture/` | Keep; reduce mixed-topic files | Cross-layer architecture, startup chain, Binder/ART/JNI architecture, package management, SELinux and permission model | Detailed kernel/native implementation to `12-platform-native/`; display, network, UI, app API and diagnostics to their domains |
| `02-rendering/` | Keep | Render pipeline, Surface/BufferQueue, graphics API and compositor mechanisms | FPS attribution and optimization decisions to `07-performance/`; View/Compose usage to `15-ui/` |
| `03-input/` | Keep | Input device-to-window/app delivery, keys, focus, AAOS input, input-specific diagnosis | Generic UI layout and rendering to `15-ui/`/`02-rendering/` |
| `04-storage/` | Keep; add runtime partition topic | Runtime partition roles, filesystem, mount and I/O mechanisms | How images are built and flashed to `18-build-system/` |
| `05-memory/` | Keep | Memory management, reclaim, freezer, GC pressure and native/shared memory ownership where memory is the core question | Performance attribution to `07-performance/`; Binder driver internals to `12-platform-native/` |
| `06-platform-services/` | Rename from `06-system/`; replace its mixed contents | Distinct system service contracts without a dedicated domain, such as notification, biometrics, location, virtualization and platform AI services | Broad AOSP optimization to `07-performance/`; AAOS services to `17-aaos/`; build to `18-build-system/` |
| `07-performance/` | Keep | Measured latency, frame pacing, ANR, memory cost, power/network cost, platform optimization decisions | Core mechanism descriptions to their mechanism directories; app implementation recipes to `09-app-practice/` |
| `08-cpu-power/` | Keep | CPU scheduler, DVFS, thermal, Power HAL, energy mechanisms | Measurement-only advice to `07-performance/`; app-side recipes to `09-app-practice/` |
| `09-app-practice/` | Keep | App-side implementation, stability, startup, I/O, networking, observability and optimization choices | Platform mechanisms to mechanism directories; independent app-framework API contracts to `16-app-framework/` |
| `10-tools/` | Keep | Perfetto, ADB, GPU/APM tools and reproducible diagnosis workflows | Product mechanisms to their domain; general learning-path material only to an established learning-path destination |
| `11-defect-patterns/` | Rename from `11-defects/` | Cross-project technical root causes, failure shapes and repair rules from defect analysis | Commit discipline and project governance to `knowledge-base/04-exp/`; project-only facts to the project-analysis destination |
| `12-platform-native/` | Keep; split current broad native file | Kernel startup/runtime mechanisms, Binder driver, shared-memory allocators, Bionic, BPF/logd and device-driver runtime behavior | Kernel/module build and image packaging to `18-build-system/` |
| `13-audio/` | Keep | Audio focus, routing, output, HAL, Bluetooth audio and AAOS-specific audio behavior | Generic network, UI and app startup topics to those domains |
| `14-network/` | Keep | Android/AAOS connectivity, cellular/Wi-Fi/VPN, app network behavior and network diagnosis | Protocol fundamentals without Android-specific behavior to a registered general networking learning directory |
| `15-ui/` | Keep | Activity, View, Compose, resources, windows, AAOS UI and driving-interaction behavior | SurfaceFlinger/GPU mechanism to `02-rendering/`; generic app components to `16-app-framework/` |
| `16-app-framework/` | Replace `16-project-architecture/` after routing its project case | Four components, Handler/Looper, Parcel/Parcelable, collections/annotations and other independent app-framework API contracts | Project-specific architecture case to `knowledge-base/career/work-project-analysis/`; implementation recipes to `09-app-practice/` |
| `17-aaos/` | Rename from `17-car-app/` | CarService service topology, VHAL integration, car user/power behavior and CarLauncher implementation | Audio/input/UI specifics to `13-audio/`, `03-input/`, `15-ui/` |
| `18-build-system/` | Keep | Source/configuration to module, executable, image, install/flash output; Soong/Make/Kati/Ninja and kernel build | Runtime `/init`, rootfs, driver callbacks, SELinux runtime policy to the appropriate mechanism document |

The final owner is determined by **the question the reader is trying to answer**. A performance question may mention Binder but remains in `07-performance/` if its decision is how to measure and attribute a delay. A build question may mention `/init` but remains in `18-build-system/` only if its decision is how the binary is produced or packaged.

## Known split and relocation candidates

These are verified title-level candidates, not permission to move an entire numbered range without reading its answer. The executor must decide each Q in the ledger before editing.

| Current source | Q/topic range to inspect | Primary proposed owner and decision |
| --- | --- | --- |
| `01-architecture/02-Android系统启动流程.md` | Boot stages versus runtime watchdog/crash recovery | Keep causal boot-stage Qs in `01-architecture/02-system-boot.md`; route general runtime recovery and fault classification to `06-platform-services/`, `07-performance/` or `11-defect-patterns/` according to the title's goal. The concise ramdisk stage remains a boot step; full ramdisk/rootfs explanation already belongs to kernel/native material. |
| `01-architecture/07-Android分区.md` | Runtime partition/super/OTA versus image packing | Runtime partitions to `04-storage/02-partitions.md`; artifact format, image build and flash operations to `18-build-system/06-system-images.md`. |
| `01-architecture/10-Kernel.md` and `12-platform-native/01-内核与原生层.md` | GKI, ramdisk/rootfs, Binder driver, ashmem, DMA-BUF | Give kernel startup and GKI a focused native document; give Binder driver and shared memory separate native documents. Preserve the second-hand evidence label on moved claims until checked. |
| `01-architecture/11-版本演进与图形栈预加载.md` | Q1–Q5 version/platform facts; Q6–Q11 graphics preload/driver; Q12–Q14 Zygote/USAP | Separate platform version judgment, graphics pipeline, and Zygote behavior. A 16 KB page claim belongs with memory/compatibility if that is the answer's core. |
| `01-architecture/12-类加载ART编译与JNI链接.md` | Q1–Q9 ART; Q10–Q12 JNI; Q13–Q16 Bionic/linker | Merge into focused ART, JNI and native-linker documents. Keep dexopt/installation as runtime or package-management behavior, not Soong module configuration. |
| `01-architecture/13-MessageQueue锁竞争与Binder深化.md` | Q1–Q7 message queue; Q8–Q13 and Q16–Q19 Binder; Q14–Q15 freezer | Move by mechanism; keep performance-specific measurement only when a distinct decision remains. |
| `01-architecture/15-安装归档与资源配置.md` | Q1–Q9 installation/archive; Q10–Q13 Resources/Configuration | Separate package management from `15-ui/03-resources.md`. |
| `01-architecture/16-显示与窗口链路.md` | Rendering Q1–Q2; WMS/ViewRoot Q3–Q5; input responsibility Q6 | Route to `02-rendering/`, `15-ui/05-window-system.md`, and `03-input/` or UI diagnosis respectively. |
| `01-architecture/17-Telephony与Connectivity.md` | Telephony and Connectivity service Qs | Merge with relevant `14-network/` topics; preserve the distinction between radio state and validated/default network. |
| `01-architecture/18-Notification-Biometric-Location.md` | Q1–Q6 notification; Q7–Q11 biometric; Q12–Q16 location | Create three focused `06-platform-services/` documents; avoid a single triple-topic filename. |
| `01-architecture/19-AVF可观测与AI手机技术栈.md` | Q1–Q4 AVF; Q5–Q8 logd; Q9–Q12 BPF; Q13–Q16 AI | AVF/AI service contracts to `06-platform-services/`; logd/BPF mechanism to `12-platform-native/`; diagnostic-tool usage to `10-tools/` only when that is the question. |
| `06-system/01-AOSP性能优化.md` | Build/verification Q5–Q7, performance mechanisms/measurement, Rust service Q23–Q25, research prototypes Q26–Q31 | Build commands to `18-build-system/`; measurable optimization and prototype evaluation to `07-performance/`; Rust runtime/FFI facts to `12-platform-native/`. Mark paper-only claims as such. |
| `06-system/02-OEM与设备差异.md` | Freezer, SoC/GPU/Power HAL/MPC Q1–Q18; Private Space Q19–Q21; AAOS Q22–Q40 | Split among `05-memory/`, `08-cpu-power/`, `07-performance/`, `16-app-framework/`, `17-aaos/` and the specific UI/audio/input domains. Use Q titles and answers, not contiguous ranges alone. |
| `06-system/03-CarService服务速览.md` | CarService service topology | Move to `17-aaos/01-car-services.md`, then merge any duplicate service answers by decision. |
| `07-performance/07-渲染管线-基础与图形API.md` and `08-渲染管线-跨框架与媒体.md` | Pipeline/API mechanism versus measured performance | Route mechanism to `02-rendering/`, framework usage to `15-ui/` or `09-app-practice/`, and attribution/optimization Qs to `07-performance/`. Camera/video and XR may need focused rendering documents after the ledger identifies coherent clusters. |
| `09-app-practice/18-应用开发机制与常用API.md` | SparseArray, View tags, Parcelable, Retrofit, notifications, preferences | Split into `16-app-framework/`, `15-ui/`, `14-network/`, `06-platform-services/` and `04-storage/`/app I/O by primary question. Do not preserve a miscellaneous API dump. |
| `10-tools/08-学习方法与检查清单.md` | Q1–Q8 diagnosis workflow; Q9 Studio preview; Q10 ADB process lookup | Keep the diagnostic method in `10-tools/`; route the two UI/ADB how-to Qs to their focused tools/UI document. |
| `11-defects/08-提交治理与防回归.md` | Commit/issue governance versus technical repair pattern | Move nontechnical project/quality governance to `knowledge-base/04-exp/`. Keep a Q under technical defect patterns only when its answer is a reusable code-level cause or repair rule. |
| `12-platform-native/03-aconfig特性开关.md` | Declaration/code generation versus runtime flag storage | Build declaration to `18-build-system/`; aconfig runtime service/storage to `06-platform-services/` or `12-platform-native/` by mechanism. |
| `13-audio/01–05` and `13-audio/06–12` | Broad reference books versus continuous AAOS 13 learning path | Retain a dedicated learning progression if its Qs answer distinct decisions; merge actual duplicate claims into one owner and keep the learning path as navigation, not copied answers. |
| `14-network/10-网络分层原理与地基机制.md` | DNS/TLS/TCP/IP/NAT fundamentals | Keep Android-specific packet path/diagnosis in `14-network/`; move general protocol teaching to `knowledge-base/网络/` once that destination and its README exist. |
| `16-project-architecture/01-应用进程启动与全局服务生命周期.md` | Specific Launcher/VehicleService architecture case | Move to `knowledge-base/career/work-project-analysis/` after creating/confirming that directory. Preserve a generic mechanism in Android Q&A only if it can stand without the project-specific class graph. |
| `17-car-app/01-Launcher.md` | CarLauncher overview and Q2 blank answer | Route generic AAOS Launcher behavior to `17-aaos/02-car-launcher.md`; resolve empty Q2 against the existing system boot/HOME Qs before writing new text. Add an H1. |
| `18-build-system/04-linux-kernel-drivers.md` | Driver runtime Q1–Q9/Q13–Q14; Kconfig/Kbuild/deploy Q10–Q12/Q15 | Driver design and userspace ABI to `12-platform-native/`; module build/deploy to `18-build-system/04-kernel-modules.md`. Verify each Q before movement. |
| `18-build-system/05-android-executables.md` and `06-android-system-images.md` | Executable production versus `/init` runtime; image packaging versus runtime partition role | Retain binary/ELF/Soong artifact and image packaging Qs here; move detailed `/init` startup semantics to system boot/native, and runtime partition behavior to storage. |

## Migration ledger and identity contract

Create `docs/superpowers/plans/android-kb-restructure/question-ledger.tsv` and `path-map.tsv` before moving content. These are review artifacts; keep them until the final verification is accepted.

Each question-ledger row must contain: `old_path`, `old_q`, `old_title`, `old_status`, `old_tags`, `old_body_sha256`, `core_question`, `action` (`keep`, `move`, `split`, `merge`, `answer`, or `remove-duplicate`), `new_path`, `new_q`, `new_title`, `evidence_version`, `related_old_ids`, `reviewer`, and `review_state`. One old Q may map to several final Qs only when its title really contains independent questions. Several old Qs may map to one final Q only when the answer preserves every distinct condition and mechanism. `remove-duplicate` must point to the surviving final Q. `answer` applies to an empty answer and needs evidence.

The path map must contain every renamed/moved document with `old_path`, `new_path`, `reason`, and `affected_links_checked`. Never rely on a same-number Q in another file as an implicit target. The ledger key is the original relative path plus Q number; the body hash catches accidental loss during editing.

For document ownership, each target file begins with a short boundary statement: what it answers, what it leaves to adjacent files, and the version/evidence basis. This is a route rule for editors; it must not become a repeated preamble in every answer.

### 单题裁决与排版协议

1. **先定归属**：写出读者要解决的一个问题，再按上表选唯一文档。标题提到某项技术，并不自动把它归入该技术的目录；归属由答案实际完成的判断决定。
2. **再定拆并**：同一标题若问两个可独立复习的目标，拆成两题。多题若依赖同一条件、给出同一结论，合成一题并保留各题独有的前提与例外。只因示例相同、关键词相同，不构成合并理由。
3. **写题目**：题目写明必要场景、现象或选择，以及需要解释的原因或做法。题面不能宽到覆盖别的文档，也不能窄到答案不得不越界。例如镜像文档问“通用 ramdisk 放进哪个启动镜像”，内核文档问“内核怎样把启动归档展开为 rootfs”，启动流程文档只问“这一动作位于启动链哪一段”。
4. **写答案**：题目行使用 Atlas 可识别的 `**Qn: [状态] [tags:标签] 题目**` 形式；原有状态和标签按原样保留。紧接一段直接回答，再按依赖顺序解释必要机制、条件和版本边界。答案到下一个 Q 标记为止；不要把下一题的引导段落留在上一题尾部。代码围栏内若有 Q 字样，围栏必须完整闭合。
5. **逐段删减**：逐段问“删掉它后，读者还能正确判断吗”和“它是否引入了标题没承诺的新目标”。前者为“能”时删除；后者为“是”时拆出并路由到新题。为让每题自足所需的少量前提可以重复，但完整答案只由一个文档拥有。
6. **验证事实**：把 Android 13、Android 17、设备厂商分支和 Linux 通用机制分开。对没有核对到的默认值、权限、属性省略行为，标明未知和待核对来源；不能根据属性名猜出省略结果。

## Execution tasks

### Task 1 — Freeze and inventory the actual baseline

**Files:** Read all `knowledge-base/01-android/**/*.md`, `AGENTS.md`, `knowledge-base/WRITING-GUIDE.md`, Atlas `SourceQuestions.kt`, and current Git status. Create the two ledger files named above plus `baseline.md` beside them.

- [ ] Record branch/commit, `git status --short`, file SHA-256 values, directory/file/Q counts, empty answers, nonsequential or duplicate Q markers, source status/tags, and internal/external Markdown links.
- [ ] Parse fenced code correctly: a line resembling `**Q9: ...**` inside a code block is not a question. Compare inventory counts with Atlas's `SourceQuestions` recognition, which accepts bold, heading, and plain Q markers.
- [ ] Scan all repository links and prose references to `01-android/` paths, H1 titles and Q numbers. Include `01-android/README.md`, `13-audio/README.md`, `面试高频索引.md`, `AGENTS.md`, and the Atlas synced settings.
- [ ] Write the baseline report with the observed totals and current dirty-file list. If another editor changes a file hash before its batch begins, re-inventory that file before editing it.

**Gate:** Every parsed source Q has exactly one ledger row; the ledger covers the complete baseline. No content file has moved yet.

### Task 2 — Approve directory and file ownership

**Files:** Modify `baseline.md` and `path-map.tsv`; draft per-directory scope text for `01-android/README.md` and any new directory README. Do not rename content yet.

- [ ] Apply the target directory contract to every one of the 125 source files. Fill `path-map.tsv` with an English kebab-case final path for each surviving document.
- [ ] Mark every Q's `core_question` and exact final owner in `question-ledger.tsv`. Decide by answer goal, not original source-book chapter, incidental example, or current filename.
- [ ] Resolve collisions before moving: existing `01-architecture/` ART/JNI/Binder files, `13-audio/` broad versus progressive books, and rendering/performance/app-practice overlaps each get one canonical owner per claim.
- [ ] Review missing external targets. Before creating `knowledge-base/网络/` or `knowledge-base/career/work-project-analysis/`, verify there is no equivalent existing destination, then add its README and update the repository knowledge map. Use `knowledge-base/04-exp/` for nontechnical project governance because it already exists.
- [ ] Give each final document a one-sentence scope and one-sentence exclusion in the ownership sheet. Reject filenames that require `-and-` or several unrelated technology names to describe their contents.

**Gate:** No ledger row has an unresolved destination; a reviewer can locate the unique owner of each question without reading the old directory name.

### Task 3 — Run a small editorial pilot

**Files:** `01-architecture/15-安装归档与资源配置.md`, `18-build-system/04-linux-kernel-drivers.md`, `09-app-practice/18-应用开发机制与常用API.md`, and their approved targets.

- [ ] Split package install from Resources/Configuration; split driver runtime from Kbuild; split the miscellaneous application API file by mechanism.
- [ ] For each moved Q, revise the question and answer together. If the answer requires another independent topic, create a separate Q at its owner and record a one-to-many mapping.
- [ ] Preserve all existing status and tags. Keep original source version and evidence qualifiers attached to the claims they support.
- [ ] Re-number each touched destination from Q1 in learning order: overview → mechanism → version/conditions → engineering application → diagnosis. Repair references listed in the ledger.
- [ ] Have a second reader check every pilot Q against the writing guide's title/answer-width and senior-interview rubric. Record concrete reject/fix notes in `baseline.md` so later batches use the same decisions.

**Gate:** No pilot Q is lost, duplicated, empty, or outside its new document; Atlas recognizes the pilot files and renders answers with the correct boundaries.

### Task 4 — Migrate kernel, startup, storage and build material

**Files:** `01-architecture/02-*`, `07-*`, `10-*`; all of `12-platform-native/`; all of `18-build-system/`; all of `04-storage/` and `05-memory/`.

- [ ] Separate startup sequence from kernel rootfs mechanics, runtime partition roles from image artifacts, and driver callbacks from Kbuild output.
- [ ] Split mixed native topics into kernel, Binder driver, shared memory, Bionic, logd/BPF and driver-runtime documents only when a coherent Q cluster exists. Record evidence level separately for each moved Q.
- [ ] Keep `18-build-system/` focused on source/configuration → artifact/installation. Any `/init` detail about PID 1 or stage handoff belongs to startup/native; any `init_boot.img` packaging detail belongs to system images.
- [ ] Batch no more than ten source documents or roughly 250 Qs per reviewer gate; finish link and ledger checks for one batch before opening the next.

**Gate:** A reader can answer “where is it built?”, “where is it packaged?”, and “what happens at runtime?” from three distinct document scopes without duplicate full answers.

### Task 5 — Migrate rendering, input, UI and performance

**Files:** all of `02-rendering/`, `03-input/`, `07-performance/`, `15-ui/`; mixed `01-architecture/11-*`, `13-*`, `16-*`; relevant `09-app-practice/06-*` through `09-*` and `10-tools/` entries.

- [ ] Give mechanisms (VSync, BufferQueue, SurfaceFlinger, graphics APIs) to `02-rendering/`, API/lifecycle usage to `15-ui/`, event delivery to `03-input/`, measured delay attribution to `07-performance/`, and app changes to `09-app-practice/`.
- [ ] Retain a Q in performance only when it answers a measurement, bottleneck, or optimization decision. A long pipeline explanation that merely supplies background is reduced to the minimum facts needed by that decision.
- [ ] Split mixed Flutter/Compose/WebView/camera/video/game/XR material by primary rendering or performance question. Create a focused file only after the ledger shows several coherent Qs, not for one incidental example.
- [ ] Reconcile overlap between UI Activity/window Qs and startup/HOME Qs by keeping the system-side decision in startup and the Activity/UI-side lifecycle in UI.

**Gate:** Each pipeline fact has one full explanation; diagnostic Qs contain enough local mechanism to stand alone without copying a whole mechanism chapter.

### Task 6 — Migrate platform services, app framework, app practice and tools

**Files:** all of `06-system/`, `08-cpu-power/`, `09-app-practice/` and `10-tools/`; every `01-architecture/` document not assigned to Tasks 4–5, including `01-*`, `03-*` through `06-*`, `08-*`, `09-*`, `12-*`, `14-*`, `15-*`, `17-*` through `23-*`; future `06-platform-services/*` and `16-app-framework/*`.

- [ ] Split Notification/Biometric/Location and AVF/logd/BPF/AI into focused documents. Move Telephony/Connectivity Qs to the network owner and Handler/four-components Qs to app framework.
- [ ] Inspect every Q in files that appear already focused, such as Binder, ART, JNI, SELinux, permissions, CPU/power and the existing app-practice books. Keep a Q in place only after the ledger confirms that both its title and answer fit the document boundary.
- [ ] Route OEM, Power HAL, MPC and AOSP performance material by actual decision. Keep version-selection and benchmark conditions with performance answers; keep build commands with build; keep app implementation with app practice.
- [ ] Resolve the two blank answers through source-backed writing or documented duplicate removal. An empty Q cannot pass the final gate.
- [ ] Move tool command syntax to `10-tools/`; keep the evidence interpretation needed to make a domain decision in the relevant domain answer.
- [ ] Inspect configuration/code fragments attribute by attribute, including list members, explicit values, omission behavior and version defaults. Split long snippets by first-level explanatory group; use `...` only where source content was actually omitted.

**Gate:** No “miscellaneous API”, three-service, or cross-layer file remains merely because it was the original collection point.

### Task 7 — Migrate AAOS, audio, network and defect patterns

**Files:** `11-defects/*`, `13-audio/*`, `14-network/*`, `16-project-architecture/*`, `17-car-app/*`, `06-system/03-*`; any new external learning or project-analysis destinations.

- [ ] Keep AAOS-specific CarService, VHAL, users/power and CarLauncher Qs in `17-aaos/`; route vehicle audio, input, UI and driving restriction details to their established domains.
- [ ] Compare audio 01–05 and 06–12 by actual answer claim. Preserve their progressive reading path in `13-audio/README.md`, while merging copied answers and keeping separate Qs that answer distinct situations.
- [ ] Route generic DNS/TLS/TCP/IP/NAT foundations to the registered general networking destination and Android-specific network state, policy and diagnosis to `14-network/`.
- [ ] Move nontechnical commit/issue governance out of `11-defect-patterns/` into `knowledge-base/04-exp/`. Move the Launcher/VehicleService project case to the registered work-project-analysis destination; keep only generalized Android mechanism in the Android learning books.
- [ ] Check anonymization of real project names, issue IDs, private endpoints and credentials before any external-target move.

**Gate:** A question's location follows its reusable decision, not the project or source material that supplied its example.

### Task 8 — Final rename, references and Atlas compatibility

**Files:** all surviving `01-android/**/*.md`, `01-android/README.md`, `13-audio/README.md`, `面试高频索引.md`, `AGENTS.md`, `knowledge-base/atlas/config/settings.properties` only if an explicit configured path changes, plus external destination READMEs.

- [ ] Apply `path-map.tsv` once semantic moves are stable. Use `git mv` for pure path renames; for splits and merges, preserve provenance in the question ledger.
- [ ] Recalculate every file's Q numbers and README question counts from parsed content. Update the interview index by verified new topic/Q IDs, not by subtracting or adding a constant.
- [ ] Resolve every relative Markdown link, image path, source code reference and prose `旧文件 Qn` reference. Existing external source references keep their exact version/commit when still valid; inaccessible source is marked unverified rather than silently replaced.
- [ ] Check Atlas's selected document path and directory tree after renames. The `sourceQuestionPaths` subtree rule already covers `01-android/`; preserve Q marker syntax, status and tags. Record the expected `old path#Q -> new path#Q` identity change. Do not claim Atlas preserves path-derived IDs automatically.
- [ ] Remove empty source files only after their Q ledger outcomes are complete. Do not leave duplicate Q compatibility stubs.

**Gate:** Every old link in the path map has been visited; directory index, audio reading path and interview index point to existing final files and the intended questions.

### Task 9 — Full acceptance review and handoff

**Files:** `question-ledger.tsv`, `path-map.tsv`, `baseline.md`, all changed documents, final README/index files.

- [ ] Structural check: valid UTF-8; one H1 per Q&A file; permitted `NN-kebab-case.md` filename; sequential unique Q numbers from Q1; no empty answer; no accidental Q marker in an unfenced example; no broken relative links; no duplicated canonical answer.
- [ ] Coverage check: every original `old_path#Qn` has a final destination or an explicit merge/duplicate disposition; every final Q traces to old Qs or a documented source-backed new Q. Split and merged claims are checked against old body hashes so no version condition or warning disappears.
- [ ] Editorial check on **every** final Q: the title names one scenario/problem and answer goal; the first paragraph answers it directly; each later paragraph is inside the title boundary; mechanism, preconditions and changing version boundaries are present; lists use a single dimension and explicit numbers; config/code snippets explain all shown properties and omitted behavior; no “see Qn” carries the answer.
- [ ] Application check: Atlas scans and renders a file with tags, a moved Q, a fenced code block, a split file and a renamed directory. Confirm Q/answer boundaries, status display and selected-document navigation. If an Atlas code defect is discovered, log it as a separate application issue and complete unaffected document work.
- [ ] Run `git diff --check`; inspect `git diff --stat` and the full diff of each completed batch; obtain a reviewer sign-off on ledger completeness and topic ownership. Report residual unverified claims with exact source/version and responsible document.

**Done means:** every directory has a written scope, every Q is in one appropriate document, every answer satisfies the writing guide, all original Qs are accounted for, navigation works, and the final migration report lists the old/new paths and any intentional merges. A formatter pass or a clean parser result alone does not satisfy this task.

## Suggested reviewer order and batching

Use one owner plus one independent reviewer per batch. Review the pilot first; then run Tasks 4–7 in batches of no more than ten source documents or roughly 250 Qs. Content ownership and the ledger must be settled before any broad rename. Re-run link/index checks after each batch and run the full Atlas and provenance checks only after the last rename. Keep unrelated dirty files out of these batches.

The handoff order is: `baseline.md` → approved `question-ledger.tsv` and `path-map.tsv` → pilot diff/review notes → domain batches → final index/Atlas review → completion report. This gives a new executor a definite next action and a record of every decision.
