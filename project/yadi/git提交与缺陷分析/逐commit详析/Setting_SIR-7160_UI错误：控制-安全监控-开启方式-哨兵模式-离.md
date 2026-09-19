# SIR-7160 · 哨兵模式"离车开启"缺少文言提示

- **提交**：`b0feb27d` | 2026-09-02 | sgh | Setting | bugfix（提交信息误标为 [feature]，JSON 标记 mistag）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
控制-安全监控-开启方式-哨兵模式选择"离车开启"时，界面上缺少 UI 稿要求的说明文字（"离开车辆后，将会启动哨兵模式，启动后将消耗一定电量"）。

## 根因分析
`SafetyMonitorFragment` 的哨兵模式选项切换逻辑（`SENTINEL_MODE_OPTIONS` 写 0/1）只控制了地址栏 `rlAddress` 与时间栏 `rlTime` 的显隐，布局 `fragment_safety_monitor.xml` 中根本没有这个说明 TextView——属于需求实现时遗漏了一个控件（缺陷库根因"需求遗漏"）。修复即补控件 + 在模式分支里挂上显隐逻辑：选"离车开启"（case 0 分支）时 `sentryModeDescriptionTv` 置 VISIBLE，其余分支置 GONE。

## 关键代码修改
改动文件：SafetyMonitorFragment.kt、layout/fragment_safety_monitor.xml、values/strings.xml
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt
                 SettingsUtils.setGSetting(SENTINEL_MODE_OPTIONS, 0)
                 mBinding.rlAddress.visibility = View.VISIBLE
                 mBinding.rlTime.visibility = View.GONE
+                mBinding.sentryModeDescriptionTv.visibility = View.VISIBLE
             }
             else -> {
                 SettingsUtils.setGSetting(SENTINEL_MODE_OPTIONS, 1)
                 mBinding.rlAddress.visibility = View.GONE
                 mBinding.rlTime.visibility = View.VISIBLE
+                mBinding.sentryModeDescriptionTv.visibility = View.GONE
```
```diff
// application/Setting/src/main/res/values/strings.xml
+    <string name="sentry_mode_description">离开车辆后，将会启动哨兵模式，启动后将消耗一定电量</string>
```

## 为什么能修复
补上了缺失的 `sentry_mode_description_tv` 控件与文案资源，并接入既有的选项切换分支，"离车开启"态显示提示、其他态隐藏，与 UI 稿对齐。隐患：本次只在 `values/strings.xml` 添加中文，未同步 `values-en/strings.xml`，英文环境下该 TextView 将显示资源名或回落中文，属于典型的多语言遗漏。

## 复盘与经验
- "缺少文言/控件"类缺陷的修复模式固定：加控件 + 加 string + 在状态分支挂显隐；评审时重点检查所有分支是否都处理了新控件的 visibility。
- 提交信息类型标记（feature/bugfix）与实际意图不符时（本例 mistag），会污染后续按类型统计缺陷的数据，需在流程上约束。
- 新增 string 必须同步所有语言目录（values-en 等），否则换语言后暴露新缺陷。
