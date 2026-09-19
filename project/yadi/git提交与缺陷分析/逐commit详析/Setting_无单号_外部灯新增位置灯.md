# 无单号 [SRS_VehSetting_017] 外部灯新增位置灯
- **提交**：`027da812` | 2026-08-19 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_017

## 需求/目标
外部灯光选择器补上"位置灯"档位图标：此前 d27b53ee 扩到 4 档（OFF/位置灯/近光/AUTO）时位置灯档位临时复用了近光灯图标，本提交为其新增专用矢量图标。

## 实现结构
- `LightFragment.kt`：`mixedItems` 第 2 项（位置灯）iconResId 由 `light_loomlight` 改为 `light_loom_position`，一行改动。
- 新增 `drawable/light_loom_position.xml`：36dp 矢量图（径向灯丝造型，填充色 #3C4558，evenOdd 填充规则）。
- 无逻辑/信号改动——发信号仍直接用 position（0~3），与 d27b53ee 确立的"UI 索引=信号值"契约一致。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
             val mixedItems = listOf(
                 MixedItem(text = "OFF"),
-                MixedItem(iconResId = R.drawable.light_loomlight),
+                MixedItem(iconResId = R.drawable.light_loom_position),
                 MixedItem(iconResId = R.drawable.light_loomlight),
                 MixedItem(text = "AUTO")
             )
```
纯资源补充型提交：结构上验证了 d27b53ee 预留的 4 档 UI 骨架只需换图标即可扩展档位，不需要动 listener 与回显。

## 复盘与要点
- 用矢量 drawable（而非 mdpi 位图）做状态灯图标，一处定义、日夜模式可用 tint 适配，是该模块图标资源更优的路线。
- 档位扩展只改资源列表一行，说明 `ImageTextRadioGroup.setMixedItems` 的数据驱动设计有效。
