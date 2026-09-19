# SIR-3241 · 把手加热连点两次显示停在1档（信号重复上报+延迟反馈双重驱动）

- **提交**：`b7addf45` | 2026-07-23 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 3D车模

## 问题
车端发送把手档位信号 0 档时，点击两次把手加热按键，3D 车模把手加热显示停在 1 档（预期应为 2 档）。

## 根因分析
日志定位出双重机制叠加（提交 [why] 写得很完整）：① 一次物理点击附近，Kanzi 会**重复上报** `Button.Handlebar value=1`（间隔约 386ms），旧逻辑对每次上报都执行 `getNextHandleHeatLevel(mHandleHeatLevel)` 推进一档——第一次上报把 0 档切到预期档位后，386ms 后的重复上报又推进了一档；② 旧的点击路径里还有一个 `HANDLEBAR_FEEDBACK_DELAY_MS=1000` 的延迟反馈任务 `mHandleHeatFeedbackRunnable`，它可能用**旧请求的结果**再次刷新 UI，把车端真实回调（`CCU_SETHANDLEHEATSWREQ`）刚更新的档位回滚，最终显示停在 1 档。两个"驱动源"（重复点击上报 + 延迟反馈）都不信任唯一事实源。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
     private static final long HANDLEBAR_FEEDBACK_DELAY_MS = 1000L;
+    private static final long HANDLEBAR_CLICK_DEBOUNCE_MS = 600L;
@@ 成员变量
+    private long mLastHandlebarClickTimestamp = 0L;
@@ CCU_SETHANDLEHEATSWREQ 信号回调（车端真实状态到达）
         signalHandlers.put(CarPropertyIds.CCU_SETHANDLEHEATSWREQ, event -> {
             mHandleHeatLevel = (int) event.getValue();
+            if (mHandleHeatFeedbackRunnable != null) {
+                mHandler.removeCallbacks(mHandleHeatFeedbackRunnable);
+                mHandleHeatFeedbackRunnable = null;
+            }
             updateHandlebarStateToKanzi();
         });
@@ KanziType.Button.HANDLEBAR 点击处理
             case KanziType.Button.HANDLEBAR:
+                long now = System.currentTimeMillis();
+                if (now - mLastHandlebarClickTimestamp < HANDLEBAR_CLICK_DEBOUNCE_MS) {
+                    LogUtils.d(TAG, "Handlebar heat click ignored by debounce: delta=" + (now - mLastHandlebarClickTimestamp));
+                    break;
+                }
+                mLastHandlebarClickTimestamp = now;
                 int previousHandleLevel = mHandleHeatLevel;
```

## 为什么能修复
① 600ms 防抖把 386ms 间隔的重复上报挡掉，一次物理点击只推进一档；② 车端真实回调到达时 `removeCallbacks` 清除未执行的延迟反馈任务，UI 只剩"车端回调"这一个事实源刷新，旧结果回滚通道被关死。防抖窗口(600ms) > 重复上报间隔(386ms)、小于正常双击间隔，取舍合理。隐患：`mLastHandlebarClickTimestamp` 只覆盖把手按钮，同类按钮（座椅加热等）的重复上报问题需同样排查；极端网络下真实回调若超过 1 秒未到且延迟任务已被移除，UI 将保持点击前的乐观值直到回调到达。

## 复盘与经验
- 输入驱动 UI 的链路要假设"同一事件可能上报多次"：按键类交互默认加防抖，间隔取"重复噪声间隔 < 防抖 < 人类双击间隔"。
- "乐观更新 + 延迟反馈兜底"与"真实回调"并存时，真实回调到达必须撤销未执行的兜底任务（removeCallbacks），否则兜底会用过期数据反杀新状态。
- 性能好的复现 bug 先拉日志量化（此处 386ms、1000ms 都是日志给出的），修复参数直接从数据来，不靠拍脑袋。
