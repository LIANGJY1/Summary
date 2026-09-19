# SIR-8140 · 蓝牙音乐未连接时【连接蓝牙】按钮无点击效果

- **提交**：`5e583109` | 2026-09-11 | dufan | BTMusic | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
蓝牙音乐未连接的空态页上，【连接蓝牙】按钮（`tvConnect`）点击没有任何按压反馈，用户无法感知按钮可点、是否点中。

## 根因分析
未连接空态的按钮背景用的是静态 shape `bg_tv_connect`（纯色圆角+描边），文字色是静态 `text_default_default`——两者都不是 selector，没有 `state_pressed` 分支，因此按压时视觉毫无变化。缺陷库根因"未添加点击效果"属实：初版实现直接给了静态资源，未接入项目的通用按压态资源体系。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt`、`application/BTMusic/src/main/res/layout/activity_main.xml`；删除 `application/BTMusic/src/main/res/drawable/bg_tv_connect.xml`
```diff
// MainActivity.kt（未连接空态刷新处）
-            it.tvConnect.setTextColor(resources.getColor(R.color.text_default_default, null))
-            it.tvConnect.background = resources.getDrawable(R.drawable.bg_tv_connect, null)
+            it.tvConnect.setTextColor(resources.getColor(R.color.selector_common_text_color_black, null))
+            it.tvConnect.background = resources.getDrawable(R.drawable.selector_common_white_btn, null)
```
```diff
// activity_main.xml
-                        android:background="@drawable/bg_tv_connect"
+                        android:background="@drawable/selector_common_white_btn"
                         android:gravity="center"
                         android:text="@string/bluetooth_setting"
-                        android:textColor="@color/text_default_default"
+                        android:textColor="@color/selector_common_text_color_black"
```

## 为什么能修复
把背景与文字色替换为通用 `selector_common_white_btn` / `selector_common_text_color_black`，selector 内含按压态分支，按下瞬间背景与文字色切换，点击反馈即出现。同时代码刷新路径（空态初始化）与布局默认值保持一致，避免状态切换后效果丢失。删除冗余的静态 `bg_tv_connect` 防止后续误用。副作用：按钮视觉样式由项目通用样式接管，圆角/描边细节与旧 shape 可能略有差异，需 UI 确认一致性。

## 复盘与经验
- 所有可点控件一律使用 selector（背景+文字色）而非静态 drawable/color，点击态应成为默认而非补丁。
- 控件状态在布局与代码两处设置时必须同步修改，否则运行时状态刷新会覆盖布局里的修复。
- 项目沉淀统一的 `selector_common_*` 资源族是提升一致性和减少此类 D 级缺陷的有效手段。
