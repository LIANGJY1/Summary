# SIR-8689 · 故障通知盖在通话浮窗上层
- **提交**：`47dd1b98` | 2026-09-18 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 待测试验证 · 域 主交互（与 SIR-8686 同根因同修法）

## 问题
触发故障通知后再进行通话，故障通知显示在蓝牙电话通话浮窗的上层，层级与设计相反。

## 根因分析
蓝牙电话的通话浮窗/拨号盘由 `FloatWindowManager`、`DialPadWindowManager` 以系统悬浮窗添加，原代码窗口类型统一取 `TYPE_APPLICATION_OVERLAY`（8.0+，`TYPE_SYSTEM_ALERT`/`TYPE_PHONE` 兜底），注释还自诩"核心修复：车机仪表专用图层"。而故障通知也是浮窗类窗口，同层 `TYPE_APPLICATION_OVERLAY` 之间按添加顺序/子层级竞争，故障通知后到即盖在通话浮窗之上。缺陷库 rc"同SIR-8686"、提交 `[why] type使用不对`：问题不在图层选择"高级与否"，而在**窗口类型优先级语义**——通话类窗口应使用电话优先级类型，才能在窗口策略中稳定压过普通通知类浮窗。

## 关键代码修改
改动文件：FloatWindowManager.java、DialPadWindowManager.java（4 处窗口类型统一）
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/floatview/DialPadWindowManager.java
-        // 【修复】使用 TYPE_APPLICATION_OVERLAY 替代已弃用的 TYPE_MAGNIFICATION_OVERLAY
-        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
-            layoutParams.type = LayoutParams.TYPE_APPLICATION_OVERLAY;
-        } else {
-            layoutParams.type = LayoutParams.TYPE_PHONE;  //NOSONAR
-        }
+        // 蓝牙电话弹窗统一使用电话优先级窗口类型。
+        layoutParams.type = LayoutParams.TYPE_PRIORITY_PHONE;
```
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatWindowManager.java
             WindowManager.LayoutParams hudParams = new WindowManager.LayoutParams(
                     ViewGroup.LayoutParams.WRAP_CONTENT,
                     ViewGroup.LayoutParams.WRAP_CONTENT,
-                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
-                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
-                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,  // NOSONAR
+                    WindowManager.LayoutParams.TYPE_PRIORITY_PHONE,  // NOSONAR
...
-        layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
+        layoutParams.type = LayoutParams.TYPE_PRIORITY_PHONE;
```

## 为什么能修复
通话浮窗、HUD、拨号盘共 4 处窗口类型统一改为 `TYPE_PRIORITY_PHONE`：该类型在窗口层级中属电话优先级段，系统故障通知（普通 overlay/通知层）天然低于电话优先级，通话浮窗稳定显示在故障通知之上，层级关系由窗口策略保证而非依赖添加时序。同时删掉各处版本分支与误导性注释，4 处类型收敛为一种，消除"有的地方一种 type、有的地方另一种"造成的层级不一致。副作用：`TYPE_PRIORITY_PHONE` 为系统内部类型，要求应用具备相应系统签名/权限（车机预置应用满足）；若其他应用也用该类型，仍需按子层级协调。

## 复盘与经验
- 浮窗层级问题的第一落点是窗口类型（type）的优先级语义，而非 z 轴微调：通话、导航、通知各有对应的类型段，选对 type 层级问题自动解决。
- 同一模块的多个悬浮窗必须统一窗口类型，"东一处 APPLICATION_OVERLAY、西一处 SYSTEM_ALERT"是层级互压 bug 的温床。
- 复制粘贴的注释（"核心修复""专用图层"）会固化错误认知，复盘时注释也要审。
- 缺陷库 rc/sol 写"同SIR-8686"，说明 8686/8689 是同一根因的两个表现，跨单号归并同修是高效做法。
