# SIR-2617 · 蓝牙来电挂断后弹窗消失过慢（1.56s，标准 0.5s）

- **提交**：`4ea4ff41` | 2026-07-28 | dufan | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙来电点击拒接后，通话浮窗平均 1.56 秒才消失，超过 0.5s 的交互响应标准。

## 根因分析
拒接后浮窗的消失原先完全依赖底层链路回调：`FloatCallWindowPresenter` 调用 `UiCallManager.get().rejectCall(mPrimaryCall, false, null)` 后就结束了，UI 要等 Bluetooth HFP/telecom 侧把"通话已断开"事件一路回调上来（`onCallRemoved` 类回调），再触发 `showEmptyUI()`。而蓝牙协议栈拒接指令的往返加 对端网络拆链耗时不可控，平均约 1.5s。缺陷库根因"回调后处理消失逻辑过迟"即指此：UI 消失时机被动绑定了最慢的一环（底层回调），而拒接这个用户动作本身在本地已是确定事实，完全可以直接驱动 UI。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java；application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java
                 LogUtils.d(TAG, "reject primaryCall :" + mPrimaryCall);
                 //拒接来电
                 UiCallManager.get().rejectCall(mPrimaryCall, false, null);
+                // 当前挂断的电话是唯一的通话时，马上隐藏通话浮窗
+                if (UiCallManager.get().getCalls().isEmpty()) {
+                    getView().showEmptyUI();
+                }
                 break;
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/UiCallManager.java
         if (telecomCall != null && mPhoneForward != null) {
             LogUtils.d(TAG, "UiCallManager rejectCall(uiCall) telecomCall= " + telecomCall.toString());
             mPhoneForward.rejectCall(telecomCall, rejectWithMessage, textMessage);
+            doRemoveUiCall(uiCall);
         } else {
```

## 为什么能修复
两处改动形成"本地立即反馈"通道：`UiCallManager.rejectCall` 在发出底层拒接指令后立刻 `doRemoveUiCall(uiCall)` 把本地通话列表（`mCallMapping`）摘除该通话，`getCalls()` 随即为空；`FloatCallWindowPresenter` 紧接着判断 `getCalls().isEmpty()` 就直接 `getView().showEmptyUI()` 隐藏浮窗。UI 消失从"等底层回调"变成"点击即响应"，耗时降到毫秒级。保守性保护：只有"当前是唯一通话"时才立即隐藏，多通话场景（等待/三方通话）仍走原有回调路径，避免误清界面。隐患：本地列表先行摘除后，若底层拒接实际失败，浮窗已消失而通话还在，依赖后续回调兜底重新刷新。

## 复盘与经验
- **UI 响应不要绑死在远端回调上**：对用户已明确发起的本地动作（挂断/拒接），应采用"乐观更新"立即反馈，远端结果仅用于校准，否则耗时上限就是对端与链路的总和。
- **回调驱动的消失时机要测量**：蓝牙电话这类跨进程+无线的链路，回调延迟应以实测数据（如本例 1.56s）驱动优化，而非默认"回调很快"。
- **乐观更新要留条件分支**：立即隐藏前检查 `getCalls().isEmpty()`，把"唯一通话"与"多通话"分开处理，避免优化主路径时破坏三方通话等复杂场景。
