# SIR-6764 · D档0车速时，dock栏未显示图标(PP0需求已适配)
- **提交**：`38048467` | 2026-09-04 | ljl | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 仪表信息

## 问题
D 档 0 车速时，dock 栏不显示图标，点击 D 区无响应，无法进入 Android 暂态桌面。

## 根因分析
这是一个被"特性开关"临时关闭的功能，而非逻辑写错。`application/SystemUI/.../digitalkey/mainaction/common/DataContext.kt` 中的数据类字段 `linuxDockAdaptationEnabled`（Linux 仪表侧适配总开关）此前默认 `false`。在 `PageStateMachine.kt` 的状态机里，多处入口被该开关门控：S1 态的 `Event.DZoneClick` 处理中 `if (!c.linuxDockAdaptationEnabled || c.isScreenLocked) return null`，开关关闭时点击 D 区事件被直接吞掉，不会迁移到 `State.S3_Android_Drive`；S2 态的 `Event.FourFingerSwipe` 等也受同样保护。之所以当初默认关，是因为 Linux 仪表（kanzi）侧尚未适配底部档位信息的隐藏，贸然打开会出现仪表与 dock 图标叠加的显示冲突。缺陷库确认 kanzi 新版本已添加隐藏底部档位信息的能力（需求变更适配完成），因此车机端只需把开关默认值翻为 `true` 即可放行整条链路。本次提交同时删除了遗留的 `NsrCommSdk.aar.bak` 备份文件。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/common/DataContext.kt；component/commonlibs/NsrCommSdk.aar.bak（删除）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/common/DataContext.kt
-     * Linux 仪表侧适配总开关（临时特性标志，默认关）。
-     * 关 = G1/G2/G3/G4/G5/G8 全部回退到改动前行为，等仪表侧适配完成后由 PageStateMachine
-     * setLinuxDockAdaptationEnabled(true) 打开；对接稳定后删除此标志即为永久启用。
+     * Linux 仪表侧适配总开关（临时特性标志，仪表侧已适配完成，默认开，SIR-6764）。
+     * 关 = G1/G2/G3/G4/G5/G8 全部回退到改动前行为，可通过 PageStateMachine
+     * setLinuxDockAdaptationEnabled(false) 快速回退；对接稳定后删除此标志即为永久启用。
      * G6（删死代码）、G7（非网易云歌词 toast）不依赖仪表，不受此开关控制。
      */
-    var linuxDockAdaptationEnabled: Boolean = false,
+    var linuxDockAdaptationEnabled: Boolean = true,
```

## 为什么能修复
开关翻转后，`PageStateMachine` 中 `Event.DZoneClick`、四指抓屏等原本返回 null 被拦截的事件恢复放行，D 档 0 车速可正常进入 `S3_Android_Drive` 并显示 dock 图标；配合新版 kanzi 隐藏仪表底部档位信息，两端显示不再冲突。开关本身仍保留（`PageStateMachine.setLinuxDockAdaptationEnabled(false)` 可动态关闭），问题可快速回退，副作用风险低；隐患仅在于若 kanzi 版本未随包升级，会出现仪表叠加显示，属跨模块版本配套问题。

## 复盘与经验
- 特性开关（feature flag）是跨团队联调的利器：先合入代码并默认关闭，等对端（kanzi 仪表）适配完成后一行翻转默认值即可"修复"，且天然支持回退。
- 排查"功能不生效"类问题时应先搜开关/门控条件，再怀疑逻辑本身——本例状态机逻辑从未改过，只是门没开。
- 默认关闭的临时开关要有明确的"打开时机"注释和跟踪单号，否则容易变成永久僵尸开关；本例注释随翻转同步更新，是好的实践。
- 顺手清理 `*.aar.bak` 备份文件，避免二进制垃圾进入版本库。
