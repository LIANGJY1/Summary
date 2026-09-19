# SIR-1889 · D档五指抓屏返回驻车桌面后仍可再次触发抓屏动画

- **提交**：`0b1048cc` | 2026-07-03 | ljl | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 仪表信息

## 问题
D 档下五指抓屏返回驻车桌面（安卓驾驶主页）后，再次五指抓屏仍会播放抓屏过渡动画；期望是已经位于桌面时不再响应动画。

## 根因分析
五指抓屏事件的处理在 `PageStateMachine.kt` 中分为"公共前置处理"与"查状态表分发"两步。原实现把动画触发写在了公共路径里：只要收到 `Event.FourFingerSwipe`，无论当前处于哪个状态，都无条件 `mainHandler.postDelayed({ notifier.enterFiveFingerCapture(1) }, 333)`。这导致即便状态机已处于 `S3_Android_Drive`（驻车桌面）自环，动画依然会被再次拉起。提交消息自述为"未区分当前是否仪表页面或安卓页面"。动画播放本质是"从其他页面切回桌面"这个**状态迁移**的伴随效果，却被错误地放在了与状态无关的**事件预处理**位置。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt`（+7/-3）
```diff
// --- application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt
-        if (event == Event.FourFingerSwipe) {
-            mainHandler.postDelayed({ notifier.enterFiveFingerCapture(1) }, 333)
-        }
+        //安卓页面已在前台不响应五指抓屏动画，仅在仪表状态1、4、5下响应
+//        if (event == Event.FourFingerSwipe) {
+//            mainHandler.postDelayed({ notifier.enterFiveFingerCapture(1) }, 333)
+//        }
```
```diff
// --- 三处状态迁移 handler（S1/S4/S5 收到 FourFingerSwipe 迁往 S3_Android_Drive 处）同步补充
                 Event.FourFingerSwipe -> {
                     State.S3_Android_Drive to { _: DataContext ->
+                        mainHandler.postDelayed({ notifier.enterFiveFingerCapture(1) }, 333)
                         notifier.showAndroidDriveHomePage()
                     }
                 }
```

## 为什么能修复
把动画触发从"事件级公共路径"下移到"状态迁移级 handler"后，只有真正发生 仪表/其他状态 → `S3_Android_Drive` 的迁移才会播动画；已在桌面时事件走桌面自身的 handler，不再触发 `enterFiveFingerCapture(1)`。隐患在于三处 handler 是复制粘贴式修改，后续新增可抓屏状态时容易遗漏；且原逻辑是以注释方式保留而非删除，存在被误恢复的可能。

## 复盘与经验
- **伴随效果要挂在状态迁移上，而不是事件入口上**：副作用（动画、提示音、埋点）若放在状态分发之前，会对所有状态生效，包括"自环"场景。
- **状态机的自环迁移是高频踩坑点**：设计状态表时应明确"当前状态再次收到同类事件"的行为，最好在状态表里显式写出自环 handler。
- 缺陷库根因还提到"3D桌面车模无交互是产品要求非问题"，说明复盘中需区分代码缺陷与产品预期，避免误归类。
