# 无单号 [SRS_SYSSetting_004] 续航里程去掉WLTC和弹出内容
- **提交**：`3a78f2de` | 2026-08-19 | sgh | Setting | feature
- **关联单**：SRS_SYSSetting_004

## 需求/目标
续航里程模式选择从三档（WLTC/WMTC/GB）裁为两档（WMTC/GB）：UI 去掉 WLTC 项、提示文案删除 WLTC 说明，并补上"UI 索引(0/1) ↔ 信号值(1/2)"的映射——此前直接把 position 当信号发，与车端定义（1=WMTC、2=GB）不符。

## 实现结构
仅改 3 个文件：
- `DisplayFragment.kt`：observe 回显 `status → index`（1→0、2→1、其余→0），点击 `position → value`（0→1、1→2、其余→1），双向映射补齐。
- `strings.xml`/`values-en/strings.xml`：`range_mode` 数组注释掉 WLTC 项（保留注释而非删除，便于恢复），`extended_range_mode_tip` 删掉 WLTC 段落。
- 无服务层/信号层改动。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayFragment.kt
                 if (status != null) {
-                    mBinding.rgExtendedRangeMode.setSelectedIndex(status)
+                    val index = when (status) {
+                        1 -> 0
+                        2 -> 1
+                        else -> 0
+                    }
+                    mBinding.rgExtendedRangeMode.setSelectedIndex(index)
                 }
...
                 override fun onItemChecked(position: Int, text: String) {
                     log("extend range mode change：$position")
+                    val value= when (position) {
+                        0 -> 1
+                        1 -> 2
+                        else -> 1
+                    }
                     settingVehicleService.sendVehicleProperty(
                         CarPropertyIds.CRUISE_MILEAGE_DISPLAY_MODE_SETTING,
-                        position
+                        value
                     )
                 }
```
此前的实现隐含假设"信号值=UI索引"，一旦信号定义从 0 起始或跳号就会选错档；本提交显式建立双向映射并各自兜底默认值，同时删除 WLTC 档说明信号侧本来就是 1/2 编号，0 号位是预留。

## 复盘与要点
- "UI 索引≠信号值"时必须在收发两端都写显式 when 映射并注释对应关系；d27b53ee 曾为此把外灯改成 1:1 直映，而续航页因车端定义固定（1/2 起）只能走映射路线——两种路线的选择取决于谁定义协议。
- 注释掉而非删除 string-array 项，是需求可能回退（WLTC 后续车型恢复）时的低成本留痕手法，但长期堆积会变成注释垃圾。
- `else -> 0`/`else -> 1` 兜底不对称：未知信号回显落到 WMTC(0)，未知点击发 WMTC(1)，与"回显到 else 也应该有日志"的排查习惯配合更稳妥。
