# YD-392744 · "去设置"按钮颜色与UI不符

- **提交**：`b0a39fec` | 2026-06-27 | daizhecheng | Vlog | bugfix（UI 视觉修复）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
相机配对页"去设置"按钮使用蓝色底，与 UI 设计稿的深色（建议色）按钮不符；同时全局修正了两个颜色令牌的取值。

## 根因分析
布局 `camera_pair_activity.xml` 中"去设置"按钮的 `android:background` 引用了 `btn_bg_radio18_blue`（蓝色底），是早期占位选择，未随设计稿更新。修复分三层：布局引用换成 `btn_bg_radio18_black`；同时把该 drawable 内部颜色从 `@color/btn_bg_black_color` 换成语义更准确的 `@color/bg_button_suggest`（与 98763f37 中 `btn_up_p.xml` 的处理一致，收敛到同一"按钮建议底色"令牌）；并按新设计稿校正 `btn_bg_red_color` 色值（`#F34730` → `#DD5252`）。另附带把根 `.gitignore` 的 `application/*/build/` 改为 `**/build/`——恰好呼应 094cdccd 的教训：旧规则只忽略 application 下一层模块的 build 目录，其他层级的 build 产物仍会漏进仓库。

## 关键代码修改
改动文件：application/Vlog/src/main/res/layout/camera_pair_activity.xml、application/Vlog/src/main/res/drawable/btn_bg_radio18_black.xml、application/Vlog/src/main/res/values/colors.xml、.gitignore

```diff
--- application/Vlog/src/main/res/layout/camera_pair_activity.xml
@@ "去设置"按钮底色换引用
-                    android:background="@drawable/btn_bg_radio18_blue"
+                    android:background="@drawable/btn_bg_radio18_black"
```

```diff
--- application/Vlog/src/main/res/drawable/btn_bg_radio18_black.xml
@@ 颜色收敛到建议色令牌
-            <solid android:color="@color/btn_bg_black_color" />
+            <solid android:color="@color/bg_button_suggest" />
```

```diff
--- .gitignore
@@ 忽略所有层级的 build 产物
-application/*/build/
+**/build/
```
另：`colors.xml` 中 `btn_bg_red_color` 由 `#F34730` 校正为 `#DD5252`。

## 为什么能修复
按钮底色直接由布局引用决定，换 drawable 即生效；drawable 内色值再收敛到 `bg_button_suggest` 单一令牌，日间/夜间与主题色调整只改一处。改动极小、无行为副作用。唯一提醒：`btn_bg_radio18_black` 文件名与其实际色值（建议色）已经名不副实，且新色值 `#DD5252` 仍写死在 colors.xml 而未拆分日/夜（values-night）版本。

## 复盘与经验
- **颜色令牌按语义命名**（`bg_button_suggest`）而非按外观命名（`btn_bg_black_color`），设计稿换色时引用不用动、只改令牌值。
- **布局里的 `@drawable` 引用也要走"审查占位"流程**：blue→black 这类早期占位引用是 UI 走查高频问题。
- **`.gitignore` 用 `**/build/` 通配所有层级**：一行的改动，杜绝 094cdccd 式"千个构建产物入库"的重演。
