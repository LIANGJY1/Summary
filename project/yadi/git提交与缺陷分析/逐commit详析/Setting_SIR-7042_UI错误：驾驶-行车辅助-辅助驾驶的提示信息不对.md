# SIR-7042 · 驾驶-行车辅助-辅助驾驶提示信息不对
- **提交**：`167b86a8` | 2026-09-01 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
驾驶 → 行车辅助 → 辅助驾驶介绍弹窗的提示文案与 UI 设计稿不一致（标点、换行显示异常）。

## 根因分析
`dialog_assist_intro.xml` 引用的 `adas_info_one` ~ `adas_info_four` 四段文案存在两处问题：功能名与说明之间的分隔符用了半角冒号"："位置错误（原文是半角 ":"，设计稿要求全角"："）；且换行符写成字面量 `\n`（转义反斜杠 + n），Android 字符串里不会被解析为换行，整段文本连排显示错乱。另外弹窗正文 TextView 缺少行间距设置，多行显示时行距过密。该提交顺带在 `SettingVehicleService` 增加了 `onCommState` 信号日志去重缓存并注释掉高频错误日志，属降噪夹带。

## 关键代码修改
改动文件：`application/Setting/src/main/res/values/strings.xml`、`res/layout/dialog_assist_intro.xml`、`.../init/SettingVehicleService.kt`
```diff
--- application/Setting/src/main/res/values/strings.xml
-    <string name="adas_info_one">前方碰撞辅助:前向车辆存在碰撞风险时,系统将发出预警
-\n前方穿行辅助:...
+    <string name="adas_info_one">前方碰撞辅助：前向车辆存在碰撞风险时,系统将发出预警
+\n前方穿行辅助：...
（adas_info_one~four 四段同样修正分隔符）

--- application/Setting/src/main/res/layout/dialog_assist_intro.xml
                         android:layout_marginTop="@dimen/dp_18"
+                        android:lineSpacingExtra="4sp"
                         android:text="@string/adas_info_one"
```
（`SettingVehicleService.kt` 新增 `lastCommStateValues` HashMap 对 `onCommState` 日志去重，并注释 `onVehicleAdasError` 的 debug 日志。）

## 为什么能修复
文案按设计稿修正分隔符并保证换行语义，配合 `lineSpacingExtra="4sp"` 恢复多行排版。注意：本提交把字面量 `\n` 前的反斜杠保留在资源里——若编译期按字符串字面量处理，`\n` 在 Android 资源中会被解析为换行符（资源编译器处理 `\n` 转义），显示恢复多行。风险仅限该弹窗文案回归；夹带的日志去重逻辑把"首见值"记录后才打日志，首个值会漏打一次日志，无功能影响。

## 复盘与经验
- 字符串资源中的换行必须写真正的转义 `\n`（或 `&#10;`），写成 `\` 换行加字面 n 会导致整段连排；评审文案类 diff 要重点盯转义。
- 中文文案的全角/半角标点应有一份统一规范并在 UI 走查前核对。
- 信号回调日志高频刷屏会淹没日志缓冲，按 id+value 去重是低成本降噪手段，但要注意首个值也会被吞。
