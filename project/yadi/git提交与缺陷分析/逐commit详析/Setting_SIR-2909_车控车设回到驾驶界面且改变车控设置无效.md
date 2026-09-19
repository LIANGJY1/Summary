# SIR-2909 · 车控车设回到驾驶界面后改变车控设置无效
- **提交**：`84758f20` | 2026-07-20 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
从车控车设回到驾驶界面后，再改变车控设置（白天/黑夜显示模式）无效。

## 根因分析
提交消息仅写"代码逻辑问题"。diff 显示根因在 `DisplayViewModel.setDisplayMode()`：写 `THEME_SHOW_MODE` 时用的是 `Settings.Global.putInt(context.contentResolver, ...)` 直写，而项目内其他模块（含本 app 的读取方）通过 `SettingsUtils.setGSetting()` 这一套全局 Settings 封装读写。两条路径写到的命名空间/用户上下文不一致（车机多用户/系统封装下 `SettingsUtils` 通常带用户或 authority 处理），导致车控侧写入的值其他监听方读不到，或读取方读的是另一份存储，表现为"设置不生效"。改为统一走 `SettingsUtils.setGSetting()` 后读写通道一致。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/DisplayViewModel.kt`

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/DisplayViewModel.kt
     fun setDisplayMode(context: Context, position: Int) {
         val uiManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
-        Settings.Global.putInt(
-            context.contentResolver,
-            THEME_SHOW_MODE,
-            position
-        )
+        SettingsUtils.setGSetting(THEME_SHOW_MODE, position)
         when (position) {
             MODE_AUTO -> uiManager.nightMode = UiModeManager.MODE_NIGHT_AUTO
             MODE_DAY -> uiManager.nightMode = UiModeManager.MODE_NIGHT_NO
```

## 为什么能修复
消除了"写 A 读 B"的存储通道分裂：所有对 `THEME_SHOW_MODE` 的读写统一到 `SettingsUtils` 封装，回到驾驶界面后 Launcher/其他监听方能收到一致的值变化，车控设置重新生效。`uiManager.nightMode` 的实际模式切换逻辑未动，改动面极小、无副作用——前提是全仓库确实统一走 `SettingsUtils`，若有第三处仍直写 `Settings.Global` 则问题会换地方复现。

## 复盘与经验
- 同一 Settings key 全仓库必须只有一个读写通道；封装类（SettingsUtils）存在的意义就是屏蔽多用户/权限差异，绕开它直调 Settings API 是这类"设置不生效"的典型来源。
- "回到某界面后设置失效"常提示读写方处于不同用户/上下文，排查时先比对两边的 key 名与写入 API 是否完全一致。
- 缺陷单写"代码逻辑问题"等于没写；本例真正教训是 PR 阶段对绕过封装的 API 调用要保持敏感。
