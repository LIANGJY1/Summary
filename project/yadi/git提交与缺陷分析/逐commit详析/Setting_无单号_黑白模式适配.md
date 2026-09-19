# 无单号 · Setting 黑白模式适配（死代码 Dialog 清理 + 语义色切换）

- **提交**：`06000dac` | 2026-06-29 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
Setting 模块黑白模式适配的清理面：直接删除 4 个已被 `CommonTools` 公共对话框替代的旧 DialogFragment（约 525 行），其余保留的对话框/控件颜色引用切换为语义色，删除无用的 night 专用 drawable。

## 实现结构
- 删除：`CarbitToHiCarDialogFragment.java`(117 行)、`ConnectionDialogFragment.java`(133)、`SwitchBTDeviceDialogFragment.java`(129)、`SwitchBTTypeDialogFragment.java`(146)——均为旧车机互联/蓝牙切换弹窗，功能已由公共 DialogUtil 承接。
- 修改：`CustomEditDialogFragment.java`、`HotspotDialogFragment.kt`、`WlanCustomEditDialogFragment.kt` 中 `text_unclickable_color/text_default_color` → `text_default_press/text_default_default`，并去掉"uiMode==19 时手动判日夜"的特殊分支。
- 资源：删除 `ic_expand_arrow.png`(mdpi 位图) 补 `ic_expand_arrow.xml` vector；删除 `bg_tab_layout_night.xml`、`bg_button_layout*.xml`、`bg_press_common.xml`、`bg_switch_card.xml` 等夜间/旧皮肤专用 drawable；`selector_bluetooth_text.xml`、`bg_bottom_linear_gradient.xml`、`ic_bluetooth/ic_hotspot/ic_wifi/icon_tab_*` 改语义色。

数据流：无新增逻辑；删除"按 uiMode 值写死 if-else"后，颜色完全交给资源限定符在 uiMode 变化时自动解析。

## 关键代码
```diff
# application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/CustomEditDialogFragment.java
-                    btConfirm.setTextColor(getResources().getColor(R.color.text_unclickable_color));
+                    btConfirm.setTextColor(getResources().getColor(R.color.text_default_press));
...
-                    int uiMode = requireContext().getResources().getConfiguration().uiMode;
-                    if (uiMode == 19) {
-                        btConfirm.setTextColor(getResources().getColor(R.color.text_default_color));
-                    } else {
-                        btConfirm.setTextColor(getResources().getColor(R.color.white));
-                    }
+                    btConfirm.setTextColor(getResources().getColor(R.color.text_default_default));
```
```diff
# application/Setting/src/main/res/drawable-mdpi/ic_expand_arrow.png 删除，新增 ic_expand_arrow.xml
+<vector ... android:fillColor="@color/icon_default_default" />
```

实现讲解：`uiMode == 19`（UI_MODE_NIGHT_YES|UI_MODE_TYPE_NORMAL 的组合值）这种魔数判断是典型的反模式——它把本该由资源系统做的事搬进了业务代码，且只覆盖"当前模式"而非"模式变化"。删除死代码弹窗则压缩了适配面：要适配的文件越少，黑白模式收尾越快。

## 复盘与要点
- 可复用手法：主题适配前先做一轮死代码/重复实现清理（尤其被公共库替代的旧 Dialog），能直接减少 30%+ 的适配工作量。
- `Configuration.uiMode` 数值比较（==19）不可读且脆弱，判断日夜应使用 `uiMode and UI_MODE_NIGHT_MASK`。
- 遗留风险：一次删除 4 个 Fragment 需确认 AndroidManifest/导航图无残留注册引用，提交说明未提及验证方式。
