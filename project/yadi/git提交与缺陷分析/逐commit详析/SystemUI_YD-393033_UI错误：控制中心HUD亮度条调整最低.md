# YD-393033 · 控制中心HUD亮度条调最低图标未置灰

- **提交**：`9163c8e6` | 2026-08-04 | liujinfeng | SystemUI | bugfix
- **缺陷库**：未关联单号（标题含 YD-393033）

## 问题
控制中心把 HUD 亮度条拉到最低时，亮度图标仍显示常规（高亮）样式，未切换为最低亮度图标。

## 根因分析
`HUDBrightnessTile` 中判断最低亮度的条件写的是 `mPreHudBrightness == 0` / `progress == 0`，但 HUD 亮度协议的最小值是 1（0 不是合法档位），用户把条拉到底时值停在 1，条件永不成立，`vector_display_0` 置灰图标分支死代码化。此外无缓存时的默认进度也错误地设为 0。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/HUDBrightnessTile.kt
```diff
// application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/HUDBrightnessTile.kt
-            if (mPreHudBrightness == 0) {
+            if (mPreHudBrightness == 1) {
                 ivBrightness.setImageResource(R.drawable.vector_display_0)
             } else {
                 ivBrightness.setImageResource(R.drawable.vector_display)
...
-                            ivBrightness.setImageResource(if (progress == 0) R.drawable.vector_display_0 else R.drawable.vector_display)
+                            ivBrightness.setImageResource(if (progress == 1) R.drawable.vector_display_0 else R.drawable.vector_display)
                         } else {
-                            seekBar?.progress = 0
+                            seekBar?.progress = 1
                             ivBrightness.setImageResource(R.drawable.vector_display_0)
                         }
```

## 为什么能修复
三处阈值统一从 0 修正为 HUD 协议最小值 1，亮度条到底（=1）时图标正确切换为 `vector_display_0`；无缓存默认进度也从 0 改为 1，避免出现"进度 0 但协议值 1"的越界初值。判断用 `==` 精确匹配仍偏脆弱，若后续协议支持 0 值或取整偏差会再次失配，更稳的是 `<= min`。

## 复盘与经验
- UI 判断阈值必须来自协议/SRS 的真实取值范围，"想当然的 0"与协议最小值 1 之间只差一位，却是必现 UI 错误。
- 同一语义阈值在一个类里出现多次时（本例 3 处），应抽成常量 `HUD_BRIGHTNESS_MIN`，一处修正全局生效。
- 硬件/协议量通常从 1 起编（0 表示无效或关闭），写 UI 联动时先确认边界含义再定条件。
