# SIR-7514 · 白天/黑夜模式，座椅位置保存按钮无点击效果
- **提交**：`39e4afc4` | 2026-09-05 | sgh | Setting | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
昼/夜两种模式下，座椅位置的"保存"按钮按下时都没有任何视觉反馈（白色按下效果失效）。

## 根因分析
这是一个典型的 Android 资源合并遮蔽（resource shadowing）问题，提交信息概括为"xml名称冲突"。公共库 `component/CommonTools` 定义了标准按下选择器 `selector_common_white_btn.xml`：`state_pressed/state_selected/state_checked → @drawable/shape_btn_white_selected`（`@color/bg_button_press` 按下色）、默认 → `shape_btn_white_unselected`。而 app 壳工程 `application/Setting` 自己也定义了一个**同名**的 `shape_btn_white_selected.xml`，内容却完全不同（`@color/bg_segmentbutton`，用于左侧导航选中态背景）。Android 资源合并时 app 模块同名资源覆盖库模块资源，于是 `selector_common_white_btn` 的按下态引用被"劫持"到 Setting 的 segmenbutton 形状——颜色与未按下态几乎一致，按压反馈肉眼不可见。此外座椅布局原引用的 `selector_common_round_btn`（本次删除）本身也缺 `state_pressed` 条目（只有 selected/checked），按下同样无效果。修复三步：① Setting 的 `shape_btn_white_selected.xml` 重命名为 `shape_btn_white_bg.xml` 消除遮蔽（`ViewExtension.setChecked`、`NavAdapter` 引用同步更新）；② 座椅保存/编辑按钮 `layout_seat_adjustment.xml` 的 background 从 `selector_common_round_btn` 全部换成库里的 `selector_common_white_btn`；③ 删除 Setting 私有的 `selector_common_round_btn.xml`。

## 关键代码修改
改动文件：application/Setting/src/main/res/layout/layout_seat_adjustment.xml；application/Setting/src/main/res/drawable/shape_btn_white_selected.xml→shape_btn_white_bg.xml（重命名）；application/Setting/src/main/res/drawable/selector_common_round_btn.xml（删除）；application/Setting/src/main/java/com/yadea/setting/extension/ViewExtension.kt；application/Setting/src/main/java/com/yadea/setting/ui/adapter/NavAdapter.kt
```diff
--- application/Setting/src/main/res/layout/layout_seat_adjustment.xml
                                 android:id="@+id/btn_save_position1"
                                 android:layout_width="@dimen/dp_80"
                                 android:layout_height="@dimen/dp_45"
-                                android:background="@drawable/selector_common_round_btn"
+                                android:background="@drawable/selector_common_white_btn"
--- application/Setting/src/main/java/com/yadea/setting/extension/ViewExtension.kt
 fun TextView.setChecked(isChecked: Boolean) {
-    if (isChecked) this.setBackgroundResource(R.drawable.shape_btn_white_selected) else this.setBackgroundResource(
+    if (isChecked) this.setBackgroundResource(R.drawable.shape_btn_white_bg) else this.setBackgroundResource(
         R.drawable.shape_setting_gray_round_bg
     )
 }
```

## 为什么能修复
重命名后 CommonTools 的 `shape_btn_white_selected`（`bg_button_press` 按下色）不再被壳工程同名资源覆盖，`selector_common_white_btn` 的按下态恢复真实视觉差异；按钮换用含 `state_pressed` 条目的标准选择器，按下反馈齐全。副作用：改名触及 `NavAdapter` 导航选中态与 `ViewExtension.setChecked` 两处既有引用，需回归导航高亮显示；被删的 `selector_common_round_btn` 仅座椅布局引用，删除安全。

## 复盘与经验
- 多模块工程中，app 壳工程定义与公共库同名的 drawable/color 会静默覆盖库资源，症状是"库组件的交互态在 app 内失效"，极难直觉定位；公共资源命名应加模块前缀或统一收口到 CommonTools，壳工程禁止重名。
- selector 不配 `state_pressed` 条目却当"可点按钮"背景用，按下必然无反馈；新建 selector 时按下态是必备条目。
- 排查"点击效果失效"类问题的固定套路：反查 selector 各 state 指向的 shape → 搜同名资源在哪些模块出现 → 确认合并归属。
