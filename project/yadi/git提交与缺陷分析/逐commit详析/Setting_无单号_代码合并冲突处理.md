# 无单号 · 代码合并冲突处理
- **提交**：`c3bba499` | 2026-08-20 | sgh | Setting | feature（实为合并冲突清理，无新功能）
- **关联单**：无

## 需求/目标
清理两分支合并后遗留的重复/残留代码：删除旧座椅按钮方案的死代码、去掉 strings 中重复的 tab 文案、并把恢复出厂确认里的档位信号换回统一 ID。

## 实现结构
- `SystemFragment.kt`（1 行）：`getAnyProperty(PCU_ACTUALGEARFEED)` → `getAnyProperty(ENERGY_PCU_ACTUALGEAR)`，档位判断统一用 ENERGY 前缀信号。
- `VehicleControlFragment.kt`（-21 行）：删除 `createSeatTouchListener` 工厂函数——19e1553b 改用长按弹窗方案后，旧按钮触摸监听器已无调用方，此处移除最后残留。
- `strings.xml`（-9 行）：删除注释 `<!--新增-->` 下重复定义的 5 个 tab_* 文案（与上方同名条目冲突）。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt
-                        settingVehicleService.getAnyProperty(CarPropertyIds.PCU_ACTUALGEARFEED)
+                        settingVehicleService.getAnyProperty(CarPropertyIds.ENERGY_PCU_ACTUALGEAR)
```
纯维护型提交：三处都是合并冲突"两边都保留"或"选错边"的典型产物（重复 string 定义、旧方案死函数、错误信号 ID），没有行为新增。

## 复盘与要点
- 合并冲突处理提交单独落库是好的实践：diff 里全是清理，出问题容易定位回滚。
- 死代码（createSeatTouchListener）从"注释停用"到"彻底删除"经历了一个提交周期，说明团队接受先保留观察再清理的节奏。
- `PCU_ACTUALGEARFEED` vs `ENERGY_PCU_ACTUALGEAR` 这类同义信号并存是冲突高发点，信号 ID 应有单一权威清单。
