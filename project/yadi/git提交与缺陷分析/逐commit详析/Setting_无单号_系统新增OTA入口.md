# 无单号 [SRS_SYSSetting_038] 系统新增OTA入口
- **提交**：`c55e754a` | 2026-08-18 | sgh | Setting | feature
- **关联单**：SRS_SYSSetting_038

## 需求/目标
系统设置页新增"系统版本"入口（OTA 升级跳转），同时修正 VIN 码为空时的分割线残留和右侧箭头图标不统一的小问题。

## 实现结构
- `fragment_system.xml`：原"个人隐私政策"与"用户协议"之间的分割线替换为 `tv_system_version` TextView（复用 `setting_title_black_style` 行样式 + `ic_expand_arrow` 箭头）；两处 `icon_right` 统一为 `ic_expand_arrow`；VIN 行 marginTop 20→12 并给分割线加 id。
- `SystemFragment.kt`：VIN 为空隐藏 `llVin` 时同步隐藏 `vinDivider`。
- strings：新增 `system_version`（仅中文，values-en 未加——该行会回退到默认资源，英文包显示中文）。
- 跳转逻辑本提交未见新增代码（入口先占位，点击行为应复用 tvPersonInfo 同类 fast-click 或后续提交补充）。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt
         } ?: run {
             mBinding.llVin.visibility = View.GONE
+            mBinding.vinDivider.visibility = View.GONE
         }
```
```xml
<!-- application/Setting/src/main/res/layout/fragment_system.xml -->
-            <View
+            <TextView
+                android:id="@+id/tv_system_version"
+                style="@style/setting_title_black_style"
                 android:layout_width="match_parent"
-                android:layout_height="@dimen/dp_1"
-                ...
-                android:background="@color/divider_default" />
+                android:gravity="center_vertical"
+                android:paddingVertical="@dimen/dp_20"
+                android:text="@string/system_version"
+                app:drawableRightCompat="@drawable/ic_expand_arrow" />
```
典型的小步 UI 迭代：分割线换成一级行入口，与上方"隐私政策/用户协议"视觉对齐；顺带处理 VIN 缺失场景的 UI 孤儿分割线。

## 复盘与要点
- 列表行"隐藏内容必须连分割线一起隐藏"是车机长列表常见坑，给分割线加 id 是最小改动方案。
- 只加中文 strings 不加 values-en 是高频遗漏（同日其他提交都是中英成对加），国际化回归时应重点扫这类入口文案。
- 入口先占位、行为后接的写法要求布局与点击逻辑分开提交时保持可追溯，本提交 diff 中未见 tvSystemVersion 的点击绑定，需在后续提交确认闭环。
