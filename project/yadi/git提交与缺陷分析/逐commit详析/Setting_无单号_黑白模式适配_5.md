# 无单号 · Setting 黑白模式适配（蓝牙配对弹窗/列表 onConfigurationChanged 刷肤）

- **提交**：`02bbb1e2` | 2026-06-30 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
蓝牙配对与设备列表相关页面的黑白模式适配：配对弹窗 Activity、蓝牙列表 Adapter、蓝牙弹窗 Fragment 在 uiMode 切换时重设颜色，并新增"已配对/可用设备"分组分隔线条目类型。

## 实现结构
- `PairDialogActivity.kt`：新增 `onConfigurationChanged`，重刷 PIN 码 6 格背景/文字、确认/取消按钮背景（`selector_common_black_btn`/`selector_common_white_btn` 语义反色）等 12 处；删除 5 秒自动 finish 的注释代码。
- `BluetoothAdapter.kt`：多类型 Adapter 新增 `TITLE_DIVIDER` 分支（分割线颜色 `divider_default`）；标题、设备名、箭头、刷新按钮全部补 `setTextColor/setImageDrawable(语义资源)`；已连接设备删掉错误的 `ic_connect_un_carplay` 图标设置（蓝牙设备不该显示 carplay 图标）。
- `BluetoothDialogFragment.kt` / `BluetoothFragment.kt`：`onConfigurationChanged` 转发刷新。
- 布局：`activity_require_pair.xml`、`dialog_bluetooth.xml` 各加 1 行视图，新增 `item_connect_divider.xml`。

数据流：uiMode 变化 → 各层 onConfigurationChanged → 逐控件重设语义资源；列表刷新时 Adapter 按 itemType 渲染（新增分隔线类型插入分组之间）。

## 关键代码
```diff
# application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAdapter.kt
         when (item.itemType) {
+            MultiBluetoothDeviceType.TITLE_DIVIDER.type -> {
+                holder.setBackgroundColor(
+                    R.id.view_line, ResourceUtils.getColor(R.color.divider_default)
+                )
+            }
...
                 holder.setText(R.id.tv_title, title)
+                    .setTextColor(R.id.tv_title, ResourceUtils.getColor(R.color.text_default_press))
+                    .setImageDrawable(
+                        R.id.iv_refresh, ResourceUtils.getDrawable(R.drawable.ic_refresh)
+                    )
```
```diff
# application/Setting/src/main/java/com/yadea/setting/ui/activity/PairDialogActivity.kt
+    override fun onConfigurationChanged(newConfig: Configuration) {
+        super.onConfigurationChanged(newConfig)
+        setBackgroundResource(mBinding.root, R.drawable.bg_dialog_dim)
+        setBackgroundResource(mBinding.tvConfirm, R.drawable.selector_common_black_btn)
+        mBinding.tvConfirm.setTextColor(ResourceUtils.getColor(R.color.selector_common_text_color_white))
+        setBackgroundResource(mBinding.tvCancel, R.drawable.selector_common_white_btn)
+        ...
+    }
```

实现讲解：这是"手动刷肤清单"模式在弹窗场景的完整样本：根布局遮罩 → 卡片 → PIN 格 → 按钮对（黑底白字/白底黑字在夜间互换）逐一重设，配合命名成对的 `selector_common_black_btn/white_btn` 让按钮反色只需互换引用。Adapter 里删除已连接设备错误显示 carplay 图标的改动，实质是顺手修了一个显示缺陷。

## 复盘与要点
- 可复用手法：弹窗类黑白适配注意两层——Dialog 窗体背景（dim 遮罩 + 圆角卡片）与内容控件都要重刷；`selector_common_black/white_btn` 成对命名是按钮反色的简化方案。
- 分隔线做成独立 itemType 而非在 item 里 if-else，符合 BaseMultiItemQuickAdapter 的多类型设计，新增类型即可复用。
- 遗留风险：此类逐控件 `onConfigurationChanged` 清单在控件增删时极易失同步（新增控件忘加一行），长期应沉淀为统一的 `refreshTheme()` 工具或改用自动资源绑定。
