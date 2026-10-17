# Android Performance Knowledge Base Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Reorganize all Android performance knowledge articles so each topic has a clear owner, Q&A order follows learning dependencies, duplicate material is consolidated, and all affected indexes remain accurate.

**Architecture:** Keep the knowledge-base Q&A article model, but split or relocate independent topics where existing files mix unrelated review goals. Assign each cross-cutting concept one authoritative article, retain only scenario-specific usage in other articles, and update the local and parent README indexes after content moves.

**Tech Stack:** Markdown, repository knowledge-base conventions, Python for structural count/link checks only.

**Spec:** `knowledge-base/01-android/13-performance/README.md` and `knowledge-base/WRITING-GUIDE.md`.

## Global Constraints

- Follow `knowledge-base/WRITING-GUIDE.md` as the only writing standard; preserve Q&A structure and enforce its title, ordering, self-contained answer, list, and example quality gates.
- Keep platform-specific implementation in the appropriate Android topic directory; do not leave project-specific incident facts in general knowledge articles.
- Do not invent or weaken technical claims while moving text; preserve version qualifiers and source anchors.
- Give every concept one primary article; scenario-specific cross references must not duplicate the full mechanism explanation.
- Preserve unrelated pre-existing working-tree modifications and do not commit the shared checkout.
- Update all affected Q numbers, counts, internal links, and the two Android README indexes.
- Do not add or run a test suite; verify Markdown structure, Q sequences, counts, and relative links.

---

### Task 1: Refactor articles 01–08

**Files:** `knowledge-base/01-android/13-performance/01-*.md` through `08-*.md`; any new focused article created from these files. Do not edit README indexes in this task; Task 4 owns all index updates.

- [x] Assign single-topic ownership for methodology, tools, Perfetto, smoothness, responsiveness, startup, and ANR.
- [x] Move startup material from responsiveness into startup; move or isolate authentication, push, and integrity topics by domain; narrow smoothness to frame/jank and route UI/platform deep dives to their existing topic directories.
- [x] Reorder Perfetto capture/SQL prerequisites, startup sections, and ANR timer/evidence sections by dependency.
- [x] Merge repeated definitions while retaining scenario-specific application steps.
- [x] Rewrite affected titles and answers to satisfy the writing guide; preserve version/source evidence.
- [x] Return exact files changed, Q migrations, new counts, and any unresolved routing decisions.

### Task 2: Refactor articles 09–15

**Files:** `knowledge-base/01-android/13-performance/09-memory-performance.md`, `11-network-performance.md` through `15-perfetto-advanced.md`; relevant existing graphics/network/platform destination articles and any focused article created. Do not edit README indexes in this task; Task 4 owns all index updates. Leave `10-power.md` for Task 3, which owns its consolidation with the app/system power articles.

- [x] Separate graphics-memory material from general memory performance and route it to the graphics topic.
- [x] Consolidate power measurement, framework mechanisms, and app-side power governance under distinct ownership; route DNS mechanics to network knowledge.
- [x] Move platform research material that does not explain performance (AOHP security harness) to its appropriate research/AI destination.
- [x] Reassess app stability/crash overlap and route project incident facts to issue/project archives, leaving reusable diagnosis knowledge.
- [x] Keep Perfetto advanced subtopics navigable, distinguish them from the basic Perfetto article, and reorder investigative workflow before detailed mechanisms where appropriate.
- [x] Rewrite affected titles and answers to satisfy the writing guide; preserve version/source evidence.
- [x] Return exact files changed, Q migrations, new counts, and any unresolved routing decisions.

### Task 3: Refactor articles 16–23

**Files:** `knowledge-base/01-android/13-performance/16-*.md` through `23-*.md`; `10-power.md`, any new focused article created, and relevant existing destination articles. Do not edit README indexes in this task; Task 4 owns all index updates.

- [x] Separate observability governance, diagnostic signal semantics, APM product selection, and SDK internals.
- [x] Move benchmark method material to the performance-methodology/tools owner; remove duplicate crash, network, power, and WebView mechanism explanations.
- [x] Consolidate scheduler/Power HAL/ADPF/thermal mechanisms; retain application-specific tuning and energy measurement in app-side articles.
- [x] Split energy case studies by topic and split CPU optimization from package-size optimization where their learning goals do not depend on each other.
- [x] Reorder each article from measurement/model prerequisites to diagnosis, implementation, and limits.
- [x] Rewrite affected titles and answers to satisfy the writing guide; preserve version/source evidence.
- [x] Return exact files changed, Q migrations, new counts, and any unresolved routing decisions.

### Task 4: Integrate structure and indexes

**Files:** `knowledge-base/01-android/13-performance/README.md`, `knowledge-base/01-android/README.md`, and every touched article.

- [x] Resolve cross-task overlaps by applying the single-owner topic map.
- [x] Ensure every article has continuous Q numbering and every internal reference resolves.
- [x] Update both README indexes with all files, descriptions, and exact question counts.
- [x] Verify total question count from actual Q headings and check Markdown whitespace.
- [x] Report remaining deferred issues explicitly; do not claim unresolved passages passed the writing-guide quality gates.
