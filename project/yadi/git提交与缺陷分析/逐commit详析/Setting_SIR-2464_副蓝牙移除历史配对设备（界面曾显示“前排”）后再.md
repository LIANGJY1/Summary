# SIR-2464 · 副蓝牙重新配对后前排条为0不显示静音图标
- **提交**：`86508828` | 2026-07-10 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙移除曾显示"前排"的历史配对设备后，再次搜索配对连接成功，界面直接显示后排（前排条为 0）时不显示静音图标，前后排角色分配异常。

## 根因分析
默认角色分配逻辑在 `BluetoothAnwFragment` 中：当 `frontCount == 0 || rearCount == 0` 时遍历 `BtAnwManager.mPairedDevices` 补分配角色，但准入条件只认 `dev.role == -1`（未分配）。重新配对的设备从系统侧带回的 `role` 可能是 `0`（旧状态残留的默认值，而非 -1），被排除在补分配之外，导致前排计数始终为 0、静音图标渲染条件不满足。缺陷库记录的"默认角色设置逻辑问题"即指此准入条件过窄；`BtAnwManager` 恢复旧状态时 `deviceBean.role = device.getRole()` 直接采用系统返回值，让 role=0 的设备进入列表。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt（+3/-1）、component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java（+3/-2）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
@@ 默认角色补分配
         if (frontCount == 0 || rearCount == 0) {
             BtAnwManager.getInstance().mPairedDevices.forEach { dev ->
-                if (dev.role == -1) {
+                val bothUnassigned = frontCount == 0 && rearCount == 0
+                val eligible = dev.role == -1 || (dev.role == 0 && bothUnassigned)
+                if (eligible) {
                     if (frontCount == 0) {
                         dev.role = 1
                         frontCount++
--- component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java（仅日志增强）
-            Log.d(TAG, "RemoteDevice list check Address:" + deviceAddress);
+            int role = device.getRole();
+            Log.d(TAG, "RemoteDevice list check Address:" + deviceAddress + ",role=" + role);
```

## 为什么能修复
准入条件放宽为"role==-1，或前后排都为空时 role==0 也可参与分配"，重新配对带回 role=0 的设备在两排全空的初始化场景下会被补分配到前排，`frontCount` 正常累加，静音图标随之正确显示；已手动分配过（role=1/2）的设备不受影响。`BtAnwManager` 改动仅为把 role 打进日志，便于复现低概率问题时定位。隐患：role 语义（-1/0/1/2）没有统一定义文档，0 与 -1 的边界仍靠场景约定。

## 复盘与经验
- **默认值语义要显式枚举**：role 出现 -1、0 两种"未分配"表示，说明枚举约定漂移；应定义唯一的 UNASSIGNED 值并在恢复状态时归一化。
- **修复点应尽量收敛在数据源头**：本修复在 UI 层放宽条件兜底，更彻底的做法是在 `BtAnwManager` 恢复 `DeviceBean` 时把非法 role 归一为 -1。
- **低概率问题先补日志再谈修复**：`device.getRole()` 进日志正是为低概率复现准备的观测手段，与本修复合并提交合理。
