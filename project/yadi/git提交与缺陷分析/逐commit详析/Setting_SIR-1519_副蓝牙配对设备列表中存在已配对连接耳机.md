# SIR-1519 · 副蓝牙已配对耳机点击"切换"按钮无响应

- **提交**：`c94193c4` | 2026-07-03 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙配对设备列表中存在已配对并连接的耳机时，点击"切换"（前后排角色互换）按钮没有任何响应。

## 根因分析
三处叠加导致"切换"形同虚设：
1. **切换前不断开连接**：原 `iv_switch` 回调只是收集 role==1（前排）/role==0（后排）的 MAC 后直接调 `BtAnwManager.switchRole(second, main)`，耳机仍处于连接态，底层角色切换无法生效（rc："切换前断开连接"）。
2. **角色未分配时参数为空串**：设备 role 为 -1（未标记前后排）时 `main`/`second` 保持 `""`，`switchRole("","")` 是空操作——这解释了"无响应"。
3. **切换后 role 又被回滚**：`BtAnwManager.switchRole()` 恢复历史状态时 `deviceBean.role = old.role` 把旧角色抄了回去，即使底层切换成功，本地缓存的 role 也没变。此外本地 `mPairedDevices` 的 role 与底层广播 `ACTION_ANW_BT_UPDATE_DEVICE_ROLE` 从不回写、不持久化，状态长期失真。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt`、`component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/BtAdapter.java`、`component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java`（共 +114/-23）
```diff
// --- BluetoothAnwFragment.kt 切换按钮回调（断开→换角色→延时重连）
+                                    if (clickedDevice.role == 1) {
+                                        rearDevice?.let {
+                                            BtAnwManager.getInstance().connectHfp(it, false)
+                                            BtAnwManager.getInstance().connectA2dp(it, false)
+                                        }
+                                        rearDevice?.let { rear ->
+                                            frontDevice?.let { front ->
+                                                BtAnwManager.getInstance()
+                                                    .switchRole(rear.macAddress, front.macAddress)
+                                            }
+                                        }
+                                        delay(2000.milliseconds)
+                                        rearDevice?.let {
+                                            BtAnwManager.getInstance().connectHfp(it, true)
+                                            BtAnwManager.getInstance().connectA2dp(it, true)
+                                        }
+                                    } else { /* 点击后排设备时对称地断开前排再切换 */ }
```
```diff
// --- BtAnwManager.java switchRole() 恢复状态时保留新角色
-                        deviceBean.role = old.role;
+                        deviceBean.role = device.getRole();
// --- BtAnwManager.java setRole()/角色更新广播回写本地缓存并持久化
+        for (DeviceBean device : mPairedDevices) {
+            if (device.macAddress.equals(macAddress)) {
+                device.role = role;
+                break;
+            }
+        }
+        saveDeviceList(mPairedDevices);
```
另在 `BluetoothAnwFragment` 中新增 role 自动分配：当前排或后排计数为 0 时，把 role==-1 的设备自动补位为前排/后排并 `setRole()` 下发，保证切换参数不再为空。

## 为什么能修复
"先断开、再 switchRole、延时 2s 重连"让底层在无连接约束下完成角色互换；role==-1 自动补位保证 main/second 恒有值；`deviceBean.role = device.getRole()` 与广播回写 + `saveDeviceList()` 持久化，让本地 role 与底层保持一致。副作用：整条链路依赖固定的 `delay(2000ms)` 等待断连完成，慢设备上仍可能时序竞争；重连分支两处都重连 rearDevice（else 分支日志却写 frontDevice），有复制粘贴痕迹；`BtAdapter.AnWBT_Disconnect_Service` 重构后出现 executeAidl 双层嵌套、并移除了 NumberFormatException 防御，属顺带改动且引入了小瑕疵。

## 复盘与经验
- **蓝牙角色/连接类操作要遵循"断开→变更→重连"时序**：在链路保持连接时做配置切换，很多协议栈会静默失败。
- **"无响应"往往不是事件没收到，而是参数退化成空操作**：role=-1 未初始化导致 `switchRole("","")`，防御性校验+自动补位比静默失败好。
- **本地缓存与底层广播是两份状态**：收到 `ACTION_ANW_BT_UPDATE_DEVICE_ROLE` 后必须回写缓存并持久化，否则下次操作的仍是旧数据。
