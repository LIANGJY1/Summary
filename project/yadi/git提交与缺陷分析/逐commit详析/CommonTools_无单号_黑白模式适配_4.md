# 无单号 · CommonTools 黑白模式适配（公共库色值收敛）

- **提交**：`b226c24f` | 2026-06-29 | dufan | CommonTools | feature
- **关联单**：无

## 需求/目标
公共组件库的黑白模式适配收口：删除整套旧私有色板（values/colors.xml 37 行 + values-night/colors.xml），把 selector、dialog/toast 布局、按钮背景全部切换到统一语义色（`text_default_default/press`、`bg_application` 等），并顺手清掉无引用的红色系 drawable。

## 实现结构
- 删除 `values/colors.xml`（tv_main、setting_warning、text_default_color 等旧色名）与整个 `values-night/colors.xml`（旧夜色板只有 4 个色）。
- `res/color/selector_common_text_color_white.xml`：改用新语义色。
- `dialog_edit.xml、dialog_text.xml、dialog_warning.xml、item_choose.xml、layout_custom_toast.xml、view_styles.xml`：textColor/background 引用同步替换。
- `bg_round_confirm*.xml、shape_btn_white_*.xml、shape_setting_seat_heat_save_selected_bg.xml` 等背景改语义色；删除 `bg_round_cancel.xml`、`shape_round_14_dd5252.xml` 两个仅服务于旧红色按钮的 drawable。

数据流：公共库色板统一后，所有依赖 CommonTools 的应用（Setting/BTMusic/Launcher）在 uiMode 切换时由资源系统自动取得对应语义色，各 app 不再各养一套色值。

## 关键代码
```diff
# component/CommonTools/src/main/res/values/colors.xml（整个文件删除，-37 行）
-    <color name="text_secondary_color">#7F0A1532</color>
-    <color name="text_default_color">#20232B</color>
-    <!--警告红色-->
-    <color name="setting_warning">#DD5252</color>
```
```diff
# component/CommonTools/src/main/res/drawable/bg_view_top_line.xml（同类引用替换，见 98f59cee）
-    <solid android:color="#2B2E334D" />
+    <solid android:color="@color/icon_default_disabled" />
```
```diff
# component/CommonTools/src/main/res/values/view_styles.xml
-        <item name="android:textColor">@color/text_default_color</item>
+        <item name="android:textColor">@color/text_default_default</item>
```

实现讲解：适配黑白模式的正确顺序是"先收敛色板、再改引用"——旧库同时存在 values 与 values-night 两份且命名无语义（setting_6020232B 这类以色值命名），直接在引用处替换必然遗漏；本提交把旧色名整体删除，编译器会强制暴露所有残留引用，等于用编译期检查代替人工排查。

## 复盘与要点
- 可复用手法："删旧色板逼出残留引用"是资源重构的杠杆点，比全局搜索替换可靠。
- 色名规约值得沉淀：`text_default_default/press/disabled`、`bg_application`、`icon_default_disabled` 这类"语义_状态"命名是本项目的语义色体系，新代码应直接复用。
- 风险：删除公共库色是破坏性变更，需保证同批所有引用方提交合入，否则跨仓库/跨模块编译会断（本批次当天 8 个"黑白模式适配"提交正是同一次收敛的分布式落地）。
