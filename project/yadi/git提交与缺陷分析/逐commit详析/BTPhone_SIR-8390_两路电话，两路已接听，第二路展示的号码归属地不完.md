# SIR-8390 · 三方通话时拨号盘未自动隐藏与通话浮窗重叠

- **提交**：`4e9d6bb3` | 2026-09-15 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
两路电话均已接听（三方通话）时，第二路的号码/归属地展示不完整——实际是拨号盘没有自动隐藏，与通话浮窗重叠遮挡所致（元数据中"验证版本号填写错误"的备注说明该单一度被误判未修复）。

## 根因分析
`FloatCallWindow` 在渲染三种三方通话布局（`float_three_way_incoming_window` / `float_three_way_holding_window` / `float_three_way_outgoing_window`）时，既不会收起之前打开的拨号盘（`hideDailPadView` 从未在进入三方态时被调用），布局里的 `iv_first_dialpad` 按钮仍可点击，允许在双路通话中继续拨号。拨号盘视图与通话浮窗同层级，持续显示即覆盖第二路通话信息（号码、归属地）。此外拨号成功分支 `safePlaceCall(numberToCall, "")` 只拨号不收盘，拨完盘仍留在屏上。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
@@ float_three_way_incoming_window 渲染处（holding/outgoing 两处同样调用）
         View view = LayoutInflater.from(getContext()).inflate(R.layout.float_three_way_incoming_window, this);
+        disableThreeWayDialPad(view);
@@ 拨号按键处理
-                    UiCallManager.get().safePlaceCall(numberToCall,"");
+                    boolean callPlaced = UiCallManager.get().safePlaceCall(numberToCall, "");
+                    if (callPlaced) {
+                        LogUtils.i(TAG, "Call placed, hide dial pad window");
+                        hideDailPadView();
+                    }
@@ 新增私有方法
+    /** 双路通话期间不允许继续拨号，并关闭可能仍在显示的拨号盘。 */
+    private void disableThreeWayDialPad(View view) {
+        hideDailPadView();
+        ImageView dialPadButton = view.findViewById(R.id.iv_first_dialpad);
+        if (dialPadButton != null) {
+            dialPadButton.setEnabled(false);
+            dialPadButton.setClickable(false);
+            dialPadButton.setAlpha(0.5f);
+        }
+    }
```

## 为什么能修复
从"入口 + 状态 + 事后"三层封堵：进入任何三方通话布局立即 `hideDailPadView()` 清掉存量拨号盘；`iv_first_dialpad` 被禁用置灰（alpha 0.5）阻止双路通话中再开拨号盘；即使此前已拨号成功，也在 `callPlaced` 后立即收盘。三处三方布局全覆盖，重叠遮挡自然消失。副作用：三方通话期间用户无法再发起新呼叫（符合业务预期），但需确认 `safePlaceCall` 返回 false（如信道忙）时拨号盘保留是否为期望交互。

## 复盘与经验
- 浮窗类 UI 的"弹层生命周期"要与通话状态机绑定：状态切换（1路→多路）时主动清理互斥浮层，否则必然出现遮挡类 bug。
- "号码归属地不完整"这类表面现象常是遮挡问题的误报，复盘时先确认是不是两层 UI 重叠。
- 缺陷单里"验证版本号填写错误"提示：关闭缺陷前核对验证版本，避免同单反复。
