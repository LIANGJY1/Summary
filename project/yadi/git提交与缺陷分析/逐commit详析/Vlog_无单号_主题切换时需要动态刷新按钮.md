# 无单号 · Vlog 主题切换时动态刷新连接按钮

- **提交**：`0338cc6e` | 2026-07-21 | daizhecheng | Vlog | feature
- **关联单**：无

## 需求/目标
修复 Vlog 相机配对页 `CameraPairedActivity` 切换主题时，"连接/断开"按钮只被重设文字颜色、没有按连接状态还原完整外观（文字、底色、加载圈）的问题。

## 实现结构
单文件 3+/1-：`main/ui/CameraPairedActivity.kt` 的 `switchTheme()` 中，`btnConnect` 原来被无条件设为白色文字（假定处于"断开中"的红色按钮态），改为调用既有方法 `updateConnectText()`——该方法按 `viewModel.isConnected` 分别还原"断开按钮（白字+红底圆角）"或"连接按钮（默认字色+描边圆圈）"的整套样式。

## 关键代码
```diff
--- a/application/Vlog/src/main/java/com/yadea/vlog/main/ui/CameraPairedActivity.kt
     fun switchTheme() {
         mBinding.let {
             it.btnOpenCapture.setTextColor(resources.getColor(R.color.text_default_default, null))
-            it.btnConnect.setTextColor(resources.getColor(R.color.text_white_default, null))
+            updateConnectText()
             it.btnToSetting.setTextColor(resources.getColor(R.color.text_white_default, null))
```
```kotlin
// 该提交前已存在的 updateConnectText()（节选）
private fun updateConnectText() {
    if (viewModel.isConnected) {
        it.btnConnect.text = getString(R.string.btn_disconnect)
        it.btnConnect.setTextColor(resources.getColor(R.color.text_white_default, null))
        it.rlConnect.background = getDrawable(R.drawable.btn_bg_radio18_red)
        it.ivLoading.visibility = View.GONE
    } else { ... }
}
```
实现讲解：主题切换函数原先把连接按钮硬编码成"已断开"外观的一种颜色，连接状态为"已连接"时切一次主题就会显示错误样式。改为复用按状态刷新的既有方法，一处逻辑两个入口共用。实为小缺陷修复（标题标为 feature）。

## 复盘与要点
- 典型的"样式刷新散落多入口"问题：主题切换、状态变更各自 setColor/background，必然出现不一致；正确形态是所有 UI 状态由单一 render 函数派生，切主题=重放 render。
- 改动虽只有 1 行，但要求 `updateConnectText()` 覆盖按钮全部视觉维度（text/颜色/背景/loading），复用前需确认其完整性。
