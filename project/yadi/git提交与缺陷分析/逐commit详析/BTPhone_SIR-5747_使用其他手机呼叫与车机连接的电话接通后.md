# SIR-5747 · 其他手机呼叫车机已连接电话，接通后 HUD 图标未变红色挂断

- **提交**：`6fd65037` | 2026-08-10 | liujinfeng | BTPhone | bugfix（含死代码大清理）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话（rc：HUD 图标按拨出/呼入方向更新 / sol：改为按是否接通更新）

## 问题
用另一部手机呼叫与车机蓝牙连接的那部手机，接通后 HUD 浮窗上的按钮仍停留在绿色"接听"图标，没有切换为红色"挂断"图标。

## 根因分析
HUD 浮窗（`FloatCallWindow`，布局 `float_hud_window.xml`）中按钮 `hangupBtn2` 的背景更新条件写错了维度：原逻辑按**通话方向**（拨出/呼入）组织分支来决定图标，而不是按**通话是否接通**。第三方来电呼叫已连接手机的场景下，车机侧该通话的更新路径不完全走"呼入弹窗"分支，方向驱动的分支没有覆盖"接通"这一刻，`hangupBtn2` 背景就停在初始绿色 `bg_reject/answer` 选择器上（布局里甚至硬编码了 `bg_reject_button_selector` 红色作为初始值，与未接通应显示绿色的规则相悖）。根因即缺陷库所述"HUD 通话状态以拨出和呼入来更新"，状态维度选错。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java`、`application/BTPhone/src/main/java/com/yadea/btphone/telecom/InCallServiceImpl.java`、`application/BTPhone/src/main/res/layout/float_hud_window.xml`（另删除 8 个死代码文件）
```diff
// application/BTPhone/src/main/res/layout/float_hud_window.xml（初始态改为绿色接听样式）
         android:src="@drawable/icon_float_reject"
-        android:background="@drawable/bg_reject_button_selector"
+        android:background="@drawable/bg_answer_button_selector"
```
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java（按通话阶段显式设置图标，多处）
+            if (hangupBtn2 != null) {
+                // 来电等待/未接通阶段：绿色接听样式
+                hangupBtn2.setBackground(ContextCompat.getDrawable(mContext, R.drawable.bg_answer_button_normal));
+            }
@@
+            if (hangupBtn2 != null) {
+                // 保留中/通话中：红色挂断样式
+                hangupBtn2.setBackground(ContextCompat.getDrawable(mContext, R.drawable.bg_reject_button_selector));
+            }
@@
             inflateCallingFloatView();
             mFloatWindowManager.replaceView(getRootView(), KEY_REMOVE_ANIM);
             setVisibility(VISIBLE);
+            if (hangupBtn2 != null) {
+                // 接通后：红色挂断样式
+                hangupBtn2.setBackground(ContextCompat.getDrawable(mContext, R.drawable.bg_reject_button_selector));
+            }
```
同时：`InCallServiceImpl` 移除 `onCallRemoved`/通话状态回调里对 `BTPhoneCmdController.notifyCallStatusChange` 的调用；整体删除 `CanSignalCoordinator`(425行)、`BTPhoneCanManager`、`BTPhoneCanConstants`、`PhoneNumberParser`、`BTPhoneCmdController`(382行) 及 `android.os.SystemProperties`/`android.telecom.Call`/`TelecomManager` 三个本地 framework 桩类。

## 为什么能修复
修复把图标更新从"方向驱动"改为"阶段驱动"：未接通（响铃/等待）显式设绿色 `bg_answer_button_normal/selector`，接通（`inflateCallingFloatView`、双通话保留中等各阶段）显式设红色 `bg_reject_button_selector`，每个 UI 渲染入口都自带正确图标态，不再依赖可能缺失的方向分支，第三方来电接通后图标必然翻红。布局初始值同步改为绿色，与"未接通显绿"规则一致，消除初始闪烁。副作用：图标状态散落在多个 if 分支中重复设置，后续新增通话阶段容易漏设一处，宜收敛为单一 `updateCallButtonByState()`。

## 复盘与经验
- UI 状态图标应绑定"业务阶段"（响铃/接通/保留/结束）而非"事件来源方向"，方向只是进入阶段的路径之一。
- 布局 XML 里硬编码的初始图标态必须与状态机的初始阶段一致，否则视觉抖动会掩盖真实状态 bug。
- 顺带删除的 framework 桩类（本地同包名 `android.telecom.Call` 等）是高危遗留——同包名伪装类会干扰编译期 API 校验，清理时应全局确认无引用。
- 删除 `BTPhoneCmdController` 等 1500+ 行死代码与缺陷修复同提交，回归时除验证 HUD 图标外，还需烟测通话状态对中间件的通知链路未被误伤。
