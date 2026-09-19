# SIR-7406 · 安全监控-格式化"正在格式化"加载图标与UI不符
- **提交**：`72a60515` | 2026-09-09 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：UI 颜色值不对）

## 问题
控制-安全监控-格式化流程中"正在格式化"加载弹窗的 loading 转圈图标颜色/灰阶与 UI 稿不一致。

## 根因分析
加载图标资源 `ic_loading_dialog.xml`（位于 component/CommonTools，8 条刻度线的矢量 loading）原先每个 path 用硬编码灰阶色 `#FFFFFFFF`、`#FF999999`、`#FF666666`、`#FFCCCCCC`、`#FF4C4C4C`、`#FFB3B3B3`、`#FF808080`、`#FFE6E6E6` 标记旋转相位。这些灰阶值与 UI 定稿不符（且硬编码白色在非纯色背景/深色主题下表现不对），属于资源值层面的颜色错误，无逻辑代码。

## 关键代码修改
改动文件：component/CommonTools/src/main/res/drawable/ic_loading_dialog.xml
```diff
--- component/CommonTools/src/main/res/drawable/ic_loading_dialog.xml
@@ -6,26 +6,33 @@
   <path
       android:pathData="M41.25,7.5H45C47.071,7.5 48.75,9.179 48.75,11.25V26.25H45C42.929,26.25 41.25,24.571 41.25,22.5V7.5Z"
-      android:fillColor="#FFFFFFFF"/>
+      android:fillColor="@color/text_white_default"/>
   <path
       android:pathData="M41.25,82.5H45C47.071,82.5 48.75,80.821 48.75,78.75V63.75H45C42.929,63.75 41.25,65.429 41.25,67.5V82.5Z"
-      android:fillColor="#FF999999"/>
+      android:fillColor="@color/text_white_default"
+      android:fillAlpha="0.6"/>
   <path
       android:pathData="M82.5,41.25L82.5,45C82.5,47.071 80.821,48.75 78.75,48.75L63.75,48.75L63.75,45C63.75,42.929 65.429,41.25 67.5,41.25L82.5,41.25Z"
-      android:fillColor="#FF666666"/>
+      android:fillColor="@color/text_white_default"
+      android:fillAlpha="0.4"/>
```
其余 5 条刻度线同样改为统一 `@color/text_white_default` + `fillAlpha`（0.3/0.5/0.7/0.8/0.9）表达相位差。

## 为什么能修复
8 条刻度统一使用主题色资源 `text_white_default`，透明度 1.0~0.3 阶梯表达旋转相位，视觉与 UI 稿一致；颜色引用主题色而非硬编码，深浅色主题自动适配。副作用很小：若 `text_white_default` 在某主题下被定义为非白色，loading 图标会随之变色——但这正是主题化的预期行为。

## 复盘与经验
- 矢量资源里硬编码 `#FFxxxxxx` 灰阶是 UI 走查高频问题源；"单色 + fillAlpha 阶梯"既符合设计稿的相位表达，又天然支持主题切换。
- loading/转圈类通用组件放在 CommonTools 会被多个界面复用（本例由 Setting 格式化弹窗引用），一处资源错误会放大成多界面 UI 缺陷，改动需全量走查引用方。
- UI 走查反馈"颜色不对"时，优先核对矢量 XML 的 fillColor/Alpha 与设计稿标注的色值/透明度，多数情况是导出 SVG 时的默认灰阶未替换。
