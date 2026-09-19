# SIR-6360 · 深色模式下热点浮窗在切换浅色后仍保持深色

- **提交**：`6c8ba490` | 2026-08-25 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
深色模式下从负一屏点击"热点"弹出 `ConnectChildDialogActivity` 浮窗，此时在车控车设里切回浅色模式，该弹窗仍停留在深色样式，不跟随系统主题切换。

## 根因分析
问题根源在 `application/Setting/src/main/AndroidManifest.xml` 中 `ConnectChildDialogActivity` 的 `android:configChanges` 声明包含了 `uiMode`。在 Android 中，一旦 Activity 在 `configChanges` 里声明接管某配置项，配置变化时系统就不再重建（recreate）该 Activity，而是回调其 `onConfigurationChanged` 由应用自行处理；该浮窗 Activity 并没有在 `onConfigurationChanged` 里做主题刷新，于是深色→浅色切换时它既不重建也不换肤，弹窗保持打开瞬间的深色资源。这是一个典型的"为了防重建而接管了 uiMode，却没接住主题切换"的配置遗漏——缺陷库标注根因为"需求遗漏"，即浅色跟随需求新增时漏掉了这个 Activity 的处理。

## 关键代码修改
改动文件：`application/Setting/src/main/AndroidManifest.xml`

```diff
--- a/application/Setting/src/main/AndroidManifest.xml
@@ -115,7 +115,7 @@
         <activity
             android:name="com.yadea.setting.ui.activity.ConnectChildDialogActivity"
-            android:configChanges="screenLayout|screenSize|smallestScreenSize|orientation|uiMode|locale|layoutDirection|touchscreen"
+            android:configChanges="screenLayout|screenSize|smallestScreenSize|orientation|locale|layoutDirection|touchscreen"
             android:excludeFromRecents="true"
             android:exported="true"
             android:launchMode="singleInstance"
```

## 为什么能修复
把 `uiMode` 从 `configChanges` 中移除后，系统深浅色切换（uiMode 配置变化）会触发该 Activity 走标准的销毁重建流程，重建时按新的主题重新 inflate 布局与取色，弹窗即恢复为浅色样式。代价是切换主题瞬间该浮窗会重建一次（存在瞬时闪烁、以及重建时未保存的临时状态丢失的可能），对只有简单展示状态的 `singleInstance` 浮窗可接受；其余 configChanges（如 locale）保留，说明多语言切换仍由应用自行处理，未被波及。注：commit message 的"测试范围：档位置灰处理"与本单无关，疑为模板复制残留。

## 复盘与经验
- `configChanges` 是一把双刃剑：声明 `uiMode` 能避免重建闪烁，但等于承诺自己处理主题切换。任何 Activity 接管 `uiMode` 前先确认深浅色切换的刷新路径是否存在，否则直接交给系统重建更安全。
- 车机浮窗常用 `excludeFromRecents + singleInstance` 的独立 Activity 实现，这类"隐藏的窗口"极易在主题/语言适配时被遗漏，深浅色测试要专门遍历所有 DialogActivity。
- 深色模式支持是后加需求时，存量 Activity 的 `configChanges` 清单就是首要排查点——"新需求遗漏"往往藏在旧配置声明里。
