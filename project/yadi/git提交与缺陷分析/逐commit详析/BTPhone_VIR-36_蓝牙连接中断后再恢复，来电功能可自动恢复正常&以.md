# VIR-36 · 蓝牙中断重连后来电不显示人名 / 来电功能自动恢复
- **提交**：`61d92d5b` | 2026-07-16 | hedeyuan | BTPhone | bugfix（含代码同步）
- **缺陷库**：未关联单号（VIR-36 无缺陷库记录）

## 问题
来电时断开蓝牙再重连，再来电悬浮窗不显示联系人姓名；同时包含来电功能在蓝牙中断恢复后自动正常化的多处修补（语音路由、状态机）。

## 根因分析
提交消息自述：断开再重连后 PBAP 通讯录尚未同步完成，来电时 `UiCall.getContactName()` 为空，界面直接显示空人名。代码上 `FloatCallWindow.inflateInComingView` 原本直接 `ViewUtil.updateCallInfo(mContext, call, tvName, ...)`，对 contactName 为空没有任何兜底。同批还修了两个相关缺陷：① 流转来电（TelecomForward）时音频路由无声，需要在来电建立/结束时调 `AnWBT_HFPAG_VoiceRecognition` 让 HFPAG 侧激活/释放语音识别通道；② `InCallUiStateMachine.IncomingState` 对 `Call.STATE_DISCONNECTED`（拒接或对方挂断）未处理，返回 NOT_HANDLED 导致状态机滞留在来电态，下次来电流程异常。本提交是"一个单号带多段修复+代码同步"的混合提交。

## 关键代码修改
改动文件：`application/BTPhone/build.gradle`、`BtPhoneApp.java`、`floatview/FloatCallWindow.java`、`FloatCallWindowPresenter.java`、`FloatWindowManager.java`、`fragment/ContactsFragment.java`、`telecom/dataexchange/TelecomForward.java`、`telecom/telecom/InCallUiStateMachine.java`、`component/Hardwarelibs/.../BtAnwManager.java`、`whitelist/com.yadea.btphone.xml`

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
+        // 蓝牙断开再重连后，流转来电时PBAP尚未同步，contactName为空
+        // 此时通过号码匹配缓存直接回显人名，并同步写入UiCall供后续状态变化使用
+        if (call != null) {
+            if (TextUtils.isEmpty(call.getContactName())
+                    && !TextUtils.isEmpty(mLastPhoneNumber)
+                    && mLastPhoneNumber.equals(call.getNumber())
+                    && !TextUtils.isEmpty(mLastContactName)) {
+                call.setContactName(mLastContactName);   // 缓存命中回显
+            } else if (!TextUtils.isEmpty(call.getContactName())) {
+                mLastContactName = call.getContactName(); // PBAP已同步，更新缓存
+                mLastPhoneNumber = call.getNumber();
+            }
+        }
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/dataexchange/TelecomForward.java
+                        //调用AnWBT_HFPAG_VoiceRecognition，设置语音识别为1，解决蓝牙耳机无声问题
+                        BtAnwManager.getInstance().setVoiceRecognition(1);
                         Call.Details details = telecomCall.getDetails();
@@ (通话结束时)
+                        //调用AnWBT_HFPAG_VoiceRecognition，释放语音识别
+                        BtAnwManager.getInstance().setVoiceRecognition(0);
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/InCallUiStateMachine.java
+            // 来电被拒接或对方挂断，通话变为DISCONNECTED时立即回到空闲状态
+            if (call.getState() == Call.STATE_DISCONNECTED) {
+                LogUtils.d(TAG, "IncomingState: Call disconnected (rejected or remote hangup), transition to IDLE");
+                transitionTo(idleState);
+                return HANDLED;
+            }
```

## 为什么能修复
人名问题：用 `mLastContactName/mLastPhoneNumber` 成对缓存"号码→人名"，PBAP 未同步期间按号码命中缓存回填 `UiCall.setContactName`，PBAP 恢复后又用真实值刷新缓存，保证不显示陈旧错误数据；无声问题：来电建立/结束对称设置 HFPAG 语音识别位，强制音频通道建立；状态机问题：补上 DISCONNECTED → idleState 迁移，防止来电态残留。副作用：缓存策略只能恢复"上次见过的联系人"，陌生新号码在 PBAP 未同步时依旧无名（属可接受降级）；`setVoiceRecognition` 若通话异常中断（ crash/断连）可能漏掉释放 0 的调用。

## 复盘与经验
- 依赖 PBAP 同步完成才有数据的 UI（来电人名），必须为"同步未就绪"设计降级路径：本地缓存按号码匹配是最轻量的兜底。
- 状态机对每个呼叫终态（挂断/拒接/远端取消）都要有显式迁移，漏一个就滞留脏状态，影响后续呼叫。
- "来电建立置位、结束复位"这类成对接口要写在不同但对称的生命周期点上，且要考虑异常路径的泄漏。
