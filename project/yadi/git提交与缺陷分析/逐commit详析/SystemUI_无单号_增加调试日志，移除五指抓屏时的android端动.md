# 无单号（SIR-XXXX）· 移除 Android 端五指抓屏动画并增加调试日志
- **提交**：`a4c36bfa` | 2026-09-08 | caohongliang | SystemUI/Launcher | 调试/行为调整（非标准 bugfix，未关联单号）
- **缺陷库**：未关联单号

## 问题
五指抓屏（截屏/进入 3D 桌面手势）时，Android 端自带的动画响应与 3D 车模动画重复/冲突；同时相关链路缺少日志，问题定位困难。

## 根因分析
`KanziDataSourceManager` 的广播处理里，收到 `ACTION_FIVE_FINGER_CAPTURE` 时既回调 `mLoadCompleteListener.onFiveFingerCapture(...)`（触发 Android 端动画），又调用 `kanziManager.setValue("", KanziType.CarModel.D_DESKTOP, ...)` 把手势值下发给 Kanzi 3D 车模——两端都做动画，视觉表现冲突。本提交属于行为调整：注释掉 Android 端回调，动画统一交给 3D 车模实现。其余改动为纯调试增强：`Myapplication.onCreate` 里 `LogUtils.setAppTag("Launcher_")` 给 Launcher 日志加统一前缀；`NavBarFragment` 在驾驶锁定媒体控制、非法连击拦截、媒体按钮触摸拦截三处补日志。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java、application/Launcher/src/main/java/com/yadea/launcher/Myapplication.kt、application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java
@@ -131,9 +131,10 @@
             switch (intent.getAction()) {
                 case ACTION_FIVE_FINGER_CAPTURE:
-                    if (mLoadCompleteListener != null) {
-                        mLoadCompleteListener.onFiveFingerCapture(intent.getIntExtra(EXTRA_ENTER, 0));
-                    }
+                    // 移除掉android的五指抓屏动画响应，动画由3D车模去实现
+//                    if (mLoadCompleteListener != null) {
+//                        mLoadCompleteListener.onFiveFingerCapture(intent.getIntExtra(EXTRA_ENTER, 0));
+//                    }
                     if (isKanziConnected) {
                         kanziManager.setValue("", KanziType.CarModel.D_DESKTOP, intent.getIntExtra(EXTRA_ENTER, 0));
                     }
```

## 为什么能修复（效果评估）
手势动画收敛为单一来源（Kanzi 3D 车模），消除双动画叠加；日志前缀与三处拦截日志方便后续用 logcat 追踪点击/锁定链路。隐患：`mLoadCompleteListener.onFiveFingerCapture` 是注释而非删除，若 Android 端动画仍被其他入口依赖，需确认无回归；未关联缺陷单号，属于开发自驱的体验调整，测试覆盖以人工验证为主。

## 复盘经验
- 同一手势在"2D 应用层 + 3D 车模层"双端响应时，必须明确唯一动画归属方，否则表现为说不清的视觉冲突。
- 调试日志要带统一 AppTag/前缀，多进程车机日志里才筛得出来；顺手补拦截点日志是低成本高回报的做法。
- 未关联单号的提交在复盘时最难追溯背景，提交信息里的 [what]/[why] 不该写 NA。
