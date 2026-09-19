# SIR-7005 · 夜间模式蓝牙未连接缺省图误用白天亮图
- **提交**：`3bb0c383` | 2026-08-31 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
夜间模式下，蓝牙电话主界面"蓝牙未连接"与"未授权"的缺省占位图仍使用白天亮色调大图，与暗色背景冲突刺眼，应使用暗灰色夜间版。

## 根因分析
`activity_main.xml` 中两个占位 ImageView 直接引用了语义化错误的资源：未连接占位用的是 `@drawable/ic_no_connect_bt`、未授权占位用的是 `@drawable/no_agree`——这两个资源只有白天目录（drawable-mdpi）下的亮色版本，没有对应的 `drawable-night` 变体，夜间模式加载到的仍是亮图。而库内其实存在规范命名的昼夜成对资源 `bluetooth_no_connect`（白天/夜间各一份）。根因即单据所写"资源用错"：布局引用了无夜间变体的旧命名资源，而非缺图。

## 关键代码修改
改动文件：application/BTPhone/src/main/res/layout/activity_main.xml 及 5 个 png（二进制资源更新）
```diff
--- a/application/BTPhone/src/main/res/layout/activity_main.xml
@@ 蓝牙未连接占位
-                android:src="@drawable/ic_no_connect_bt"
+                android:src="@drawable/bluetooth_no_connect"
@@ 未授权占位
-                    android:src="@drawable/no_agree"
+                    android:src="@drawable/empty"
```
配套资源变化：新增 `drawable-night/bluetooth_no_connect.png`（夜间暗色版）；`drawable/bluetooth_no_connect.png` 替换为优化后的图（138620B→45866B）；删除废弃的 `drawable-mdpi/ic_no_connect_bt.png`、`drawable-night/no_agree.png`、`drawable/no_agree.png`。均为二进制图片更新。

## 为什么能修复
引用切到"昼/夜双份齐全"的 `bluetooth_no_connect` 后，夜间模式由资源限定符自动命中暗色版本；未授权占位改用 `empty` 资源统一空态展示。同时删除三张废弃 png，避免后续再被误引。风险极小；注意点是确认所有引用旧名 `ic_no_connect_bt`/`no_agree` 的位置已全部迁移（本提交仅布局两处，其余引用需全局搜索确认）。

## 复盘与经验
- 夜间适配的检查项不是"有没有图"，而是"用的资源有没有 drawable-night 变体"——引用无夜间变体的资源等于夜间适配未做。
- 资源命名要语义化且昼夜成对（xxx / drawable-night/xxx），本例新旧两套命名并存正是错引的温床，删旧图是收尾必做步骤。
- UI 走查应包含昼夜两套模式逐屏比对，缺省图/占位图是夜间走查最高频的漏网点。
