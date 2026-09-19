# 无单号 · DVR 行车记录仪存储信息不刷新（依赖一次性连接回调）

- **提交**：`badf0ed5` | 2026-09-11 | sgh | Setting | bugfix（未关联单号）
- **缺陷库**：未关联单号

## 问题
行车记录仪（DVR）已连接的情况下，安全监测页的存储信息/格式化按钮不刷新、长期置灰。

## 根因分析
`SafetyMonitorFragment` 用成员布尔 `dvrConnected` 记录 DVR 连接态，但它只靠 `dvrCallback.onConnect` 这**一次性回调**置 true；页面销毁重建（或进入时 DVR 服务本就已连接）时会错过 onConnect，`dvrConnected` 永远是 false。旧 `initDvrService()` 还有个方向性错误：`if (!dvrManager.isConnect) setDashcamGray(false)` 只处理"未连接置灰"，**对"已连接"没有任何主动拉取**，既不刷存储进度也不取视频数据，UI 就停在灰态（提交 [why]：dvr 服务已连接未刷新，[how]：进入界面就主动获取一次）。此外格式化按钮与 `updateFormatGrayState()` 都读 `dvrConnected` 而非服务实时态，同样被脏标记拖累。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt（1 文件 +16/-17）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt
@@ initDvrService()
         dvrManager.initAndConnService(requireContext().applicationContext)
         dvrManager.setDvrCallBackListener(dvrCallback)
-        if (!dvrManager.isConnect) {
-            setDashcamGray(false)
+        //DVR服务已连接时主动拉去数据刷新UI
+        if (dvrManager.isConnect) {
+            refreshDvrStates()
+            getVideoData()
+        } else {
+            updateFormatGrayState()
         }
@@ updateFormatGrayState()
-        val enabled = dvrConnected && hasUsb
+        val enabled = dvrManager.isConnect && hasUsb
@@ 格式化按钮点击守卫
-            if (!dvrConnected || !hasUsb) {
+            if (!dvrManager.isConnect || !hasUsb) {
```
（删除 `setDashcamGray()` 整体置灰函数及其 4 处调用，统一走 updateFormatGrayState/updateLoopRecordGrayState；onConnect 回调与可见恢复 pendingUiRefresh 分支同步改用统一刷新）

## 为什么能修复
三个关键点：① 进入界面时若服务已连接，立刻 `refreshDvrStates()+getVideoData()` 主动拉取，不再等可能错过的事件；② 连接态判断全部改为服务实时字段 `dvrManager.isConnect`，消除错过 onConnect 后永久置灰的脏标记；③ 删除整体置灰函数后，各控件灰态由各自以实时态计算，回调和可见恢复路径也统一。风险：高频调用 `isConnect` 若其实现含 binder 调用需注意开销（通常是缓存字段，安全）。

## 复盘与经验
- 绑定型服务（bind 后回调通知连接）的经典陷阱："回调只在状态变化时发生"，页面重建时必须主动查询当前态（init 时 if(alreadyConnected) 拉一次），事件+查询两条腿缺一不可。
- 用服务实时态替代本地镜像布尔（dvrConnected）做 UI 判定，可整体消灭一类"标记与实际脱节"的 bug。
- 删除冗余的整体置灰函数、收敛到单一刷新入口，与 9bdf8eb8（迎宾互斥置灰）是同一重构思路：单一状态函数 + 全触发点。
