# 无单号 · 静态代码扫描同步（Setting SonarQube 整改）

- **提交**：`4f0440ab` | 2026-07-14 | sgh | Setting | feature（实为质量整改）
- **关联单**：无

## 需求/目标
**提交类型：静态扫描（SonarQube）批量整改**。对 Setting 应用 21 个文件整改（+1199/-1613，净删 414 行），范围覆盖 widget 自定义控件（`GearSwitchViewNew` 大改 270 行、`GearSwitchView2`、`BaseSwitchCompat`、`LimitedSeekBar`）、输入过滤器、Application 与主 Fragment 容器。

## 实现结构
- `NOSONAR` 行级豁免：`SdkManager.getInstance().init` 回调、空实现 `initObserve/lazyLoadData`（框架模板方法空实现属合理场景）。
- 重复代码抽取：主容器 Fragment 切换逻辑抽 `hideOtherFragments(transaction, fragments, target)`，两处循环合一。
- Kotlin 惯用法重写：Fragment 复用判断由 6 行嵌套 if 改为 `to = existingTo?.takeIf { from != null || it === to } ?: to`。
- 删除注释代码（侧方预警 UI 更新残留）、日志拼接精简（`GearSwitchViewNew` 内多处）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/activity/MainActivity.kt（Fragment 容器切换，节选）
-            if (existingTo != null) {
-                val isFromLocaleChange = (from == null) && (existingTo !== to)
-                if (!isFromLocaleChange) {
-                    to = existingTo
-                }
-            }
+            // 非语言切换场景（from==null且实例不同）时，复用已有的Fragment
+            to = existingTo?.takeIf { from != null || it === to } ?: to
@@
-            for (frag in allFragments) {
-                if (frag != null && frag.isAdded && frag !== to) {
-                    transaction.hide(frag)
-                }
-            }
+            hideOtherFragments(transaction, allFragments, to)
```
实现讲解：整改中质量最高的部分是 Fragment 容器：把"语言切换时强制新建、否则复用实例"的隐晦嵌套条件用 `takeIf` 显式化并配注释，同时把 hide 循环抽函数消重复。这类"顺手重构"发生在扫描整改提交里，行为等价性验证（切页/切语言回归）必不可少。

## 复盘与要点
- 空实现的模板方法（`initObserve(){}`）用 `// NOSONAR` 保留而非删除，是正确的取舍——删掉会破坏基类约定；这类"合理空实现"值得沉淀为团队 NOSONAR 白名单。
- 整改提交混入行为敏感重构时，diff 审查重点应放在被重写的条件表达式上（`takeIf` 语义与原嵌套 if 必须逐分支比对）。
- 与 `f38ce24d`/`0a41a634` 构成三连整改，说明 SonarQube 门禁已上线并按模块推进；建议后续整改拆小提交以降低回归定位成本。
