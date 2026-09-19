# SIR-6455 · 三方通话中点击置灰拨号键仍调起蓝牙电话界面

- **提交**：`c2940000` | 2026-08-26 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
三方通话时，浮窗上的拨号键显示为置灰（不可用），但点击它却拉起了蓝牙电话主界面。

## 根因分析
三方通话浮窗的根布局 `ll_float_root_window` 在 `FloatCallWindow` 中注册了整窗点击 `llFloatOutingWindow.setOnClickListener(v -> mPresenter.launchPhoneActivity())`——点浮窗任意空白处进入全屏蓝牙电话。而拨号键 `iv_keyboard`（`icon_float_keyborad`）在三个 `float_three_way_*_window.xml` 里只写了 `android:clickable="false"` + `android:alpha="0.5"`：`alpha` 只是视觉置灰，`clickable=false` 在 Android 中的真实语义是"该 View **不消费**触摸事件"，点击事件穿透按钮冒泡到父容器根布局，恰好命中"进全屏"逻辑。也就是说"置灰"只做了样子，事件层面按钮形同虚设，用户点的是按钮、响应的却是整窗。

## 关键代码修改
改动文件：`application/BTPhone/src/main/res/layout/float_three_way_holding_window.xml`、`float_three_way_incoming_window.xml`、`float_three_way_outgoing_window.xml`、`application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java`

```diff
--- a/application/BTPhone/src/main/res/layout/float_three_way_incoming_window.xml
@@ -81,7 +81,8 @@
             android:layout_marginStart="14dp"
             android:layout_marginTop="@dimen/dp_60"
             android:padding="@dimen/dp_10"
-            android:clickable="false"
+            android:clickable="true"
+            android:enabled="false"
             android:alpha="0.5"
             android:src="@drawable/icon_float_keyborad"
             android:background="@drawable/bg_mic_button_selector"
```

（holding/outgoing 两个布局的拨号键做同样修改。`FloatCallWindow.java` 另有一轮重构：删除三方通话"运行时用 `ConstraintLayout.LayoutParams` 动态交换上/下布局位置"的 50 余行代码，改为按通话状态选用静态布局，视图 id 语义化改名 `ivTopHangUp→ivActiveHangUp`、`ivBottomHangup→ivHoldingHangup` 等。）

## 为什么能修复
`clickable="true"` 让拨号键本身成为触摸目标：即使 `enabled=false` 无点击动作，View 也会消费（返回 true 吃掉）DOWN 事件，事件不再冒泡到根布局，`launchPhoneActivity()` 不会被误触发；`alpha="0.5"` 保留置灰视觉，`enabled="false"` 明确"不可用"语义。配套的布局静态化重构消除了运行时改约束带来的状态错乱空间（与 SIR-6456 的浮窗消失同源），`FloatCallWindow` 净减 89 行。风险：若其他置灰按钮也存在 `clickable=false` 写法仍有同类隐患，需同类排查。

## 复盘与经验
- Android 里"禁用一个按钮"的正确组合是 `enabled=false`（或 `clickable=true`+拦截），单独 `clickable=false` 会让事件穿透到父容器——置灰永远是视觉，事件消费才是行为。
- 给浮窗/弹窗根布局注册整窗点击（点空白进全屏）时，所有"应无效"的子控件都必须能消费事件，否则空白语义会吞掉按钮语义。
- 运行时用 LayoutParams 动态交换布局位置是高危写法，状态一多必乱；按状态切换静态布局 + 语义化 id，代码量和出错率同时下降（本 commit 顺带完成了这次重构）。
