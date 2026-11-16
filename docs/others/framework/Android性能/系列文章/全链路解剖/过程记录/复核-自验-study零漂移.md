# 复核自验 · study 全部改动源码文件零代码漂移

> 全量复核自验项 2。对象：AAOS13_study 相对精树 AAOS13 的全部改动源码文件（AppList 战役批05~12 批注集，39 文件）。判据：批注仅为注释添加，代码行零净变更。
> 方法复现 批12-过程记录.md §七.1：`grep -v '[一-龥]'` 行级剥 CJK（精树→study），diff 的 d/c（删除/变更）操作必须为 0；ins 残留须为注释/空行脚手架。
> 基准：精树 `/home/liang/Project/AAOS13`（只读）；study `/home/liang/Project/MyProject/AAOS13_study`。脚本 /tmp/review-applist/r7/cjk_drift_check.sh。

## 一、范围

`git status --porcelain` 过滤源码扩展名（.java/.cpp/.c/.h/.cc，排除 docs/）得 39 文件，恰为 AppList 战役批注触及集：
- 输入链（批05~07）：InputManagerService.java、SystemServer.java、EventHub.cpp、InputReader.cpp、MultiTouchInputMapper.cpp、TouchInputMapper.cpp、InputListener.cpp、InputDispatcher.cpp/.h、InputTransport.cpp、VelocityTracker.cpp（native）。
- 应用消费/帧调度（批08~09）：InputEventReceiver.java、VelocityTracker.java（java）、ViewRootImpl.java、RecyclerView.java、android_view_InputEventReceiver.cpp、Choreographer.java。
- HWUI/RT（批10）：HardwareRenderer.java、Bitmap.cpp、SkiaOpenGLPipeline.cpp、CanvasContext.cpp、DrawFrameTask.cpp、EglManager.cpp。
- BufferQueue/BLAST（批11）：Surface.java、BLASTBufferQueue.java、BLASTSyncEngine.java、BLASTBufferQueue.cpp、BufferQueueConsumer.cpp、BufferQueueProducer.cpp、SurfaceComposerClient.cpp、Surface.cpp。
- SurfaceFlinger（批12）：BufferLayer.cpp、BufferStateLayer.cpp、Output.cpp、DispSyncSource.cpp、EventThread.cpp、MessageQueue.cpp、VsyncConfiguration.cpp、SurfaceFlinger.cpp。

## 二、结果

**39/39 文件 d/c=0，drift_files=0。** 逐文件 ins 残留（剥 CJK 后）：

| 文件 | d/c | ins | 文件 | d/c | ins |
|---|---|---|---|---|---|
| Choreographer.java | 0 | 1 | InputManagerService.java | 0 | 75 |
| InputEventReceiver.java | 0 | 3 | SystemServer.java | 0 | 4 |
| Surface.java | 0 | 1 | BLASTSyncEngine.java | 0 | 1 |
| VelocityTracker.java | 0 | 1 | BLASTBufferQueue.cpp | 0 | 1 |
| ViewRootImpl.java | 0 | 18 | BufferQueueConsumer.cpp | 0 | 2 |
| RecyclerView.java | 0 | 9 | BufferQueueProducer.cpp | 0 | 2 |
| android_view_InputEventReceiver.cpp | 0 | 1 | Surface.cpp | 0 | 0 |
| BLASTBufferQueue.java | 0 | 0 | SurfaceComposerClient.cpp | 0 | 9 |
| HardwareRenderer.java | 0 | 2 | InputTransport.cpp | 0 | 3 |
| Bitmap.cpp | 0 | 0 | VelocityTracker.cpp | 0 | 6 |
| SkiaOpenGLPipeline.cpp | 0 | 0 | InputListener.cpp | 0 | 0 |
| CanvasContext.cpp | 0 | 1 | InputDispatcher.cpp | 0 | 2 |
| DrawFrameTask.cpp | 0 | 2 | InputDispatcher.h | 0 | 1 |
| EglManager.cpp | 0 | 0 | EventHub.cpp | 0 | 1 |
| InputReader.cpp | 0 | 2 | MultiTouchInputMapper.cpp | 0 | 2 |
| TouchInputMapper.cpp | 0 | 42 | BufferLayer.cpp | 0 | 0 |
| BufferStateLayer.cpp | 0 | 1 | Output.cpp | 0 | 3 |
| DispSyncSource.cpp | 0 | 0 | EventThread.cpp | 0 | 1 |
| MessageQueue.cpp | 0 | 1 | VsyncConfiguration.cpp | 0 | 0 |
| SurfaceFlinger.cpp | 0 | 5 | | | |

## 三、ins 残留性质核验（防无 CJK 的代码新增漏网）

剥 CJK 后 ins 残留共两类：空行/空注释标记（//、/*、*/、*）脚手架，以及含 ASCII 内容的注释行（英文注释、架构图、代码示例、含 →/。 等非 CJK 字符故未被 grep 剥除）。机械过滤：

1. 全部残留行中"非纯脚手架"行 42 条，逐条核验**全部以注释标记（// / /* / */ / *）开头**——非注释行＝0（/tmp/review-applist/r7/cjk_drift/noncomment.txt 空）。即 42 条均为注释内 ASCII 内容（如 InputEventReceiver.java 的用法示例注释、InputManagerService.java 的 JNI 架构图注释、Choreographer.java 引英文 log 句注释），非裸代码。
2. 交叉佐证：全 39 文件 `git diff` 的删除行（^-），过滤注释行与含 CJK 行后**剩余 0**——所有删除行均为被修正的旧批注注释行（含 CJK 或纯注释标记），无代码行删除。

## 四、正规口径再验（台账§六复核指南 E）

指南 E 指定唯一可靠口径：剥含 CJK 全范围（一-鿿、　-〿、＀-￯）的行后，difflib SequenceMatcher 非 insert 型 opcode（delete/replace）必须为 0（比 §二 的 [一-龥] 行级 grep 更宽，含 CJK 标点/全角；能捕获空行删除型漂移，即 drawSoftware 教训）。以此正规口径对 39 文件再跑（cjk_drift_canonical.py）：

**39/39 文件 noninsert=0，drift_files=0。** 与 §二 grep 口径结果一致，且更强（剥除更多批注行后代码-only 比对仍零 delete/replace）。

## 五、结论

study 39 个改动源码文件相对精树**仅注释添加（CJK 批注＋ASCII 批注内容）与空行，代码行零删除零变更**：grep 口径 d/c=0、删除行全为旧批注、ins 残留全为注释/空行；正规口径（指南 E）SequenceMatcher noninsert=0。两口径互证，与战役各批"零代码漂移"声明一致。自验项 2 通过。

留痕：/tmp/review-applist/r7/cjk_drift/{summary.txt, nonscaffold.txt, noncomment.txt}＋cjk_drift_check.sh＋cjk_drift_canonical.py。