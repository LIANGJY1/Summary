# SIR-5708 · 屏幕亮度调至最低会回弹（SystemUI 侧）

- **提交**：`18b4d021` | 2026-08-06 | dufan | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（rc：进度区间有误 / sol：修改进度区间）

## 问题
在控制中心把亮度滑条拖到最低时，亮度不会停在最低值，而是自动弹回，表现为"回弹"。

## 根因分析
快捷设置亮度滑条 `brightnessSeekBar` 的进度区间配置为 `android:min="0"`、`max="20"`，`BrightnessTile` 在无缓存值时默认 `setProgress(0)`。而系统亮度属性的有效下限是 1（0 为非法/关闭档，底层不接受），用户把滑条拖到 0 后下发的亮度值超出系统允许区间，系统侧拒绝或被纠偏回合法值，亮度回调再刷新 UI 时进度跳回，视觉上就是"回弹"。根因即缺陷库所述"进度区间有误"：UI 可选区间与系统亮度合法区间 `[1,20]` 不一致。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/BrightnessTile.java`、`application/SystemUI/src/main/res/layout/fragment_quick_setting.xml`、`application/SystemUI/src/main/res/layout/fragment_quick_setting_no_hud.xml`
```diff
// application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/BrightnessTile.java
         } else {
-            brightnessSeekBar.setProgress(0);
+            brightnessSeekBar.setProgress(1);
         }
```
```diff
// application/SystemUI/src/main/res/layout/fragment_quick_setting.xml（no_hud 布局同步修改）
                 android:max="20"
-                android:min="0"
+                android:min="1"
```

## 为什么能修复
把滑条 `min` 从 0 改为 1 后，用户物理上拖不到非法值 0，下发的亮度永远在系统合法区间内，不再触发底层纠偏回弹；默认进度同步改为 1，保证初始值同样合法。`fragment_quick_setting.xml` 与 `fragment_quick_setting_no_hud.xml` 两份布局成对修改，避免带 HUD/不带 HUD 两种形态行为分叉。副作用：亮度最低档含义从"0"变为"1"，若产品定义中 0 代表熄屏则此改动会移除熄屏入口——本案例中系统本身不接受 0，属对齐现实约束。

## 复盘与经验
- 滑条类控件的 min/max 必须与系统属性/协议的合法区间对齐，UI 区间宽于系统区间就会产生"拖到端点被弹回"的体验 bug。
- 双布局（带 HUD/不带 HUD）承载同一控件时，属性修改要成对落实，建议抽 style 收敛。
- Setting 侧同单号还有配套提交 `b20d7c1b`，同一参数在多模块各有一份 UI 区间配置时，修一处漏一处是常态，应全局检索该控件的 min/max。
