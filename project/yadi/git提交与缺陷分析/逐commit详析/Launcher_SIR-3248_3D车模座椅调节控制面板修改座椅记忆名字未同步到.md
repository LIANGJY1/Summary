# SIR-3248 · 3D车模改座椅名未同步到车控车设（写入源不同：SPUtils vs SeatUserManager）· Launcher 侧

- **提交**：`4deed122` | 2026-07-23 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 3D车模

## 问题
在 3D 车模座椅调节控制面板修改座椅记忆名字后，车控车设页显示的名字不变（与 `cc65c389` 同单，本提交修 Launcher 写入侧）。

## 根因分析
两侧各写各的存储：`RenameDialogManager.showRenameDialog` 的 `confirm` 只写 Launcher 自己的 `SPUtils.setParam("cseat_position_name_N", newName)`（注释还声称"与 Setting 共用同一份数据"，实际并不成立），而 Setting 车控页读的是公共组件 `SeatUserManager`（JSON 文件）。元数据一针见血："Setting 不是读 Launcher 的 SPUtils，而是读公共组件里的 SeatUserManager"。数据源分叉导致 3D 车模改名永远到不了 Setting。另外广播 `sendSeatNameChangedBroadcast` 不带权限发送，且 `KanziSignalMapping.readSeatNameFromSp` 也读 SP 旧源，读取链路同样分叉。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/manager/SeatNameStore.java（新增）；application/Launcher/src/main/java/com/yadea/launcher/manager/RenameDialogManager.kt；application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java；application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
```diff
--- a/.../manager/SeatNameStore.java（新增，统一座椅名存取）
+    public static void saveSeatName(int position, String name) {
+        if (!isValidPosition(position) || name == null) return;
+        ensureCurrentUserExists();
+        SeatUserManager.INSTANCE.setSeatPositionName(position, name);
+        // Keep the old Launcher-local cache for compatibility with existing builds.
+        SPUtils.INSTANCE.setParam(LEGACY_SP_KEYS[position], name);
+    }
--- a/.../manager/RenameDialogManager.kt
-        val intent = Intent(ACTION_SEAT_NAME_CHANGED).apply { ... }
-        app.sendBroadcast(intent) //NOSONAR
+        val intent = Intent(SeatNameStore.ACTION_SEAT_NAME_CHANGED).apply { ... }
+        app.sendBroadcast(intent, com.yadea.launcher.Constants.BROADCAST_PERMISSION) //NOSONAR
@@ confirm()
-                    SPUtils.setParam(SEAT_POSITION_KEYS[position], newName)
+                    SeatNameStore.saveSeatName(position, newName)
                     sendSeatNameChangedBroadcast(newName, position)
--- a/.../control/KanziDataSourceManager.java
@@ Kanzi 初始化（sendInitData 链尾）
+        sendSeatNamesToKanzi();   // 初始化时主动下发 3 个座椅名，避免状态落后
@@ 广播注册
-        IntentFilter filter = new IntentFilter(ACTION_SEAT_NAME_CHANGED);
+        IntentFilter filter = new IntentFilter(SeatNameStore.ACTION_SEAT_NAME_CHANGED);
         mContext.registerReceiver(mSeatNameReceiver, filter, com.yadea.launcher.Constants.BROADCAST_PERMISSION, ...);
--- a/.../control/KanziSignalMapping.java
     private String readSeatNameFromSp(int position) {
-        Object value = SPUtils.INSTANCE.getParam(keys[position], defaults[position]);
-        return value instanceof String ? (String) value : defaults[position];
+        return SeatNameStore.getSeatName(position);
     }
```

## 为什么能修复
新增 `SeatNameStore` 作为唯一存取门面：写入时先 `SeatUserManager.setSeatPositionName`（Setting 读取的源）再兼容性回写旧 SP（旧版本读路径不炸），广播统一 action 并带 `BROADCAST_PERMISSION`；`KanziSignalMapping`/`KanziDataSourceManager` 的读路径全部改走 `SeatNameStore`，并在 Kanzi 初始化时主动把 3 个名称下发，避免车模初始显示落后。读写两侧数据源合流，同步问题从根上消除。隐患：双写（SeatUserManager + SP）期间两份存储仍需人工保持兼容，旧 SP 只出不进的话跨版本升级需留意；写路径的跨进程细节由后续 `74777571` 继续修正。

## 复盘与经验
- "同一份数据"必须落在同一个存储源上：注释声称共用、代码各写各的，是最危险的假一致；发现两套 key/两套 Manager 立即拉齐。
- 跨应用/跨模块共享数据抽一个 Store 门面（校验+默认值+兼容迁移集中处理），散落的 SPUtils 直接调用是分叉的温床。
- 自定义广播带上权限（`sendBroadcast(intent, permission)` + 注册时传 permission），防止第三方伪造座椅名等敏感车控数据。
