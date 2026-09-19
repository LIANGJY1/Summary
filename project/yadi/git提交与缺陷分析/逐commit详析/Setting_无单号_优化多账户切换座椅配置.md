# 无单号 · 优化多账户切换座椅配置

- **提交**：`d2257e2c` | 2026-07-17 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
重构多账户下的座椅记忆配置链路：槽位换算、保存状态、名称存储统一收敛到 `SeatUserManager`，去掉散落在 `SettingVehicleService`/`UserConfigManager` 的转发方法与 SP 双写，并为每个用户的 3 个座椅槽位增加"是否已保存"状态。

## 实现结构
4 个文件（+149/-263，净瘦身）：
- `common/manager/SeatUserManager.kt`：API 全面改为"内部从 GSetting 读当前 `USER_ID`"，调用方不再传 userId；删除 `getSeatNamesFromSP`（SharedPreferences 兜底路径，统一只走 JSON 缓存）、删除 `setSelectedBy3D/setSeatPositionNameBy3D` 两个 3D 车模适配方法；新增 `savedStatus` JSONArray（3 槽位）及 `setSeatSaveStatus/isSeatPositionSaved`；`getSelectedPosition(position)` 改名 `getSavePosition` 并在 start==-1 时返回 -1；方法普遍去掉 Boolean 返回值、清理 `?.also{}` 式啰嗦日志写法；
- `ui/fragment/VehicleControlFragment.kt`：`selectSeatPosition` 先 `getSavePosition(position)` 换算成用户专属槽位，`isSeatPositionSaved` 为 false 时提示"当前座椅位置为空"直接返回；召回成功回调里 `saveSelectedPosition`，保存成功后 `setSeatSaveStatus(position, true)`；新增多处 400ms `debugSimulateResponse` 模拟对手件回复（联调用）；
- `utils/UserConfigManager.kt`：删除 `saveSeatPosition/saveSeatPositionName/getSeatPositionNames` 转发方法；`sendSeatConfig` 改为返回 Boolean（供判断是否发送成功）；
- `init/SettingVehicleService.kt`：删除同名转发方法；`debugSimulateResponse` 临时置注释。

数据流：UI 点击 → Fragment（槽位换算 + 已保存校验）→ `CCU_SEATPOSITIONMEMORYRECALL/SET` 车控信号 → 硬件回调 → `SeatUserManager` 落盘（users/{uid}/seatConfig）。

## 关键代码
```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/manager/SeatUserManager.kt
+    @Synchronized
+    fun setSeatSaveStatus(absolutePosition: Int, saved: Boolean) {
+        ...
+        val relativeIndex = absolutePosition - startPosition
+        if (relativeIndex !in 0..2) return
+        seat.put("savedStatus", JSONArray().apply {
+            val oldArray = seat.optJSONArray("savedStatus")
+            for (i in 0..2) {
+                val oldValue = oldArray?.optBoolean(i, false) ?: false
+                put(if (i == relativeIndex) saved else oldValue)
+            }
+        })
+        saveToDisk()
+    }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
-        seatTriggerTimeoutJob?.cancel()
-        updateSeatPositionButtonsUI(position)
-        seatPositionPendingState = position
+        //计算选中位置槽位
+        val selectedPosition =
+            SeatUserManager.getSavePosition(position).takeIf { it != -1 } ?: position
+        //当前位置是否保存过
+        val savedState= SeatUserManager.isSeatPositionSaved(selectedPosition)
+        if(!savedState){
+            showToast("当前座椅位置为空")
+            return
+        }
+        updateSeatPositionButtonsUI(selectedPosition)
+        seatPositionPendingState = selectedPosition
         settingVehicleService.sendVehicleProperty(
             CarPropertyIds.CCU_SEATPOSITIONMEMORYRECALL,
-            position
+            selectedPosition
         )
```
实现讲解：核心是"按用户分槽"——每个用户在 JSON 缓存里有 `startPosition`（如 A 用户从 4 开始），UI 的 1/2/3 通过 `start + position - 1` 映射到全局 1~18 的信号槽位，不同账户互不覆盖。`savedStatus` 数组让空槽位在召回前就被拦截，而不是等车控无响应。

## 复盘与要点
- 好的收敛方向：把 userId 的获取下沉到 Manager 内部，调用方不再可能传错用户；删掉 SP 双写消除了"JSON 与 SP 不一致"这一类多账户脏数据问题。
- 遗留风险 1：`getSavePosition` 失败时 Fragment 用 `?: position` 兜底回 UI 原始值 1~3，游客或未注册用户会拿到与真实槽位语义不同的编号，依赖车控侧对游客的处理。
- 遗留风险 2：diff 中大量 400ms `debugSimulateResponse` "开发测试模拟对手件回复"代码随功能一起提交（同日 `004dd2c7` 才注释掉），测试脚手架与生产代码混在同一提交，回归时需甄别。
