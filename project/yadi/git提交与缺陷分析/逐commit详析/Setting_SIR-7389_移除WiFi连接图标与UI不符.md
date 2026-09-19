# SIR-7389 · 移除 WiFi/蓝牙设备条目的展开箭头图标与 UI 不符
- **提交**：`189f8c9f` | 2026-09-04 | dufan | Setting | bugfix（资源替换）
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
设置-无线网络（WiFi 列表项）与已配对蓝牙设备条目上，用于"移除/展开"的右侧箭头图标样式与 UI 设计稿不符。

## 根因分析
多处布局与适配器引用了旧图标资源 `R.drawable.icon_right`，设计稿要求使用新的展开箭头 `ic_expand_arrow`。属资源引用错误（`icon_right` 为历史遗留图标），非逻辑问题；代码侧 `BluetoothAdapter` 动态 `setImageDrawable` 与三个 item 布局（`item_wlan.xml`、`item_paired_device.xml`、`item_paired_anw_device.xml`）各自写死，需逐处替换。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAdapter.kt`、`application/Setting/src/main/res/layout/item_paired_anw_device.xml`、`item_paired_device.xml`、`item_wlan.xml`

```diff
--- application/Setting/src/main/res/layout/item_wlan.xml
             android:layout_width="@dimen/dp_24"
             android:layout_height="@dimen/dp_24"
             android:layout_marginStart="@dimen/dp_6"
-            android:src="@drawable/icon_right"
+            android:src="@drawable/ic_expand_arrow"
             android:visibility="gone" />
```

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAdapter.kt
                 .setImageDrawable(
                     R.id.iv_arrow,
-                    ResourceUtils.getDrawable(R.drawable.icon_right)
+                    ResourceUtils.getDrawable(R.drawable.ic_expand_arrow)
                 )
```

## 为什么能修复
四处引用统一替换为设计稿指定的 `ic_expand_arrow`，代码动态设置与 XML 静态设置两种路径同步更新，视觉一致。无逻辑风险；隐患是同类旧图标可能还有其他引用点未清理，建议全局搜索确认。

## 复盘与经验
- 图标替换类缺陷要同时排查"XML 静态引用"与"适配器代码动态 setImageDrawable"两条路径，漏一条就出现列表滚动后图标变回旧样的诡异现象（复用 ViewHolder 时代码赋值覆盖 XML）。
- 设计切图入库时应统一命名规范（如 `ic_*`），及时废弃旧资源（`icon_right`），从源头减少误引用。
