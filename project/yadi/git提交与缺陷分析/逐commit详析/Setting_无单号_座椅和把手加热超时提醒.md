# 无单号 · 座椅和把手加热超时提醒

- **提交**：`24f1277f` | 2026-07-25 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
座椅加热/把手加热调节后若车控信号未按预期回复（超时回滚），在 UI 恢复上一档位的同时弹出"座椅加热异常/把手加热异常"提示，让用户知道操作未生效。

## 实现结构
3 个文件（+13/-4）：
- `VehicleControlFragment.kt`：通用的加热档位绑定方法 `setupHeater(...)` 增加 `toastContent: String` 参数；超时回滚分支（`getState() != getTemp()` 时 `restoreUI(previousState)`）追加 `showToast(toastContent)`；
- 两处调用点分别传入新文案 `handle_heating_exception`（把手加热异常）与 `seat_heating_exception`（座椅加热异常）；
- 中英 `strings.xml` 新增两条文案（中文 values 里还顺带提前加入了灯光自动模式的两条 tip 文案，为次日 `f0ebfcaf` 做准备）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
         propertyId: Int,
         restoreUI: (Int) -> Unit,
         levelIcons: IntArray,
-        fallbackIcon: Int
+        fallbackIcon: Int,
+        toastContent: String
     ) {
         iconView.setOnClickListener {
             ...
                 if (getState() != getTemp()) {
                     logObserve("Heater timeout rollback, sent ${getTemp()} but received ${getState()}, restore to level: $previousState")
                     restoreUI(previousState)
+                    showToast(toastContent)
                 }
                 setTemp(-1)
```
实现讲解：该加热组件已有一套"发送值与回调值不一致即回滚"的超时机制（getState/getTemp 对账 + restoreUI），本次只是把静默回滚升级为"回滚 + 告知"，参数化文案使同一组件服务两个功能。改动小而克制。

## 复盘与要点
- "UI 回滚必须伴随用户可感知的反馈"是车控类交互的基本要求，否则用户以为设置成功；此提交补的正是这个缺口。
- 可复用点：把提示文案作为 `setupHeater` 参数而非硬编码，保持组件通用；后续若加"通风/按摩"等同构功能可直接沿用。
- 遗留点：toast 文案"异常"未区分超时与真实故障（车控上报的失败也可能是硬件异常），后续可依据回执类型细分文案。
