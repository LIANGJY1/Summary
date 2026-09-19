# SIR-5992 · HUD开启时拨打蓝牙电话，HUD不显示联系人名称和号码

- **提交**：`445512c7` | 2026-08-19 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B（提交信息标注影响等级D） · 频次 必现-80%~100% · 状态 关闭 · 域 HUD

## 问题
HUD 开启时拨打蓝牙电话，若该号码无联系人名称，HUD 界面名称和号码都不显示，一片空白。

## 根因分析
`FloatCallWindow` 的 HUD 布局填充逻辑（`inflateHudLayoutInto`/`inflateOutGoingHudView`）中，`tvUser` 的赋值被包在 `if (!TextUtils.isEmpty(primaryCall.getContactName()))` 里：联系人名为空时整个 if 不执行，`tvUser` 既不显示名字也不会回退显示号码——代码里虽然写了 `else { tvUser.setText(primaryCall.getNumber()) }`，但它在"名字非空但按空格切分后为空"的嵌套分支里，名字为空的外层分支没有任何动作。结果无联系人名字的来电在 HUD 上无任何身份信息。修复利用上文已算好的 `displayName`（`StringUtil.isBlank(contactName)` 时取 `primaryCall.getNumber()`），无条件 `setText`。注：缺陷库 sol 写"无联系人名字时，使用号码代替"，与 diff 实际一致——号码兜底来自 `displayName` 的三元定义，diff 中该行为上下文未变。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java（+6/-14，两处同型修改）
```diff
@@ FloatCallWindow.java（来电与去电 HUD 两处相同） @@
-        if (!TextUtils.isEmpty(primaryCall.getContactName())) {
-            if(primaryCall.getContactName().split(" ")[0].length()>0){
-                tvUser.setText(primaryCall.getContactName().split(" ")[0]);
-            }else {
-                tvUser.setText(primaryCall.getNumber());
-            }
-        }
+        tvUser.setText(StringUtil.isBlank(displayName)
+                ? ""
+                : displayName.strip().split("\\s+")[0]);
```
（`displayName` 定义为：`StringUtil.isBlank(primaryCall.getContactName()) ? primaryCall.getNumber() : primaryCall.getContactName()`）

## 为什么能修复
赋值不再依赖"名字非空"前置条件，`displayName` 在名字为空时已经是号码，`tvUser` 永远被刷新，空白场景消除。同时统一用 `StringUtil.isBlank` + `strip().split("\\s+")`，修掉了旧代码 `split(" ")[0]` 对非常规空白/前导空格名字处理不稳的问题。隐患：名字和号码都为空的极端情况下显示空串（`? ""` 分支），但该场景在真实通话中不存在。

## 复盘与经验
- "条件不满足就什么都不做"是 UI 空白 bug 的常见形态：旧值残留或初始为空，比错误显示更具迷惑性，显示控件应无条件刷新。
- 嵌套 if-else 的兜底分支（else 里 setText(number)）写在了错误的层级，导致看似有兜底实则不可达——review 时要检查每个分支的可达性。
- 先算好一个语义完整的 `displayName`（名字→号码兜底）再统一消费，比在每个显示点重复判断更不易漏。
