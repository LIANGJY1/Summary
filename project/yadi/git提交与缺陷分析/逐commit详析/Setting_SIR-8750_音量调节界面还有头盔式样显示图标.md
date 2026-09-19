# SIR-8750 · 音量调节界面残留头盔图标，按 SRS 删除
- **提交**：`56a24978` | 2026-09-18 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 待测试验证 · 域 车控车设

## 问题
音量调节界面仍显示"头盔"式样图标，而最新 SRS（软件需求规格）已删除头盔图标显示。

## 根因分析
产品需求变更（耳机方案从"头盔"演进为普通"耳机/头戴设备"）后，音量界面代码未同步：`VolumeFragment.setLeftImg()` 中蓝牙音量组图标取 `chooseIcon(isZero, R.drawable.ic_helmet_off, R.drawable.ic_sound_helmet)`，双蓝牙从机/主机场景直接 `setImageResource(R.drawable.ic_sound_helmet)`；同时存在一套废弃的 `SoundFragment`（+`fragment_sound.xml`、`SoundViewModel`、`ic_helmet_off.xml`、`ic_sound_helmet.xml`）仍在代码库中，语音入口 `VoiceOperationUtil` 也保留 `"open_sound" -> SoundFragment::class.java` 映射。旧 UI 资产与新需求并存，导致头盔式样图标继续出现。缺陷库 rc"UI变更"、sol"修改界面"属需求对齐型修复。

## 关键代码修改
改动文件：VolumeFragment.kt、VoiceOperationUtil.kt、VolumeViewModel.kt（删除 SoundFragment.kt、SoundViewModel.kt、fragment_sound.xml、fragment_sound_effect.xml 局部、ic_helmet_off.xml、ic_sound_helmet.xml，-937 行）
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/VolumeFragment.kt
-        val helmetIcon = chooseIcon(isZero, R.drawable.ic_helmet_off, R.drawable.ic_sound_helmet)
+        val helmetIcon = chooseIcon(isZero, R.drawable.ic_headphones_off, R.drawable.ic_headphones)
...
             isBtSlaveEnabled && isBtMasterEnabled -> {
-                mBinding.sbMedia.ivLeft.setImageResource(R.drawable.ic_sound_helmet)
-                mBinding.sbCall.ivLeft.setImageResource(R.drawable.ic_sound_helmet)
+                mBinding.sbMedia.ivLeft.setImageResource(R.drawable.ic_headphones)
+                mBinding.sbCall.ivLeft.setImageResource(R.drawable.ic_headphones)
```
```diff
// application/Setting/src/main/java/com/yadea/setting/utils/VoiceOperationUtil.kt
-            "open_sound" -> SoundFragment::class.java
+//            "open_sound" -> SoundFragment::class.java
```
（删除整个 `SoundFragment`/`SoundViewModel`/`fragment_sound.xml` 头盔版音量页及头盔图标 drawable。）

## 为什么能修复
把蓝牙音量组、双蓝牙连接场景的图标全部换成 `ic_headphones`/`ic_headphones_off`，视觉与最新 SRS 对齐；同时删除整套废弃的头盔版音量页（SoundFragment 家族）与其 drawable，从根上消灭"旧式样再出现"的可能，语音入口映射一并注释，避免语音跳转到已删除语义的页面。大删除量（-937 行）属于清理技术债的正向 bugfix。风险：若后续需求又恢复头盔形态，需要重新实现；已删除资源的引用必须清理干净（本提交同步删除了引用点，无悬空资源）。

## 复盘与经验
- 需求变更型"bug"（SRS 删除某式样）的修复要点是"换图标 + 删旧资产 + 断入口"三步到位，只换图标不删旧页会留下第二次出现的通道（如语音跳转）。
- 图标语义命名（`ic_sound_helmet` → `ic_headphones`）应跟随产品概念演进重命名，避免"helmet"字样误导后续开发。
- 大体量删除提交要检查全部引用点（布局、代码、语音路由），确认无悬空引用后再提交。
