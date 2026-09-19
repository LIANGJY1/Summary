# SIR-8119 · 上下电后开机音乐/系统提示音开关状态不被记忆

- **提交**：`93df0152` | 2026-09-11 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
车机正常上下电后，"开机音乐"和"系统提示音"开关没有记住用户设置的状态，重新上电后回到默认值。

## 根因分析
系统提示音开关在应用侧通过 L2A（Local-to-App/车控信号）通道读写：`SoundFragment`/`SoundEffectFragment` 用 `settingVehicleService.getL2A/sendL2A` 读写信号 `Constants.LOCK_SOUND_SWITCH`（即 `"Lock_Sound_Switch"`），`SettingVehicleService` 的信号回调按该 key 分发到 `lockSoundSwitch` LiveData。但底层（CCU/车控侧）已把"系统提示音开关"信号变更为新信号 `"Sys_Tips_Sound_SW"`，旧 `Lock_Sound_Switch` 信号不再承载该设置（或不被持久化），应用读写的是废弃信号，上下电后自然取不到记忆值。缺陷库根因"信号变更"准确：这是车端信号定义变更后应用未同步的对接类缺陷。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/Constants.java`、`application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt`、`application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundEffectFragment.kt`、`application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt`
```diff
// Constants.java
-    public static final String LOCK_SOUND_SWITCH = "Lock_Sound_Switch";
+    public static final String SYS_TIPS_SOUND_SW = "Sys_Tips_Sound_SW";
```
```diff
// SettingVehicleService.kt（信号回调分发）
-                Constants.LOCK_SOUND_SWITCH -> {
+                Constants.SYS_TIPS_SOUND_SW -> {
                     lockSoundSwitch.postValue(value)
                 }
```
```diff
// SoundFragment.kt / SoundEffectFragment.kt（读写两处同改）
-        settingVehicleService.getL2A(Constants.LOCK_SOUND_SWITCH)
+        settingVehicleService.getGSetting... getL2A(Constants.SYS_TIPS_SOUND_SW)
         mBinding.swSystemSound.setOnCheckedChangeListener {
-            settingVehicleService.sendL2A(Constants.LOCK_SOUND_SWITCH, if (it) 1 else 0)
+            settingVehicleService.sendL2A(Constants.SYS_TIPS_SOUND_SW, if (it) 1 else 0)
         }
```

## 为什么能修复
常量统一改指向新信号 `Sys_Tips_Sound_SW` 后，读（getL2A 拉取初值）、写（开关回调 sendL2A）、回调分发三处全部对齐车端新信号，开关状态随车端持久化，上下电后能正确恢复。常量集中定义在 `Constants.java`、四处引用一次改齐，无遗留旧信号读写。注意点：变量名 `lockSoundSwitch` 沿用未改，语义已与"锁车音"无关，后续维护有误读风险；已按旧信号设置过的用户值可能无法迁移到新信号（车端数据迁移问题，非应用层可控）。

## 复盘与经验
- 与车端信号耦合的设置项，信号更名/换信号必须应用侧同步且读写分发三处（拉取、写入、回调）一起改，漏一处即出现"改了没反应"或"不记忆"。
- 常量集中在 `Constants` 统一管理是对的，但改名时建议连派生变量名（lockSoundSwitch）一起更名，保持语义一致。
- 上下电记忆类缺陷的排查第一步：核对应用读写的信号名与最新车控接口文档是否一致。
