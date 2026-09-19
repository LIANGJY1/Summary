# SIR-2041 · 3D 车模生态件换色功能（feature，非 bugfix）

- **提交**：`e9e3db42` | 2026-08-10 | yangcheng-neu | Launcher | **feature（提交标注 [feature]，缺陷库 mistag=true，与批次"bugfix"定位不符，以 diff 实际为准）**
- **缺陷库**：未关联缺陷（SIR-2041 为需求单，defs 为空）

## 问题与说明
本提交是**新功能**：在 3D 车模中支持尾箱（TRUNK）/左箱（LEFT_BOX）/右箱（RIGHT_BOX）三个生态件的颜色自定义，含预览、应用、恢复默认、退出编辑四个交互。无缺陷修复语义，按结构剖析。

## 实现结构
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/manager/EcoColorStore.java`（新增 245 行）、`application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java`、`application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java`、`application/Launcher/src/main/java/com/yadea/launcher/manager/KanziType.java`
```diff
// application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
+            case KanziType.Button.ECO_TRUNK_COLOR:
+                handleEcoColorClick(EcoColorStore.Part.TRUNK, intValue);
+                break;
+            case KanziType.Button.ECO_COLOR_APPLY:
+                if (intValue == 1) {
+                    EcoColorStore.Part[] changed = EcoColorStore.applyPending();  // 仅落盘
+                }
+                break;
+            case KanziType.Button.ECO_COLOR_RETURN_DEFAULT:
+                if (intValue == 1) {
+                    EcoColorStore.Part[] changed = EcoColorStore.applyDefault();
+                    sendEcoColorsToKanzi(changed);
+                }
+                break;
+            case KanziType.Button.ECO_COLOR_EXIT_EDIT:
+                if (intValue == 1) {
+                    EcoColorStore.Part[] dirty = EcoColorStore.discardPending();  // 丢弃预览，回滚已保存色
+                    sendEcoColorsToKanzi(dirty);
+                }
+                break;
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/manager/EcoColorStore.java（核心状态机，摘要）
+    /** 点生态件颜色：预览并即时下发 Kanzi，不落盘 */
+    private void handleEcoColorClick(EcoColorStore.Part part, int color) {
+        if (!EcoColorStore.setPendingColor(part, color)) { return; }
+        sendEcoColorsToKanzi(new EcoColorStore.Part[]{part});
+    }
```
设计要点：`EcoColorStore` 用静态数组维护 `sSavedColors`（SP 落盘）与 `sPendingColors`（预览）双份状态，`getDirtyParts()`/`getPersistedParts()` 差异化计算下发集合；`ensureReady()` 惰性初始化 + `syncPendingFromSaved()` 从 `SPUtils` 加载并对非法色值兜底 `COLOR_DEFAULT`；`KanziType.Button` 新增 6 个按钮 id（`Button.ECO_Color_Apply` 等，其中 EXIT_EDIT 为占位 id 待 Kanzi 定义）。

## 为什么这样设计
"预览-应用"两段式：点击色卡即时下发 Kanzi 预览但不落盘，只有点"应用"才 `applyPending()` 持久化，退出编辑时 `discardPending()` 把 dirty 部件回滚到已保存色——避免了"点了颜色就永久生效"的误操作。仅对 dirty 部件下发，减少 Kanzi 通信量。注意点：`sendEcoColorsToKanzi(changed)`（apply 分支）实际取 `getPendingColor` 下发，而 apply 后 pending 与 saved 相同，语义一致；`ECO_COLOR_EXIT_EDIT` 注释明示占位 id，存在与 Kanzi 工程联调期变更风险。

## 复盘与经验
- "预览(pending) - 已保存(saved) - 脏检查(dirty)"三件套是可视化编辑类功能的标准状态机，落笔前先定状态模型再写 UI。
- SP 落盘值要做合法性校验并给默认兜底（`isValidColor ? color : COLOR_DEFAULT`），防配置回滚/升级后脏数据。
- 与 3D 引擎约定的按钮 id 应尽早冻结，代码中留占位 + TODO 注释虽诚实，但集成期容易漏改。
- 批次分析中该提交被标为 bugfix 属 mistag，统计 bugfix 数量时应以提交头 `[feature]/[bugfix]` 前缀为准。
