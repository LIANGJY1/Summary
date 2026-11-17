# Logcat 诊断

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：00-methods-and-tools/01-performance-workflow.md · Q38 -->

**Q1: [learning] 只有一条 Logcat 日志（例如 Choreographer 的跳帧警告）时，怎样反查到源码并追到引入版本？**

按六步从设备现场追到源码再返回现场：

1. 保留原始时间戳、priority、tag、pid、tid 和进程名。
2. 去掉数字、地址和对象 ID 等动态字段；例如 `Skipped 42 frames` 中的 42 不是稳定搜索词。
3. 用有区分度的稳定文本执行 `content:"..."` 搜索。
4. 固定源码 tag，查看日志调用周围的条件分支与参数来源。
5. 追调用者，记录 debug flag、系统属性或 build type 是否控制该日志。
6. 返回设备证据，核对进程、线程、版本与触发条件。

以跳帧日志为例：AAOS13 源码核对，`"The application may be doing too much work on its main thread"` 位于 `frameworks/base/core/java/android/view/Choreographer.java`，触发条件是 `skippedFrames >= SKIPPED_FRAME_WARNING_LIMIT`，默认阈值 30、来自系统属性 `debug.choreographer.skipwarning`。这条日志只说明主线程进入 `doFrame()` 时已比预期回调时间晚了若干帧间隔，不能证明耗时发生在某个业务方法，也不能逐条对应 42 个已提交的坏帧。字面量搜不到时依次尝试：tag 常量与日志方法、字符串稳定片段、error code 或 stats atom、调用栈里的类与方法、生成源的输入文件、厂商仓库。

追版本历史用 Git 与 Gerrit：`git log -S'字符串'` 找该字符串出现次数变化的提交，`-G'正则'` 找 diff 行匹配的提交。`git blame` 只显示最近一次逐行修改，格式化或重构会覆盖归属，需要配合 `-S`/`-G` 与父提交阅读。提交消息里的 `Change-Id: I...` 是 Gerrit 评审的稳定标识，可到 AOSP Gerrit 查 patch set、评审意见与关联 bug。
