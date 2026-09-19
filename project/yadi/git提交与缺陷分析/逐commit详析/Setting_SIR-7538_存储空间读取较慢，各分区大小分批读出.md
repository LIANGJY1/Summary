# SIR-7538 · 存储空间读取慢，DVR 连接后主动拉取各分区大小
- **提交**：`afca564e` | 2026-09-07 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：get dvr 信息逻辑问题）

## 问题
安全监控页的存储空间（循环录像/紧急保存/图片/驾驶辅助各分区）读取很慢，进入页面后长时间不显示或迟迟不出全量数据。

## 根因分析
页面原来完全依赖 `IDvrCallBackListener` 的被动回调来获得各分区大小，而 DVR 服务的回调时机滞后（缺陷库记为"DVR 服务回调太慢"）：服务建立连接后并不保证立刻推送全量 video data，只有等底层慢慢回调，UI 就一直空着。修复思路是把"等推送"改成"连接即拉取"：在 `onConnect()` 里新增主动调用 `getVideoData()`，通过 `DvrLocalServiceMng.getInstance().getVideoData(...)` 分别请求 `Constant.VideoType.CYCLE_RECORD`、`EMERGENCY_SAVE`、`PICTURE`、`DRIVING_ASSISTANT_RECODE` 四类分区数据，连接建立即触发一次全量读取，回调数据随后刷新 UI。同提交还重构了 `StorageUsageBarView.onDraw`：改为 `path.addRoundRect` 裁剪画布后直接 `drawRect` 按真实比例画矩形，替代原先手工计算首尾段圆角的 `addRoundRect` 画法，顺带修复小占比分段"竖线戳出圆角"的绘制问题。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt、application/Setting/src/main/java/com/yadea/setting/ui/widget/StorageUsageBarView.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt
@@ -353,17 +352,28 @@
+    /**
+     * 主动获取进度
+     */
+    fun getVideoData(){
+        log("getVideoData 0,1,2,3")
+        DvrLocalServiceMng.getInstance().getVideoData(Constant.VideoType.CYCLE_RECORD)
+        DvrLocalServiceMng.getInstance().getVideoData(Constant.VideoType.EMERGENCY_SAVE)
+        DvrLocalServiceMng.getInstance().getVideoData(Constant.VideoType.PICTURE)
+        DvrLocalServiceMng.getInstance().getVideoData(Constant.VideoType.DRIVING_ASSISTANT_RECODE)
+    }
+
     private val dvrCallback = object : IDvrCallBackListener {
         override fun onConnect() {
             log("dashcam onConnect")
             dvrConnected = true
             setDashcamGray(true)
             refreshDvrStates()
+            getVideoData()
         }
--- application/Setting/src/main/java/com/yadea/setting/ui/widget/StorageUsageBarView.kt
@@ -39,29 +39,25 @@
+        canvas.save()
+        path.reset()
+        path.addRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, Path.Direction.CW)
+        canvas.clipPath(path)
         for (i in sizes.indices) {
             val s = sizes[i]
             if (s <= 0f) continue
             val segW = w * s / total
             rectF.set(left, 0f, left + segW, h)
             paint.color = colors[i]
-            canvas.drawPath(path, paint)
+            canvas.drawRect(rectF, paint)
             left += segW
         }
+        canvas.restore()
```

## 为什么能修复
"被动等回调"变成"连接成功即主动请求一次全量数据"，消除了对 DVR 服务回调时序的依赖，进入页面后存储信息能及时到齐——这正是缺陷库 sol 写的"dvr 连接成功后主动获取一次存储信息"。绘制重构用 clipPath 保证任何窄段都不会溢出圆角轨道，鲁棒性更好。副作用：`getVideoData()` 每次 onConnect 都会发 4 次请求，若 DVR 反复断连重连会重复请求，但量级小、可接受；clipPath 对抗锯齿圆角有轻微性能开销，自定义小控件内可忽略。

## 复盘经验
- 依赖外部服务回调刷新 UI 的页面，"连接成功后主动拉一次全量"是消除时序依赖的标准手法；纯被动等待等于把展示时机交给对方。
- 分段进度条画圆角，先 clip 轮廓再画矩形，比逐段手算圆角方向简单且不会出现"细段戳出圆角"的瑕疵。
- UI 控件绘制重构与数据时序修复混在同一提交，回溯定位时应按文件分开阅读。
