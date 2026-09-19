# SIR-7973 · 连点多媒体按键后重复 toast 排队连弹
- **提交**：`9f7e69ea` | 2026-09-09 | caohongliang | CommonTools | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（根因：toast 会进入队列依次显示）

## 问题
蓝牙已连接且手机端无音乐播放时，按住多媒体 BTN 连续点击再松手，"无音乐"类相同 toast 一条接一条排队弹出，持续很久。

## 根因分析
公共组件 `ToastUtils`（object 单例，CommonTools）的 `showCenterToast` 原实现每次调用都直接 inflate `layout_custom_toast` 并 `toast.show()`；配合内部 `messageQueue`（LinkedList）+ `processNext()` 队列机制，同一文案的 toast 每次点击都入队，显示完一条再弹下一条（各 3 秒），连点 N 次就要弹 N 次，形成"一直弹 toast"。队列没有按内容去重/合并，是设计缺陷而非偶发。

## 关键代码修改
改动文件：component/CommonTools/src/main/java/com/yadea/common/utils/ToastUtils.kt
```diff
--- component/CommonTools/src/main/java/com/yadea/common/utils/ToastUtils.kt
@@ -119,21 +104,57 @@
         if (Looper.myLooper() != Looper.getMainLooper()) {
             handler.post { enqueueToast(toastMsg) }
             return
         }
+        if (currentToastMessage?.message == toastMsg.message) {
+            // 正在显示的同文案 toast：取消当前，立即显示最新一条
+            messageQueue.removeAll { it.message == toastMsg.message }
+            currentToastRemoveRunnable?.let(handler::removeCallbacks)
+            currentToast?.cancel()
+            currentToast = null
+            currentToastMessage = null
+            currentToastRemoveRunnable = null
+            showToast(toastMsg)
+            return
+        }
+        val iterator = messageQueue.listIterator()
+        while (iterator.hasNext()) {
+            if (iterator.next().message == toastMsg.message) {
+                // 队列中已有同文案：移除旧的，把最新请求挪到队尾
+                iterator.remove()
+                messageQueue.add(toastMsg)
+                return
+            }
+        }
         messageQueue.add(toastMsg)
         processNext()
     }
```
同时：`showCenterToast` 重构为构造 `ToastMessage` 后统一走 `enqueueToast`；显示逻辑抽为 `showToast()`，记录 `currentToastMessage` 与 `currentToastRemoveRunnable`（可取消的移除任务）；`cancelAllClear` 同步清空新状态；注释口径从"按队列显示，3S 消失"改为"不同内容按队列，相同内容只保留最新，2 秒消失"。

## 为什么能修复
入队路径增加两级同文案合并：正在显示的同文案 toast 被立即替换（cancel+重弹最新），队列中的同文案被去重后仅保留最新一条，连点 N 次最终只弹 1~2 次，队列不再被刷爆。`currentToastRemoveRunnable` 句柄化解决了原来 postDelayed 任务无法取消的问题（替换时旧定时任务不会误清新 toast）。副作用：duration 统一按 LENGTH_SHORT 的 getToastDuration 计算，原先注释的 3 秒口径变为 2 秒，属交互规格变更需产品确认；跨线程调用经 handler.post 回主线程，时序安全。

## 复盘与经验
- 全局 Toast 单例必须内置"同文案去重/替换"策略，物理按键连击、信号风暴场景下 toast 队列会无限膨胀，这是车机 UI 的经典坑。
- 用 postDelayed 做延时清理时保留 Runnable/Message 句柄以便取消，否则"替换当前 toast"类逻辑无法正确实现。
- 公共组件的行为变更（3s→2s）要在注释与提交信息中显式声明，避免下游模块按旧口径做自动化验证。
