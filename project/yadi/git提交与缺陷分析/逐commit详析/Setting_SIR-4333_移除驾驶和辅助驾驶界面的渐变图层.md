# SIR-4333 · 移除驾驶和辅助驾驶界面的渐变图层

- **提交**：`7e415dad` | 2026-07-28 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
驾驶/辅助驾驶/场景模式界面顶部与底部显示渐变模糊遮罩，与最新需求不符（背景资源颜色错误，需求变更要求移除）。

## 根因分析
三个界面的布局在 `NestedScrollView` 之上叠加了 `top_virtual_driving_bg`/`bottom_virtual_driving_bg` 两个 48dp 的 View，背景引用 `@drawable/vir_top_bg`、`@drawable/vir_bottom_bg` 渐变图；Fragment 里又调用 `setupScrollFade(...)` 把滚动联动到这两个遮罩。需求变更后该渐变效果不再需要，整体属于视觉资源层面的变更。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt、application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt、application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/SceneModeDialogFragment.kt、application/Setting/src/main/res/layout/dialog_scene_mode.xml、application/Setting/src/main/res/layout/fragment_assisted_driving.xml、application/Setting/src/main/res/layout/fragment_driving.xml
```diff
// application/Setting/src/main/res/layout/fragment_driving.xml（另两个布局同构）
-        <View
-            android:id="@+id/top_virtual_driving_bg"
-            android:layout_width="match_parent"
-            android:layout_height="48dp"
-            android:layout_gravity="top"
-            android:background="@drawable/vir_top_bg" />
-
-        <View
-            android:id="@+id/bottom_virtual_driving_bg"
-            android:layout_width="match_parent"
-            android:layout_height="48dp"
-            android:layout_gravity="bottom"
-            android:background="@drawable/vir_bottom_bg" />
```
```diff
// AssistedDrivingFragment.kt / DrivingFragment.kt / SceneModeDialogFragment.kt
-        mBinding.vcNestedScrollView.setupScrollFade(mBinding.topVirtualDrivingBg, mBinding.bottomVirtualDrivingBg)
```
场景模式弹窗保留底部遮罩 View 但背景由 `vir_bottom_bg` 换为 `bg_bottom_linear_gradient`。

## 为什么能修复
删除遮罩 View 与 `setupScrollFade` 联动后，渐变图层彻底不再渲染，视觉与需求一致；三个 Fragment 同时移除联动调用，避免对已删除 id 的 binding 引用导致编译失败。无功能副作用，纯视觉变更。

## 复盘与经验
- 移除 UI 元素必须"布局 + 代码联动调用"成对清理，否则要么编译报错、要么残留无效逻辑。
- 渐变/模糊遮罩这类装饰性图层建议用独立 style/drawable 命名收敛，需求变更时只换资源即可，降低布局级返工成本。
