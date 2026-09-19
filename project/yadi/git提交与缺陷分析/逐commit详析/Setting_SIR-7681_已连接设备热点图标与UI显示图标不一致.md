# SIR-7681 · 已连接设备热点图标与UI不一致
- **提交**：`37269f8c` | 2026-09-08 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
热点管理列表中"已连接设备"条目显示的是通用 WiFi 图标 `ic_wifi`，与 UI 稿要求的手机/设备类图标不一致。

## 根因分析
热点接入设备列表 item 布局 `item_hotspot.xml` 中的图标控件 `iv`（ImageView，36dp）初始直接复用了 WiFi 信号图标 `@drawable/ic_wifi`，而 UI 变更后已连接设备需要专属的"设备"矢量图标。修复方式是新增矢量 drawable `icon_ap_device.xml`（36x36 viewport，填充色引用主题色 `@color/icon_default_default`，path 绘制的是带底部横线的手机轮廓），并在布局中替换引用。属于 UI 资源未随设计稿同步的纯资源类缺陷。

## 关键代码修改
改动文件：application/Setting/src/main/res/drawable/icon_ap_device.xml（新增）；application/Setting/src/main/res/layout/item_hotspot.xml
```diff
--- application/Setting/src/main/res/layout/item_hotspot.xml
@@ -15,7 +15,7 @@
             android:id="@+id/iv"
             android:layout_width="@dimen/dp_36"
             android:layout_height="@dimen/dp_36"
-            android:src="@drawable/ic_wifi" />
+            android:src="@drawable/icon_ap_device" />
```
新增 icon_ap_device.xml：36dp 矢量图，`android:fillColor="@color/icon_default_default"`，pathData 绘制设备轮廓（二进制之外的矢量 XML 资源）。

## 为什么能修复
布局引用切到新图标后，列表项渲染即为 UI 稿图标；新图标使用 `@color/icon_default_default` 主题色而非硬编码，深浅色主题下均可正常着色，无逻辑副作用。隐患仅是图标资源命名（`icon_ap_device`）与旧 `ic_wifi` 前缀风格不统一。

## 复盘与经验
- UI 变更类缺陷（图标/切图）多发生在设计稿 late change 后未同步代码；建立"UI 变更→资源替换清单"可减少此类走查返工。
- 列表 item 复用通用图标（ic_wifi）时，应确认其语义是否匹配条目类型，语义不符即使视觉近似也会被 UI 走查打回。
- 矢量图标 fillColor 引用 color 资源而非硬编码，可同时满足多主题要求。
