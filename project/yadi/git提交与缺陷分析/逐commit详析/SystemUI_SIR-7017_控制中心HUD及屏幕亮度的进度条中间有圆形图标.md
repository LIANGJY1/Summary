# SIR-7017 · 控制中心 HUD 及屏幕亮度进度条中间有圆形图标
- **提交**：`38786eb9` | 2026-09-01 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
控制中心里 HUD 亮度与屏幕亮度两条进度条（SeekBar）按压时中央出现一个圆形图标（ripple/按压底），视觉上像多了一个圆点。

## 根因分析
两处布局使用 material 风格的 SeekBar，Material 样式控件默认自带按压态背景（ripple 等 foreground/background drawable）。按压时默认背景在滑条中央显现圆形高亮，与车机定制 UI 不符。纯视觉问题，通过布局属性即可关闭。

## 关键代码修改
改动文件：`application/SystemUI/src/main/res/layout/fragment_quick_setting.xml`（2 处 SeekBar）、`application/SystemUI/src/main/res/layout/fragment_quick_setting_no_hud.xml`（1 处）
```diff
--- application/SystemUI/src/main/res/layout/fragment_quick_setting.xml
                 android:layout_width="0dp"
                 android:layout_height="@dimen/dp_60"
                 android:layout_marginStart="@dimen/dp_20"
+                android:background="@null"
                 android:max="20"
                 android:min="1"
```
（三个亮度 SeekBar 均添加 `android:background="@null"`，此处列一个代表 hunk）

## 为什么能修复
`android:background="@null"` 清空 Material SeekBar 的默认按压背景 drawable，圆形 ripple 高亮不再绘制，滑条按压时保持干净的定制外观。副作用：控件的按压反馈动画一并消失，但车机 HMI 设计本就不需要该反馈，属预期取舍。

## 复盘与经验
- Material 组件（MaterialSeekBar/MaterialButton 等）默认自带 ripple/foreground，嵌入定制车机 UI 时应主动检查按压态表现。
- 关闭控件默认反馈用 `background="@null"` 最轻量，但要同步评估是否需要自绘的替代按压反馈。
- 同一控件在多个布局变体（带 HUD / 无 HUD）中出现时，修改要全覆盖，本提交对 3 处都做了处理，值得借鉴。
