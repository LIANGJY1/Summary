# SIR-1540 · 哨兵模式配置字关闭后设置界面未隐藏对应选项
- **提交**：`33f76f04` | 2026-07-02 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 系统需求
- **说明**：与 `5f2e51de`（CommonTools 提供 `getSentryMode()`）配套。

## 问题
手动把哨兵模式配置字改为不支持后，车机设置界面仍显示"哨兵模式"选项，控件功能配置未生效。

## 根因分析
`SystemFragment`（`application/Setting/.../ui/fragment/SystemFragment.kt`）初始化"开启方式"单选组 `rgOpenMethod` 时，无条件使用含"哨兵模式"的选项数组，且直接 `mViewModel.mOpenMethod.value = SettingsUtils.getGSetting(GRAY_OPEN_METHOD, 0)` 恢复上次选择。代码完全没有读取哨兵配置字：即使整车配置不支持哨兵，UI 照样渲染"哨兵模式"项，已保存的选择索引也可能指向不存在的档位（哨兵模式对应索引 >= 2），造成配置与控件脱节。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt`、`application/Setting/src/main/res/values/strings.xml`
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt
@@ -106,7 +107,15 @@
-        mViewModel.mOpenMethod.value = SettingsUtils.getGSetting(GRAY_OPEN_METHOD, 0)
+        val hasSentryMode = SysPropUtils.getSentryMode() == 1
+        var selectOpenMethod = SettingsUtils.getGSetting(GRAY_OPEN_METHOD, 0)
+        if (!hasSentryMode) {
+            mBinding.rgOpenMethod.setItems(R.array.open_methods_no_sentry)
+            if (selectOpenMethod >= 2) {
+                selectOpenMethod = 0
+            }
+        }
+        mViewModel.mOpenMethod.value = selectOpenMethod
         mBinding.rgOpenMethod.setSelectedIndex(mViewModel.mOpenMethod.value ?: 0)
--- a/application/Setting/src/main/res/values/strings.xml
@@ -423,6 +423,10 @@
+    <string-array name="open_methods_no_sentry">
+        <item>关闭</item>
+        <item>防盗模式</item>
+    </string-array>
```

## 为什么能修复
界面初始化时通过 `SysPropUtils.getSentryMode() == 1` 判断配置：不支持哨兵则把单选组换成不含"哨兵模式"的 `open_methods_no_sentry` 数组（关闭/防盗模式），并把持久化的选择索引 `>= 2`（指向哨兵档位）回退为 0（关闭），保证"选项可见性"与"选中值合法性"同时符合配置字。隐患：配置字在运行中被手动修改时，本界面只有重建/重进才重新读取，缺少对配置变化的监听；索引回退策略是硬编码 0，若日后档位顺序调整需同步维护。

## 复盘与经验
- "按配置字裁剪 UI"必须同时处理两件事：隐藏选项 + 校正已持久化的选择值，只隐藏不复位会出现选中索引越界。
- 配置字驱动的界面应在初始化统一走一个分支函数，并把档位数组作为资源（string-array）管理，便于按配置切换。
