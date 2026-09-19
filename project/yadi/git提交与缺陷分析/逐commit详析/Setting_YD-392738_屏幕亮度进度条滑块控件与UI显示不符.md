# YD-392738 · 屏幕亮度进度条滑块控件与UI显示不符

- **提交**：`69a46856` | 2026-06-26 | dufan | Setting | bugfix（UI 视觉修复）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
HUD 屏幕亮度档位进度条（`GearSwitchView2`）的滑块（thumb）样式与新版 UI 设计稿不符：新设计要求档位条隐藏滑块、只显示带圆角的激活轨道填充。

## 根因分析
`GearSwitchView2` 的 `onDraw` 流程是固定五步：画轨道 → 画激活段（`drawActiveTrack`）→ `drawThumb(canvas)` → 画刻度 → 画档位数字，滑块无条件绘制，控件没有"是否显示滑块"的开关。同时 `drawActiveTrack` 只有"圆形滑块式"一种激活段画法（saveLayer + 白色填充 + 刻度镂空 + 圆角边框），无法呈现 UI 新稿要求的"方形圆角直边填充"形态。提交消息 [why] 为"UI变更"，即设计稿迭代后旧绘制能力覆盖不了新视觉，需要给控件扩展能力而非改数值。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/widget/GearSwitchView2.kt、application/Setting/src/main/res/layout/seekbar_setting_hud_brightness_gear.xml、application/Setting/src/main/res/values/attrs.xml

```diff
--- application/Setting/src/main/res/values/attrs.xml
@@
             <attr name="thumbRadius" format="dimension" />
-
+            <!-- 滑块显示 -->
+            <attr name="isShowThumb" format="boolean" />
```

```diff
--- application/Setting/.../ui/widget/GearSwitchView2.kt
@@ 新增 isShowThumb 属性并参与绘制分支
+    var isShowThumb = true
+        set(value) {
+            if (field != value) { field = value; invalidate() }
+        }
@@ onDraw
-        drawThumb(canvas)
+        if (isShowThumb) {
+            drawThumb(canvas)
+        }
```

```diff
--- application/Setting/.../ui/widget/GearSwitchView2.kt
@@ drawActiveTrack 增加无滑块的直角右缘画法（Path 拼左圆角+右侧直边）
+        if (isShowThumb || activeTrackEnd.toInt() == 596) {
+            ...原 saveLayer 圆角画法...
+        } else {
+            if (activeTrackEnd.toInt() != 24) {
+                val path = Path()
+                path.moveTo(activeTrackRect.left + r, activeTrackRect.top)
+                path.quadTo(activeTrackRect.left, activeTrackRect.top,
+                            activeTrackRect.left, activeTrackRect.top + r)
+                ...
+                canvas.drawPath(path, activeTrackPaint)
+                canvas.drawPath(path, activeTrackBorderPaint)
+            }
+        }
```
布局 `seekbar_setting_hud_brightness_gear.xml` 新增 `app:isShowThumb="false"` 使用新属性。

## 为什么能修复
通过自定义属性 `isShowThumb` 把"是否绘制滑块"做成控件能力：为 false 时跳过 `drawThumb`，且激活轨道走新增的 Path 分支——左端保留圆角、右端改为直边矩形填充，与新版 UI 稿一致；属性带 `invalidate()` 触发重绘，XML 一行配置即可生效。隐患明显：分支条件用了魔法数 `activeTrackEnd.toInt() == 596` 和 `!= 24`（硬编码像素值），在不同分辨率/密度屏幕上 596px 不再成立，分支会走错，属于"按当前机器调好的像素"式写法。

## 复盘与经验
- **自定义 View 要把"形态开关"做成属性**：视觉稿迭代频繁时，`isShowThumb` 这类 boolean styled attr + `invalidate()` 比改死代码可复用得多。
- **警惕像素级魔法数**：`== 596`/`!= 24` 这类 px 硬编码应换成 dp 换算或语义化模式枚举，否则换屏即回归。
- **UI 不符类 bug 的两类根源要分清**：能力缺失（控件画不出目标形态）与参数偏差（颜色/间距数值错），本例属于前者，修法是扩展控件而非调参。
