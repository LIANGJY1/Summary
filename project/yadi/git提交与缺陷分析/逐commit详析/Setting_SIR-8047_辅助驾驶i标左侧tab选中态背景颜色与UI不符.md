# SIR-8047 · 辅助驾驶弹窗左侧 tab 选中态背景色与 UI 不符

- **提交**：`a057a5c1` | 2026-09-10 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
辅助驾驶"i"标弹窗（dialog_assist_intro）左侧 tab（前碰撞预警/后碰撞预警/车道偏离预警/交通预警）选中态背景颜色与 UI 设计稿不符。

## 根因分析
四个 tab 按钮统一引用公共选择器 `@drawable/selector_common_gray_btn`，其选中态背景色是旧灰系配色，与最新设计稿要求的选中态白色底（`@color/bg_segmentbutton`）不一致（缺陷库记"颜色值不对"）。修复方式不是改公共选择器（会影响所有使用方），而是新建一套语义化选择器供本弹窗使用。

## 关键代码修改
改动文件：application/Setting/src/main/res/layout/dialog_assist_intro.xml、component/CommonTools/src/main/res/drawable/selector_common_gray_white_btn.xml（新增）、shape_setting_white_round_bg.xml（新增）（3 文件 +20/-7）
```diff
--- application/Setting/src/main/res/layout/dialog_assist_intro.xml（4 处 tab 同构）
-                android:background="@drawable/selector_common_gray_btn"
+                android:background="@drawable/selector_common_gray_white_btn"
--- component/CommonTools/src/main/res/drawable/selector_common_gray_white_btn.xml（新增）
+    <item android:state_selected="true" android:drawable="@drawable/shape_setting_white_round_bg" />
+    <item android:drawable="@drawable/shape_setting_gray_round_bg" />
--- component/CommonTools/src/main/res/drawable/shape_setting_white_round_bg.xml（新增）
+    <solid android:color="@color/bg_segmentbutton" />
+    <corners android:radius="12dp" />
```
（布局同时微调滚动区底部间距：ns_detail 去掉 marginBottom 48dp，ll_detail_items paddingBottom 改 48dp）

## 为什么能修复
选中态（state_selected）指向新建的白色 12dp 圆角背景，默认态复用既有灰色圆角，tab 选中颜色即与设计稿一致；通过新增选择器而非篡改 `selector_common_gray_btn`，其他模块的按钮不受影响。改动为纯资源层，风险低；注意本选择器放在 CommonTools 公共库里，命名（gray_white）已暗示灰/白双态，后续其他双态 tab 可复用。

## 复盘与经验
- 公共 drawable 的颜色"修不对"时，正确做法是派生新的语义化选择器，而不是直接改公共资源（会波及所有使用方）——本提交是标准示范。
- tab/分段按钮的选中态颜色应统一走 `bg_segmentbutton` 这类语义色，避免每次设计稿换色都新增十六进制裸值。
- UI 对版类缺陷要连同间距一起核对（本提交顺带修了滚动区底部留白），一次过版减少来回。
