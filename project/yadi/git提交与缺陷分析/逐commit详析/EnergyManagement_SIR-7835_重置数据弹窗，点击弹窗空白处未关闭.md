# SIR-7835 · 能量中心重置数据弹窗点击空白处不关闭
- **提交**：`f2856001` | 2026-09-08 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
里程管理页的"重置数据"确认弹窗（TextDialog），点击弹窗外空白遮罩区域不会关闭，不符合常规交互预期。

## 根因分析
`MileageManagementActivity` 构建 `resetConfirmationDialog`（TextDialog，文案"小计里程数据重置后不可恢复"）后显式设置 `isEnableClickMask = false`。TextDialog 的这一属性为 false 时会同步禁用"点击遮罩取消"与 Dialog 本身的 cancel 行为——点击空白处完全没有响应。这是开发者为了防误触（重置不可恢复）主动关掉的，但交互需求实际要求空白处可关闭弹窗，两相矛盾，属于交互属性设置与需求不符。修复就是把开关翻回 true，交还给 TextDialog 的遮罩点击关闭逻辑。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt
```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MileageManagementActivity.kt
@@ -417,7 +417,8 @@
             "重置数据",
             "小计里程数据重置后不可恢复", "重置", "取消"
         )
-        resetConfirmationDialog!!.isEnableClickMask = false
+        // Allow dismissing the confirmation dialog by tapping the surrounding mask.
+        resetConfirmationDialog!!.isEnableClickMask = true
         resetConfirmationDialog!!.setCallback(object : Callback {
             override fun confirm(content: Any?) {
```

## 为什么能修复
`isEnableClickMask = true` 恢复 TextDialog 的遮罩点击取消与 Dialog cancel 行为，点击空白即 dismiss，满足交互需求。副作用需留意：该弹窗动作"重置后不可恢复"，放开空白关闭后误触遮罩会更轻易地退出确认——但退出只是不执行重置，不会误操作数据，风险可接受；真正要防的"误点重置按钮"由按钮位和二次文案保障。

## 复盘经验
- 封装弹窗组件的开关属性（isEnableClickMask）常常一开关联动多个行为（遮罩点击 + cancel），用前要看清组件实现，别按字面理解。
- "防误触"与"交互一致性"冲突时，默认遵循平台通用交互（点空白关闭），数据安全靠确认文案与按钮布局保障。
- 这类一行翻转的缺陷最容易在代码评审漏掉——评审弹窗代码时应问一句"空白点击行为符合交互稿吗"。
