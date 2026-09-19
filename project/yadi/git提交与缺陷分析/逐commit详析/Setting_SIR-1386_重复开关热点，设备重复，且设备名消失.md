# SIR-1386 · 重复开关热点后已连接设备名消失

- **提交**：`5d65bec8` | 2026-07-08 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
重复开关热点后，已连接设备列表出现异常：设备显示重复、且设备名消失（列表出现空名条目）。

## 根因分析
底层（wifi AP 服务）在热点反复开关后上报的客户端记录里**name 字段可能为空**（rc："底层未返回设备名"）。`HotspotAdapter.convert()` 直接 `holder.setText(R.id.tv_name, item.name)`，空名直接渲染成空白条目，用户看到"设备名消失"；数据侧没有任何兜底标识，空名条目与重复条目在界面上难以区分。本 diff 的修复是把展示兜底为 MAC 地址，并删除了从未赋值的 `tv_address` 副控件、补充 AP 状态广播中 `WIFI_AP_FAILURE_REASON` 的日志便于底层定位。**注意：单据现象中的"设备重复"在本次 diff 中没有对应的去重逻辑修改**，以 diff 实际内容为准，本次修复针对的是"设备名消失"这一半症状。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/adapter/HotspotAdapter.kt`（+6/-5）、`application/Setting/src/main/res/layout/item_hotspot.xml`（-8，删除 tv_address）、`component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/network/ap/WxApEventManager.java`（+2/-1）
```diff
// --- application/Setting/src/main/java/com/yadea/setting/ui/adapter/HotspotAdapter.kt
-        holder.setText(R.id.tv_name, item.name)
+        LogUtils.d("HotspotAdapter", "convert: $item")
+        holder.setText(R.id.tv_name, if (TextUtils.isEmpty(item.name)) item.address else item.name)
```
```diff
// --- component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/network/ap/WxApEventManager.java
         public void onReceive(Context context, Intent intent) {
-            LogService.AP.logD(WxApEventManager.TAG, "ApStateHandler");
+            int failureReason = intent.getIntExtra("android.net.wifi.extra.WIFI_AP_FAILURE_REASON", -1);
+            LogService.AP.logD(WxApEventManager.TAG, "ApStateHandler--failureReason:" + failureReason);
```

## 为什么能修复
空名时回退显示 MAC 地址，设备条目永远有唯一可辨识文本，"设备名消失"不再出现；MAC 天然区分条目，也顺带缓解了"看起来重复"的迷惑性。`tv_address` 布局删除与 adapter 不再设置其颜色保持一致，避免悬空 id。隐患：MAC 地址直接展示给终端用户可读性差且涉及隐私展示习惯，产品上可考虑显示为"未知设备(MAC后4位)"；重复条目的去重仍依赖数据层，未在本次闭环。

## 复盘与经验
- **列表渲染对必现字段要做空值兜底**：name 这类"应该有值"的字段在底层异常时照样为空，display 层 fallback（name→address→"未知设备"）是标配。
- **一个单据两个症状，diff 可能只修一半**：复盘时要对照 diff 与 rc 逐条核对，"设备重复"未修应另开单跟踪而不是随本单关闭。
- 底层异常难复现时，先把关键 extra（如 WIFI_AP_FAILURE_REASON）打进日志，是低成本高收益的观测性投资。
