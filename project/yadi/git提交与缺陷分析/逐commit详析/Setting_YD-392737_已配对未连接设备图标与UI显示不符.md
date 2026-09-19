# YD-392737 · 已配对未连接设备图标与UI显示不符

- **提交**：`e75c7e39` | 2026-06-26 | daizhecheng | Setting | bugfix（UI 视觉修复）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
副蓝牙已配对列表里，耳机类设备（未连接/已连接态）显示的是头盔图标，与 UI 设计稿的耳机图标不符。

## 根因分析
`BluetoothAnwAdapter` 的 item 转换逻辑里存在两处图标赋值路径、且互不一致：未配对分支曾按 COD 设备类别（`cod and Constant.Major.BITMASK`）区分 `ic_helmet`/`ic_headphones`；而已配对（`isBonded`）分支的图标选择完全脱离 COD 判断——不管设备是头盔还是耳机，一律 `setImageResource(R.id.iv, R.drawable.ic_helmet)` / `ic_helmet_connected`。于是耳机设备一旦完成配对，就永远顶着头盔图标，"已配对未连接"场景必现。另外布局 `item_paired_anw_device.xml` 的默认 `android:src` 也是 `ic_helmet`，加载瞬间同样闪出头盔回图。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAnwAdapter.kt、application/Setting/src/main/res/drawable/ic_headphones.xml、application/Setting/src/main/res/drawable/ic_headphones_connected.xml（新增）、application/Setting/src/main/res/layout/item_paired_anw_device.xml

```diff
--- application/Setting/.../ui/adapter/BluetoothAnwAdapter.kt
@@ 已配对分支按设备类别选图标
-                    val majorCode = (cod) and Constant.Major.BITMASK
-                    if (majorCode == Constant.Major.AUDIO_VIDEO) {
-                        holder.setImageResource(R.id.iv, R.drawable.ic_helmet)
-                    } else {
-                        holder.setImageResource(R.id.iv,R.drawable.ic_headphones)
-                    }
                     if (isBonded) {
@@
                         val majorCode = (cod) and Constant.Major.BITMASK
+                        val major = BtAnwManager.isBluetoothHeadsetOrHelmet(majorCode)
                         if (isConnect) {
-                            holder.setImageResource(R.id.iv, R.drawable.ic_helmet_connected)
+                            if (major) holder.setImageResource(R.id.iv, R.drawable.ic_headphones_connected)
+                            else holder.setImageResource(R.id.iv, R.drawable.ic_helmet_connected)
                         } else {
-                            holder.setImageResource(R.id.iv, R.drawable.ic_helmet)
+                            if (major) holder.setImageResource(R.id.iv, R.drawable.ic_headphones)
+                            else holder.setImageResource(R.id.iv, R.drawable.ic_helmet)
                         }
```

```diff
--- application/Setting/src/main/res/layout/item_paired_anw_device.xml
@@ 默认图与类别一致
-                android:src="@drawable/ic_helmet" />
+                android:src="@drawable/ic_headphones" />
```
新增 `ic_headphones_connected.xml`：与 `ic_headphones` 同一 pathData、填充色改为高亮蓝 `#1A8EFF`，形成"未连接灰/已连接蓝"成对图标。

## 为什么能修复
修复把"图标类型"的判定依据统一回 COD 设备类别（`BtAnwManager.isBluetoothHeadsetOrHelmet(majorCode)`），并挪进 `isBonded` 分支内部，使未连接/已连接两种状态各自都有 helmet、headphones 成对图标，消除了"配对后图标被写死"的根因；布局默认 `src` 同步换成耳机图，避免绑定前的默认图闪烁错误。无逻辑副作用，纯展示层修复；注意 `ic_headphones_connected` 是靠复制 pathData 换色实现的，后续改矢量图形需要两个文件同步维护。

## 复盘与经验
- **同一数据的多状态图标要成对设计**：`ic_headphones` / `ic_headphones_connected`（灰/蓝）模式比"一张图到处复用"更符合状态语义，也方便夜间模式换色。
- **分支合并时别丢判定条件**：图标选择逻辑从"按 COD 判断"退化为"写死一张图"，正是状态分支重构时丢弃既有条件的典型回归。
- **item 布局的默认 `android:src` 也是 UI 的一部分**：默认图应与最常见数据形态一致，否则绑定窗口期就会露馅。
