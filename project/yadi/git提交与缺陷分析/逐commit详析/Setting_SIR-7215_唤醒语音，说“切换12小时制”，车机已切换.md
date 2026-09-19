# SIR-7215 · 语音切换 12 小时制后，显示设置中"12小时"不高亮
- **提交**：`f42050e1` | 2026-09-18 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 待测试验证 · 域 语音

## 问题
语音唤醒后说"切换12小时制"，系统时间制式已切换成功，但显示设置页里"12小时"选项没有高亮，界面与实际状态不一致。

## 根因分析
`DisplayFragment` 中时间制式的单选组 `rgTimeShow` 只在用户**手动点击**时同步：点击回调里写 `Settings.System.putString(..., TIME_12_24, ...)`。语音助手走系统通道直接改写 `Settings.System.TIME_12_24`，不经过设置页点击回调，而页面没有注册对该 Settings 项的监听，导致外部修改后 UI 索引不刷新——缺陷库 rc"未做时间状态值改变"即指此。典型违背"同一设置项可能被多个入口修改，UI 必须以数据源为准"的原则。

## 关键代码修改
改动文件：DisplayFragment.kt（+33）
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayFragment.kt
+    private var timeModeObserver: ContentObserver? = null
...
+        timeModeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
+            override fun onChange(selfChange: Boolean) {
+                log("time_12_24 changed by external")
+                mBinding.rgTimeShow.setSelectedIndex(
+                    if (TextUtils.equals(
+                            Settings.System.getString(
+                                requireContext().contentResolver,
+                                Settings.System.TIME_12_24
+                            ), "12"
+                        )
+                    ) 0 else 1
+                )
+            }
+        }
+        requireContext().contentResolver.registerContentObserver(
+            Settings.System.getUriFor(Settings.System.TIME_12_24), true, timeModeObserver!!
+        )
+    }
+
+    override fun onDestroyView() {
+        timeModeObserver?.let {
+            requireContext().contentResolver.unregisterContentObserver(it)
+        }
+        timeModeObserver = null
+        super.onDestroyView()
     }
```

## 为什么能修复
注册 `ContentObserver` 监听 `Settings.System.TIME_12_24` 的 Uri：无论修改来自页面点击、语音助手还是其他应用，`onChange` 都会触发并按 Settings 里的实际值回设 `rgTimeShow.setSelectedIndex`（"12"→索引 0 高亮 12 小时）。UI 从"事件驱动"变为"数据源驱动"，多入口修改不再出现状态漂移。`onDestroyView` 中成对注销 observer，避免 Fragment 视图销毁后回调持有已失效的 binding 造成泄漏或崩溃。副作用：手动点击也会触发一次 onChange 重复 setSelectedIndex，幂等无影响。

## 复盘与经验
- 任何写入 `Settings.System/Secure/Global` 的设置项，页面都应同时具备"写入口"和"ContentObserver 读监听"两条腿，因为语音、快捷开关、云端下发都是潜在修改方。
- `registerContentObserver` 必须与 `unregisterContentObserver` 在视图生命周期内成对出现，Fragment 中放在 `onDestroyView` 最稳妥。
- 车机语音场景是设置类 UI 的隐形修改者，评审设置页时"语音改这个值 UI 会不会跟"应作为固定检查项。
