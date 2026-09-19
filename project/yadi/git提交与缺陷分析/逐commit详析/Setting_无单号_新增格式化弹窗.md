# 无单号 [SRS_SYSDVRSetting_005] 新增格式化弹窗
- **提交**：`a96638b6` | 2026-08-25 | sgh | Setting | feature（diff 实为文案资源 + 哨兵模式默认值，弹窗接线在后续提交）
- **关联单**：SRS_SYSDVRSetting_005

## 需求/目标
为行车记录仪"格式化存储"准备确认弹窗文案（标题"存储格式化"+ 数据不可恢复警示），并在哨兵模式定时弹窗（ScheduleSentryModeDialog）给起止小时设置默认值（22 点 ~ 8 点）。

## 实现结构
- `values/strings.xml`、`values-en/strings.xml`：新增 format_storage_title / format_storage_content 两条（内容为"格式化将清除U盘内全部录像、图片及文件，数据不可恢复…"）。
- `ScheduleSentryModeDialog.kt`：npvStartHour 默认 value=22、npvEndHour 默认 value=8，作为夜间哨兵时段的出厂默认。
- 弹窗本身未见新增类——按仓库惯例应复用通用 TextDialog/showWarningDialog；当前工作区代码中 SafetyMonitorFragment 已通过这些 key 组装确认弹窗与结果 toast（format_storage_no_storage/action/success/fail 等），说明本提交是文案先行，按钮接线由相邻提交补齐。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/ScheduleSentryModeDialog.kt
         mBinding.npvStartHour.apply {
             refreshByNewDisplayedValues(hourArray)
+            value=22
             setOnValueChangedListener { _, _, _ -> }
         }
...
         mBinding.npvEndHour.apply {
             refreshByNewDisplayedValues(hourArray)
+            value=8
             setOnValueChangedListener { _, _, _ -> }
         }
```
```xml
<!-- application/Setting/src/main/res/values/strings.xml -->
+    <string name="format_storage_content">格式化将清除U盘内全部录像、图片及文件，数据不可恢复。若存有重要证据或资料,请先备份。确定继续格式化吗?</string>
+    <string name="format_storage_title">存储格式化</string>
```
破坏性操作（格式化）的确认文案强调"数据不可恢复 + 先备份"，符合车机存储操作的合规话术；哨兵模式默认 22:00-08:00 覆盖夜间，属于合理出厂值。

## 复盘与要点
- values-en 里 format_storage_content 实际是中文文案（直接复制了中文文件内容），英文包会显示中文——本批提交第二次出现"英文资源漏翻"，建议接入资源 lint 检查。
- 提交标题与 diff 内容不完全对应（只加了文案和默认值），依赖相邻提交才构成完整功能，细粒度拆分有利于回滚但也要求提交信息更准确。
- NumberPicker 默认值硬编码（22/8），若产品要按冬夏季调整需改代码，可考虑下沉到配置或资源。
