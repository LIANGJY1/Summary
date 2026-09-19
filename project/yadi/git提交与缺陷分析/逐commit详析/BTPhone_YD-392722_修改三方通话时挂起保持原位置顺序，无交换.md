# YD-392722 · 三方通话挂起后界面两路通话位置交换

- **提交**：`f13e9093` | 2026-07-06 | duanlonglong | BTPhone | bugfix
- **缺陷库**：未关联单号（提交带 YD-392722 单号，缺陷库 defs 为空）

## 问题
三方通话中把当前通话挂起（Hold）并接通另一路时，悬浮窗上下两路通话的号码/归属地/按钮发生整体对调，位置顺序改变；期望挂起后各通话保持原位置顺序，不交换。

## 根因分析
`FloatCallWindow.inflateThreeWayHoldingView()` 旧实现按**状态**绑定视图：`uiCallManager.getCallWithState(Call.STATE_ACTIVE)` 填上方布局、`getCallWithState(Call.STATE_HOLDING)` 填下方布局。两路通话的 ACTIVE/HOLDING 状态一互换，同一槽位填充的通话对象就跟着换，号码、归属地、计时与挂断/接听按钮全部错位；且 ACTIVE 为 null 时还会退化到 `getPrimaryCall()` 兜底，绑定更不可控。这是典型的"以易变状态为 key 绑定 UI"，而非以稳定的通话实体为 key。另外 `float_three_way_holding_window.xml` 里第二路号码直接写死了 `android:text="111222333444555666"`，绑定前会闪现假号码。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java`（+265/-204 主体重写）、`.../telecom/UiBluetoothMonitor.java`、`float_three_way_holding_window.xml`、`float_three_way_incoming_window.xml`、`float_three_way_outgoing_window.xml`、`float_outgoing_window.xml`
```diff
// --- FloatCallWindow.java inflateThreeWayHoldingView()：按通话列表序绑定+动态约束调位
-        UiCall call = uiCallManager.getCallWithState(Call.STATE_ACTIVE);
-        UiCall secondCall = UiCallManager.get().getCallWithState(Call.STATE_HOLDING);
+        List<UiCall> hasCalls = uiCallManager.getCalls();
+        if(hasCalls.size() < 2){
+            showMiniFlolatUI();
+            return;
+        }
+        UiCall firstCall = hasCalls.get(0);
+        UiCall secondCall = hasCalls.get(1);
+        // 根据通话状态动态调整布局：通话中(ACTIVE)显示在上方，挂起中(HOLDING)显示在下方
+        boolean firstCallIsActive = (firstCall.getState() == Call.STATE_ACTIVE);
+        if (!firstCallIsActive) {
+            secondParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;   // ACTIVE 移到顶部
+            firstParams.topToBottom = R.id.secondCallLayout;                    // HOLDING 排其下
+            ...
+        }
```
```diff
// --- float_three_way_holding_window.xml：移除写死的假号码
-            android:text="111222333444555666"
+            android:text=""
```
配套改动：`UiBluetoothMonitor` 新增 `getBluetoothHeadsetClient()` 供通话保持/切换操作使用（FloatCallWindow 内注释"需暴露此方法"）；三路通话布局清理 `tools:text` 占位。

## 为什么能修复
改为按 `getCalls()` 列表序（稳定）绑定"第几路通话→第几个布局"，同一路通话的号码/计时/按钮始终在同一视图中，不再随 ACTIVE/HOLDING 翻转而互换；位置仅通过 ConstraintLayout 参数动态调整保证 ACTIVE 在上；<2 路通话时降级为迷你悬浮窗，消除空绑定。隐患：布局调位仍以"ACTIVE 在上"为原则，极端时序下（两次状态回调查看之间）可能有一帧错位；454 行大重写也放大了回归面。

## 复盘与经验
- **列表 UI 绑定要以稳定实体为 key，不要以易变状态为 key**：状态（ACTIVE/HOLDING）只能决定样式/位置，不能决定"谁显示在哪个槽位"，否则状态一翻转内容全换。
- **布局 XML 里禁止写死演示数据到 `android:text`**（`tools:text` 才是预览专用），否则数据未绑定前会显示假号码。
- **渲染前先做数量守卫**：`calls.size() < 2` 提前降级，避免对 null/缺路通话做强解。
