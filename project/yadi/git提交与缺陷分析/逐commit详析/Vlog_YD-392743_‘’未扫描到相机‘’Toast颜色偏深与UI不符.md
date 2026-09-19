# YD-392743 · "未扫描到相机"Toast颜色偏深与UI不符

- **提交**：`838a8309` | 2026-06-27 | daizhecheng | Vlog (+CommonTools) | bugfix（UI 视觉修复 + Toast 机制重构）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
"未扫描到相机"等提示 Toast 的背景色偏深，与 UI 设计稿不符；同时存在多处 Toast 叠加弹出、退出进程后 Toast 仍残留的体验问题。

## 根因分析
视觉问题的直接根源在 `component/CommonTools/.../drawable/bg_toast_round.xml`：背景色引用 `@color/bg_toast`，该令牌取值偏深，未跟上新设计稿的中性灰（`#545F72`）。体验问题的根源在 `ToastUtils` 的用法：各页面直接调 `showMsgToast`/`showCenterToast`，系统 Toast 各自为政——连续触发时叠加排队错乱、应用 `killProcess` 退出时无 Toast 被取消。本提交一行改色修复视觉，同时把全模块 Toast 调用统一切到新增的 `ToastUtils.showCenter` 队列化实现（`LinkedList` 队列 + `currentToast` 单例 + Handler 定时 2000/3500ms 顺序消费），并在 `BaseActivity.onLapseDownExit` 退出前调用 `cancelAll()`。

## 关键代码修改
改动文件：component/CommonTools/src/main/java/com/yadea/common/utils/ToastUtils.kt、component/CommonTools/src/main/res/drawable/bg_toast_round.xml、application/Vlog/src/main/java/com/yadea/vlog/base/BaseActivity.kt、application/Vlog/.../capture/CaptureActivity.kt、application/Vlog/.../main/ui/HomeActivity.kt

```diff
--- component/CommonTools/src/main/res/drawable/bg_toast_round.xml
@@ Toast 背景色按设计稿校正
-    <solid android:color="@color/bg_toast"/>
+    <solid android:color="#545F72" />
```

```diff
--- component/CommonTools/src/main/java/com/yadea/common/utils/ToastUtils.kt
@@ 新增队列化单实例 Toast
+    private var currentToast: Toast? = null
+    private val messageQueue = LinkedList<ToastMessage>()
+    private val handler = Handler(Looper.getMainLooper())
+
+    fun showCenter(context: Context, message: String, ...) {
+        messageQueue.add(toastMsg); processNext()
+    }
+    private fun processNext() {
+        if (messageQueue.isEmpty() || currentToast != null) return
+        val toastMsg = messageQueue.poll() ?: return
+        ...inflate(R.layout.layout_custom_toast)...设置文本/图标/重力...
+        currentToast = toast; toast.show()
+        handler.postDelayed({ currentToast?.cancel(); currentToast = null; processNext() },
+                getToastDuration(Toast.LENGTH_SHORT))
+    }
+    fun cancelAll() { handler.removeCallbacksAndMessages(null); currentToast?.cancel(); messageQueue.clear() }
```

```diff
--- application/Vlog/.../base/BaseActivity.kt
@@ 全部调用切到 showCenter；退出前清 Toast
-        ToastUtils.showMsgToast(this@BaseActivity, message!!)
+        ToastUtils.showCenter(this@BaseActivity, message!!)
@@ onLapseDownExit
         instaCameraManager.closeCamera()
+        ToastUtils.cancelAll()
         finishAffinity()
```

## 为什么能修复
改 `bg_toast_round` 的 solid 色即让所有自定义 Toast 底色变浅，视觉对齐设计稿（注意：把令牌 `@color/bg_toast` 改成写死 `#545F72` 是"反向硬编码"，色值正确但丢了主题化能力）；`showCenter` 用"队列 + 同一时刻仅一个 Toast + 定时 cancel 再取下一条"消除叠加错乱，`cancelAll` 保证杀进程前清理。隐患：`processNext` 靠 `postDelayed` 驱动、无 Looper 判空外的线程防护（调用须在主线程）；写死的 2000/3500ms 与系统 Toast 实际时长并不严格同步，个别机型上仍可能有边缘时序差异。

## 复盘与经验
- **一处 drawable 全局生效**：公共组件的视觉修正优先找资源文件，而不是逐页面覆盖。
- **修色时别把令牌改回硬编码**：正确做法是改 `bg_toast` 令牌值（或拆日/夜），本提交选择了最快的硬编码，留下主题化债务。
- **Toast 需要全局编排**：单例队列 + 顺序消费 + 生命周期取消（退出/切页时 `cancelAll`）是车载等长亮屏场景的实用模式。
