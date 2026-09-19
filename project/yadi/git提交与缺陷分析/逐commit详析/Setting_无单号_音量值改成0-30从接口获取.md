# 无单号 · 音量值改成 0-30 从接口获取
- **提交**：`fe80b0bf` | 2026-09-17 | sgh | Setting | feature
- **关联单**：无（[why] 标注"需求变更"）

## 需求/目标
音量设置页的进度条量程需求变更：原来写死 0-39，改为从车机音频管理接口动态读取各音频组的真实最大音量（兜底值同步从 39 改为 30）。

## 实现结构
- `ui/viewmodel/VolumeViewModel.kt`（+11/-3）：`setSeekbarView` 初始化各音量条时，`seekBar.max` 从硬编码 39 改为调用 `getMaxVolumeForUsage(groupId)` 按音频组取真实上限；其中第 5 个（索引 5，媒体条）特殊地用 `AUDIO_VOLUME_GROUP_MEDIA` 的最大值；`getMaxVolumeForUsage` 的空指针兜底从 39 改 30。
- `ui/fragment/VolumeFragment.kt`（+2/-1）：通话音量条下限钳制条件 `progress <= 5` 改为 `progress < 5`（5 本身不再被强制拉回），并补一条 setVolume 日志。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/VolumeViewModel.kt
+/**
+ * 需求变更，音量值改成0-30从接口取
+ */
+val max =
+    if (indexOf == 5) getMaxVolumeForUsage(CarAudioManager.AUDIO_VOLUME_GROUP_MEDIA) else getMaxVolumeForUsage(
+        groups[indexOf]
+    )
+log("setSeekbarView:min:0,max:$max")
 seekBar.min = 0
-seekBar.max = 39
+seekBar.max = max
```

```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/VolumeViewModel.kt
 fun getMaxVolumeForUsage(groupId: Int) =
-    settingVehicleService.getCarAudioManager()?.getGroupMaxVolume(groupId) ?: 39
+    settingVehicleService.getCarAudioManager()?.getGroupMaxVolume(groupId) ?: 30
```

实现讲解：把量程从"编译期假设"改为"运行期询问音频服务"，不同车型/音频配置下进度条刻度自动对齐真实能力，避免用户拖到 39 但系统只认 30 的静默截断。`indexOf == 5` 的特殊分支暗示第 6 个音量条（媒体）此前就与 `groups` 数组存在错位/特判的历史。

## 复盘与要点
- **量程类 UI 永远不要硬编码上限**：硬件能力（最大音量、温度区间等）必须运行期从服务读取+合理兜底，写死数值换车型必炸。
- **边界条件改动要盯语义**：`<= 5` 改 `< 5` 表面上是一字符修复"progress=5 也被重置"的抖动，实际改变了通话音量最小档位语义（现在 5 是合法值可直达），回归时需重点验证最低档行为。
- **遗留**：`indexOf == 5` 用魔法下标关联音频组，数组顺序一变即错位；建议映射为显式的组→条配置。
