# 无单号 · 座椅位置记忆选择和加热逻辑优化

- **提交**：`7521f5c4` | 2026-07-15 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
重构座椅记忆"召回触发"的确认协议并优化加热档位交互：废除两阶段校验，改为"匹配回调 + 0x1E 失败信号 + 1 秒超时兜底"的单层状态机；结束信号改走无回滚通道；快速点击加热按钮按已下发未确认值递进；同时补一套模拟硬件回复的调试设施。

## 实现结构
改动 4 个文件（+125/-37）：
- `ui/fragment/VehicleControlFragment.kt`（主体）：
  - `handleSeatPositionTrigger` 状态机简化：`pendingState==null` 时回调仅刷 UI；`1..18` 时，回调 `==30`(0x1E) 判失败、`==期望值` 判成功并 `sendVehiclePropertyNoRollback` 下发结束信号、其余不匹配回调**丢弃且不取消超时**；
  - 新增 `seatTriggerTimeoutJob`：下发召回后 1 秒无匹配回调则主动 `readVehicleProperty` 读实际值刷 UI；`onDestroyView` 统一取消。
  - 加热点击：`isInvalidClick` 过滤连击，下一档位基准从"已确认值"改为 `getTemp().takeIf { it >= 0 } ?: previousState`（未确认的临时值），快速连点不跳档；位置编辑/保存按钮同样加 `isInvalidClick`。
  - 顺带新增 HUD 辅助驾驶开关 observer。
- `init/SettingVehicleService.kt`：新增 `sendVehiclePropertyNoRollback()`（结束信号等无需超时回滚的场景）与 `debugSimulateResponse()` 调试入口。
- `component/Carlib/.../CarServiceManager.kt` + `PropertyRollbackManager.kt`：调试链路透传——`handlePropertyChanged(propertyId)` 取消超时定时器后，把 `CarPropertyValue(propertyId,0,value)` 经 `AppCarPropertyValue.convertCarPropertyValue` 转换并 post 到 `callback.onChangeEvent`，完整复刻硬件回复路径。

数据流：点击记忆位 → 下发 `CCU_SEATPOSITIONMEMORYRECALL=position`（自动挂回滚定时器）+ 启动 1s 超时 Job → SCU 回调：成功(匹配)/失败(0x1E)/噪声(丢弃) → 成功后发 `=0` 结束信号（无回滚）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt（节选）
+                    // 匹配期望位置，触发成功
+                    position == seatPositionPendingState -> {
+                        seatTriggerTimeoutJob?.cancel()
+                        seatTriggerTimeoutJob = null
+                        showToast(getString(R.string.car_control_seat_trigger_success))
+                        // 下发结束信号（不需要超时回滚）
+                        settingVehicleService.sendVehiclePropertyNoRollback(
+                            CarPropertyIds.CCU_SEATPOSITIONMEMORYRECALL,
+                            0
+                        )
+                        seatPositionPendingState = null
+                    }
+                    // 不匹配的回调，丢弃，不取消超时，等待超时兜底
+                    else -> {
+                        logObserve("[Seat Trigger] Discard mismatched callback: $position, expecting: $seatPositionPendingState")
+                    }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt（超时兜底，节选）
+        seatTriggerTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
+            delay(1000)
+            if (mBinding.root.isAttachedToWindow && seatPositionPendingState != null) {
+                seatPositionPendingState = null
+                val actualPosition = settingVehicleService.readVehicleProperty(CarPropertyIds.CCU_SEATPOSITIONMEMORYRECALL)
+                updateSeatPositionSelectUI(actualPosition)
+            }
+        }
```
实现讲解：旧协议要求硬件回两个信号（位置 + 完成信号 31）且任一不匹配立即判失败，实战中易被乱序/噪声回调误杀。新协议的关键取舍是"宽容匹配 + 超时兜底"：只有 0x1E 明确失败或 1 秒超时才判负，噪声回调既不判胜也不判负，超时后以实读信号值为准刷 UI——把"对时序的依赖"换成"对最终状态的依赖"。调试链路则把 `PropertyRollbackManager` 打造成可注入的假硬件，无对手件台架上即可回归整条确认协议。

## 复盘与要点
- 车控确认协议的通用范式可提炼为三分支状态机：`明确失败 / 明确成功 / 无关回调`，超时后永远以"读实际状态"收尾，比多阶段握手更适合不可靠 CAN 环境。
- "结束信号走无回滚通道"是个容易被忽略的细节：结束信号(=0)本身不是要等待确认的控制指令，若复用带超时回滚的 `sendVehicleProperty` 会制造虚假的失败回滚。区分指令语义并选择对应通道，值得沉淀为接口约定。
- 遗留风险：调试入口 `debugSimulateResponse` 已合入主干（虽以 TODO 注明测试完成后删除，且调用处已注释），发布版若被误用会伪造硬件回复；建议构建变体隔离（debug-only source set）。
