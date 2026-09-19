# YD-393032 · 控制中心自动亮度/HUD自动亮度关闭图标未置灰

- **提交**：`5d4e888b` | 2026-08-04 | liujinfeng | SystemUI | bugfix
- **缺陷库**：未关联单号（标题含 YD-393032）

## 问题
控制中心里"屏幕自动亮度""HUD 自动亮度"开关关闭后，图标仍是高亮样式，没有置灰态，昼夜模式下均与 UI 稿不符。

## 根因分析
`fragment_quick_setting.xml` / `fragment_quick_setting_no_hud.xml` 中 `auto_switch`、`hud_switch` 两个 ImageButton 的 `android:src` 直接写死为选中态矢量图 `vector_display_auto` / `vector_hud`，工程中根本不存在未选中（unsel）资源，代码侧即使 setSelected(false) 也无法呈现置灰效果——是"ui 变更，新增未选中态图标"的资源层缺失。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/drawable/selector_display_auto.xml、application/SystemUI/src/main/res/drawable/selector_hud.xml、application/SystemUI/src/main/res/drawable/vector_display_auto_unsel.xml、application/SystemUI/src/main/res/drawable/vector_hud_unsel.xml、application/SystemUI/src/main/res/layout/fragment_quick_setting.xml、application/SystemUI/src/main/res/layout/fragment_quick_setting_no_hud.xml
```diff
// application/SystemUI/src/main/res/drawable/selector_display_auto.xml（selector_hud.xml 同构）
+<selector xmlns:android="http://schemas.android.com/apk/res/android">
+    <item android:state_selected="true" android:drawable="@drawable/vector_display_auto"/>
+    <item android:drawable="@drawable/vector_display_auto_unsel"/>
+</selector>
```
```diff
// application/SystemUI/src/main/res/layout/fragment_quick_setting.xml
                 android:background="@drawable/quick_button_selector_on"
                 android:scaleType="centerInside"
-                android:src="@drawable/vector_display_auto"
+                android:src="@drawable/selector_display_auto"
```
```diff
// fragment_quick_setting.xml 约束错链修正
-                app:layout_constraintStart_toEndOf="@id/hud_switch" />
+                app:layout_constraintStart_toEndOf="@id/hud_auto_switch" />
```
新增 `vector_display_auto_unsel.xml`（30dp 多 path 太阳/亮度环）与 `vector_hud_unsel.xml`（HUD 面板图形），填充色均为置灰语义的 `@color/icon_default_press`。

## 为什么能修复
图标 src 换成 selector 后，View 的 selected 状态直接驱动选中/置灰两套矢量切换，无需代码改图标；顺带修正了 HUD 亮度条约束引用错误 id（`hud_switch` → `hud_auto_switch`）导致的布局错位。隐患：selector 依赖代码正确维护 `setSelected()` 状态，若开关状态同步代码缺失仍不会自动变化（本提交只补资源层）。

## 复盘与经验
- 双态图标必须以 selector + sel/unsel 两个矢量交付，布局直接写死单态图是"状态切换不生效"类 UI 缺陷的常见根因。
- 复制粘贴布局时 `layout_constraintStart_toEndOf` 引用的 id 极易链错对象，评审时要专门核对约束链。
- 置灰态的颜色应使用设计系统中的 disabled/press 语义色（如 `icon_default_press`），与主题昼夜联动。
