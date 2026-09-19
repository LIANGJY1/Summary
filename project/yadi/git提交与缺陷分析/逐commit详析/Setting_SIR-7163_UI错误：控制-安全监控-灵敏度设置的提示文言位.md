# SIR-7163 · 灵敏度设置提示文言位置错误

- **提交**：`fe97eb57` | 2026-09-02 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
控制-安全监控-灵敏度设置的提示文言显示位置错误，不在 UI 稿指定的设置项下方独立区域。

## 根因分析
灵敏度说明文案此前通过 `ImageTextRadioGroup.setSubTitle()` 渲染，即被当作单选组内部副标题挤在选项行内（`SafetyMonitorFragment` 初始化与 `onItemChecked` 回调都写 `rgSensitivitySetting.setSubTitle(strings[index])`），与设计稿"设置项下方独立灰色说明文字"的位置不符（缺陷库根因"需求遗漏"——实现时缺一个独立说明控件）。

## 关键代码修改
改动文件：SafetyMonitorFragment.kt、VehicleControlFragment.kt、layout/fragment_safety_monitor.xml
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt
         val strings = resources.getStringArray(R.array.sensitivity_setting_sub)
-        mBinding.rgSensitivitySetting.setSubTitle(strings[index])
+        mBinding.sensitivitySettingDes.text = strings[index]
             override fun onItemChecked(position: Int, text: String) {
                 SettingsUtils.setGSetting(SENSITIVITY_SETTING, position)
                 val strings = resources.getStringArray(R.array.sensitivity_setting_sub)
-                mBinding.rgSensitivitySetting.setSubTitle(strings[position])
+                mBinding.sensitivitySettingDes.text = strings[position]
```
```diff
// application/Setting/src/main/res/layout/fragment_safety_monitor.xml（设置项下方新增说明控件）
+            <TextView
+                android:id="@+id/sensitivity_setting_des"
+                style="@style/setting_title_gray_style"
+                android:layout_marginTop="@dimen/dp_12"
+                android:text="@string/sentry_mode_description" />
```

## 为什么能修复
新增独立的 `sensitivity_setting_des` TextView（`setting_title_gray_style` 灰字样式、位于单选组下方），说明文案改写到这里，位置与 UI 稿一致；切换灵敏度时随 `onItemChecked` 实时刷新。隐患两处：其一，新 TextView 的 XML 默认文案误用了 `@string/sentry_mode_description`（哨兵模式文案，从 b0feb27d 的修复复制而来），仅因代码初始化立即覆盖 `strings[index]` 才未暴露，XML 层的 copy-paste 错误应清理；其二，本提交夹带了与本单无关的 `VehicleControlFragment` 座椅逻辑改动（`isSeatControlEnabled` 默认值 true→false、日志调整），违反"一提交一缺陷"，回滚/追溯时易误伤。

## 复盘与经验
- "提示文字位置不对"的通病是把说明文案塞进组件内置副标题槽位；需要独立位置的说明，第一天就该建独立控件。
- 复制上一个修复的布局片段时，`android:text` 引用的 string 一定要换，占位错误文本会在初始化时序变化时暴露成真 bug。
- 提交混入无关文件（座椅 CAN 逻辑）会让该提交无法按单号安全回滚，commit 粒度纪律值得在 review 中硬性把关。
