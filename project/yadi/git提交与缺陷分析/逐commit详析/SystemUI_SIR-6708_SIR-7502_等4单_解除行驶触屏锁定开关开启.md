# SIR-6708/SIR-7502/SIR-7505/SIR-7533 · 行驶触屏锁定逻辑补充 & 导航可触摸 & CarPlay来电号码未滚动
- **提交**：`48462980` | 2026-09-04 | ljl | SystemUI/BTPhone | bugfix
- **缺陷库**：SIR-6708/7502/7505/7533 均为 等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互/仪表信息/手车互联（需求变更类）

## 问题
三件事：① "解除行驶触屏锁定"开关开启时，起步后车速低于 20km/h 的前 5 秒内屏幕未锁定（SIR-7502/7505）；② 全屏地图导航时车速>0 锁屏状态下地图仍可被触摸操作（SIR-6708）；③ CarPlay 来电浮窗来电号码不滚动显示（SIR-7533）。

## 根因分析
① `DriveTouchLockController.kt` 的 `evaluateLocked()` 原逻辑是"开关开时：车速 >= 20km/h 锁定；车速 < 20km/h 才启动 5s 解除计时"。该规则隐含"先满足过锁定条件"的前提：车辆从 0 起步且持续低于 20km/h 时，锁定条件从未满足，代码走"未锁定（含首次评估）"分支直接 `setLocked(false)`，导致低速起步阶段触屏完全不锁，违反"车速<20km/h 且 5s 内应保持锁定"的迟滞语义。
② 锁屏态下 SystemUI 只拦截了状态机内的手势（GestureGuard），但 S2 常态导航等场景前台是地图应用，普通触摸由 Android InputDispatcher 直接派发给前台应用，根本不经过 SystemUI 状态机，出现"toast 已提示锁屏但地图还能拖动"。修复引入新组件 `DriveTouchMaskWindow`：锁屏期间经 `WindowManager.addView` 加一层 `TYPE_APPLICATION_OVERLAY` 全屏透明遮罩，`setOnTouchListener` 返回 true 消费全部触摸，层级设计为"应用窗口 < 遮罩 < 系统窗口（状态栏/Dock/HUN）"，只挡前台应用不挡 SystemUI 自身；同时锁屏 toast 改为满足条件即弹（与解除开关解耦），触摸遮罩时按 1500ms 节流重弹。
③ `CarPlayCallWindow.java` 来电卡片的 `tv_name` 是普通 `TextView` + `ellipsize="end"`，长"姓名 号码"串被截断，没有蓝牙电话弹窗的跑马灯效果；本次把它替换为 `SmartEllipsizeTextView` 并改用其 `setNameAndNumber(resolveName(info), resolveNumber(info))` 统一处理姓名/号码拼接与 LTR 隔离（原 `resolveDisplayName` 的字符串自拼逻辑拆分为两个解析方法）。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallWindow.java；application/BTPhone/src/main/res/layout/carplay_call_card.xml；application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt；application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/common/DriveTouchLockController.kt；application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/common/DriveTouchMaskWindow.kt（新增）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/common/DriveTouchLockController.kt
+                // 起步沿（车速从 0 升至正车速，如挂 D 档起步）：立即锁定并启动 5s 解除计时，
+                // 否则车速始终 < 20 时永远不会锁定（SIR-7502/7505）
+                lastSpeed <= 0f && speed > 0f -> {
+                    startUnlockTimer()
+                    setLocked(true)
+                }
+                // 未锁定且非起步沿：保持/置为未锁定，不等待 5s
                 else -> {
                     cancelUnlockTimer()
                     setLocked(false)
```
```diff
--- application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt
         ctx.isScreenLocked = locked
+        // 锁屏期间弹全屏遮罩屏蔽前台应用（地图等）的普通触摸，解锁移除（SIR-6708）
+        DriveTouchMaskWindow.update(locked && ctx.gear != Gear.P)
...
-            // 锁屏 toast 仅在"解除行驶触屏锁定开关=开启"时提示（SRS §屏幕触控限制）；
-            // 开关=关闭时锁屏只显示 Dock 锁定标记 + 拦截手势，不弹 toast
-            if (DriveTouchLockController.isSwitchOn()) {
-                showToast(appContext.getString(R.string.toast_drive_touch_locked))
-            }
+            // 锁屏 toast：满足锁屏条件即弹出一次，与解除行驶触屏锁定开关状态无关
+            // （开关开/关均提示，SIR-6708）
+            showToast(appContext.getString(R.string.toast_drive_touch_locked))
```
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallWindow.java
-        TextView tvName = card.findViewById(R.id.tv_name);
         TextView tvStatus = card.findViewById(R.id.tv_status);
+        SmartEllipsizeTextView tvName = card.findViewById(R.id.tv_name);
...
-        tvName.setText(resolveDisplayName(info));
+        tvName.setNameAndNumber(resolveName(info), resolveNumber(info));
```
（carplay_call_card.xml：`TextView` 换 `com.yadea.btphone.view.SmartEllipsizeTextView`，移除 `ellipsize/maxLines` 由控件内部接管）

## 为什么能修复
① 引入 `lastSpeed` 记录前一帧车速，检测"0→正"起步沿立即锁定并启动 5s 解除计时，使"车速<20 且不足 5s"区间处于锁定态，5s 计时器到期且仍<20 才解锁，迟滞语义补全；② 遮罩窗口在输入分发链路的更上游消费触摸，前台地图再也收不到 MOVE 事件，同时锁屏提示不再受开关状态影响；③ 跑马灯控件 + 姓名/号码分离解析，长来电信息可滚动且拼接规则与蓝牙电话弹窗一致。风险点：遮罩若在异常时序下未移除会挡住全部前台触摸（代码已做幂等 `update` 与 try-catch 防护）；起步沿锁定依赖车速信号连续上报，若信号丢帧可能漏检。

## 复盘与经验
- 状态机/控制器类迟滞逻辑（如"超阈值锁定、低于阈值且持续 N 秒解锁"）必须考虑初始沿：从"从未锁定"状态进入低于阈值区间时，原条件组合会漏掉"先锁后计时"的路径，补边沿检测是标准解法。
- SystemUI 想"锁住"前台应用的触摸，靠拦截自己框架内的手势没用——普通 touch 走 InputDispatcher 直达前台；用 `TYPE_APPLICATION_OVERLAY` 全屏消费型遮罩是 Android 车机上典型的输入阻断手段，层级要精确卡在"应用之上、自家系统组件之下"。
- 一次提交跨 SystemUI+BTPhone 修 4 个单号，效率高但测试面大，提交信息用 "&" 串联多个缺陷，适合需求变更批量适配场景，日常建议尽量拆分。
