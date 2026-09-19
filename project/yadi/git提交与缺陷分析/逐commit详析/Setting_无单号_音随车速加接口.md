# SRS_SYSSetting_008 · 音随车速加接口

- **提交**：`27131b98` | 2026-08-31 | sgh | Setting | feature
- **关联单**：SRS_SYSSetting_008（标题引用，无系统单号）

## 需求/目标
"音随车速（速度音量补偿）"此前只有 UI 占位（日志 `[Placeholder]`），本提交接入 `CarAudioManager.speedCompensationLevel` 真实接口，实现四档（关/低/中/高）的读取回显与下发。

## 实现结构
仅改动 `SoundEffectFragment.kt`：
- `initSoundFollowSpeedMode()`：初始化时从 `settingVehicleService.getCarAudioManager()?.speedCompensationLevel` 读取当前档位，按 `AUDIO_SPEED_COMPENSATION_LEVEL_OFF/LOW/MIDDLE/HIGH` 四个系统常量映射到 `rgSoundFollowSpeed` 的 0~3 索引。
- `setupSoundFollowSpeedListener()`：选择回调把 position 反向映射为系统常量写回 `speedCompensationLevel`，替换原占位日志。
- 方法补 `@RequiresPermission("android.car.permission.CAR_CONTROL_AUDIO_VOLUME")` 权限注解。

数据流：CarAudioManager（车机音频服务）↔ Fragment 单选组，读（init 回显）与写（onItemChecked 下发）走同一属性，无中间缓存。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundEffectFragment.kt
@@ -109,8 +109,26 @@
+    @RequiresPermission("android.car.permission.CAR_CONTROL_AUDIO_VOLUME")
     private fun initSoundFollowSpeedMode() {
-        mBinding.rgSoundFollowSpeed.setSelectedIndex(mSoundFollowSpeedMode)
+        val level = settingVehicleService.getCarAudioManager()?.speedCompensationLevel
+        when (level) {
+            CarAudioManager.AUDIO_SPEED_COMPENSATION_LEVEL_OFF -> mBinding.rgSoundFollowSpeed.setSelectedIndex(0)
+            CarAudioManager.AUDIO_SPEED_COMPENSATION_LEVEL_LOW -> mBinding.rgSoundFollowSpeed.setSelectedIndex(1)
+            CarAudioManager.AUDIO_SPEED_COMPENSATION_LEVEL_MIDDLE -> mBinding.rgSoundFollowSpeed.setSelectedIndex(2)
+            CarAudioManager.AUDIO_SPEED_COMPENSATION_LEVEL_HIGH -> mBinding.rgSoundFollowSpeed.setSelectedIndex(3)
+        }
     }
```
```diff
@@ -130,7 +150,23 @@
             override fun onItemChecked(position: Int, text: String) {
                 mSoundFollowSpeedMode = position
-                logClick("[Placeholder] Sound follow speed mode: $position")
+                when (position) {
+                    0 -> settingVehicleService.getCarAudioManager()?.speedCompensationLevel =
+                        CarAudioManager.AUDIO_SPEED_COMPENSATION_LEVEL_OFF
+                    ...
+                }
             }
```

实现讲解：典型的"占位换真身"提交：不动 UI 结构，只把读写两端的假实现换成 Android Car 音频接口，且读写都引用 `CarAudioManager` 官方枚举常量而非魔法数字，值域由平台保证。`?.` 安全调用使 audio manager 未就绪时静默不崩。

## 复盘与要点
- 读写共用同一系统枚举常量是最省心的接口设计；对比驾驶模式（`e0903b2f`）那种 req/sts 双编码信号，能用平台标准属性就不要自造协议。
- 遗留风险：只做了进入页面时的一次性回显，外部（如语音助手）改了档位时页面不会感知；when 未加 else 分支，系统返回未知值时 UI 停留原状，属可接受的静默降级但缺日志。
