# SIR-8577 · 点击系统按键缺少系统按键提示音

- **提交**：`3e14b5df` | 2026-09-17 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 待测试验证 · 域 系统需求

## 问题
点击系统按键时没有按键提示音——声音设置里的"系统提示音"开关打开后，系统层并未生效。

## 根因分析
`SoundEffectFragment` 中系统提示音开关（`swSystemSound`）的状态只同步到了车端信号层：观察 `settingVehicleService` 的开关 LiveData 后仅更新 UI 的 `isChecked`，以及走 `getL2A(Constants.SYS_TIPS_SOUND_SW)` / 开关回调写 CAN 信号；**从未写入 Android `Settings.System`**。系统按键音由系统框架读取 `Settings.System.SOUND_EFFECTS_ENABLED` 决定是否播放，该值一直保持默认（关闭），所以无论车端信号开关是开是关，按键音都不响。元数据定性为"需求遗漏"：当初实现只做了车控信号通路，漏了系统设置通路。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundEffectFragment.kt、component/CommonTools/src/main/java/com/yadea/common/utils/SettingsUtils.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundEffectFragment.kt
             viewLifecycleOwner, Observer { value ->
                 log("received TheLock Alert Switch：$value")
                 mBinding.swSystemSound.isChecked = value == 1
+                SettingsUtils.setSysSetting(Settings.System.SOUND_EFFECTS_ENABLED, value)
             })
--- component/CommonTools/src/main/java/com/yadea/common/utils/SettingsUtils.kt
+    // 写入 System 设置 Int
+    fun setSysSetting(key: String, value: Int): Boolean {
+        return ContextGet.applicationContext()?.contentResolver?.run {
+            Settings.System.putInt(this, key, value)
+        } == true
+    }
```

## 为什么能修复
开关状态变化（含开机时从车端信号同步 initial value）即把 0/1 写入 `Settings.System.SOUND_EFFECTS_ENABLED`，系统框架按键音通路读取该值后按开关播放/静音，需求闭环。写在 Observer 里保证了"信号恢复/其他端修改"时系统设置也被拉平，双存储最终一致。隐患：value 为 null 时 `value == 1` 为 false 会写入 0（关），若车端信号异常会导致提示音被意外关闭；另外 `SOUND_EFFECTS_ENABLED` 是系统标准键，修改需系统权限（平台签名应用无碍，普通应用会失败且这里未检查返回值）。

## 复盘与经验
- "车端信号 + 系统设置"双通路的需求（提示音、亮度、语言等）最容易漏掉系统侧通路，功能联调要以系统实际行为（按键响不响）为验收，而不是设置页开关状态。
- 设置值写入要放在状态 Observer 中而不是点击回调里，才能覆盖开机同步/远端修改等所有状态来源。
- `SettingsUtils` 这类工具对象按"get/put 成对"补齐 API，本提交补 `setSysSetting` 就是在补历史上只有 get 的不对称设计。
