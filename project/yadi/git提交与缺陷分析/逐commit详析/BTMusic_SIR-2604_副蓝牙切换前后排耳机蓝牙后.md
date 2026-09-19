# SIR-2604 · 副蓝牙切换前后排耳机后后排耳机无法自动重连
- **提交**：`4b738678` | 2026-07-14 | daizhecheng | BTMusic | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙在前后排耳机之间切换后，后排蓝牙耳机无法自动重连。

## 根因分析
两处叠加：
1. **复制粘贴变量错**：`BluetoothAnwFragment` 重连协程的第二条分支里，日志写着 `"reconnect device frontDevice ..."`，代码却是 `rearDevice?.let { ... }` —— 前排重连路径实际去连了后排设备（或反之），切换场景下目标设备张冠李戴，后排耳机自然收不到重连动作。
2. **连接标志残留**：切换前后排会触发断开/重连，但 `DeviceBean` 的 `hfpFlag/hfpagFlag/a2dpFlag/a2dpSourceFlag` 仍停在 `BT_STATUS_CONNECT`；`BtAnwManager.disconnect` 只调 `AnWBT_Disconnect_Service` 发起断开，标志位要等底层回调才可能复位，重连逻辑若依据标志判断"已连接"就会跳过连接。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt（+12/-2）、component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java（+19/-6）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
@@ 重连协程分支一：连后排前先清标志
                                             delay(2000.milliseconds)
+                                            resetDeviceFlags(frontDevice)
                                             rearDevice?.let { ... connectHfp(it, true); connectA2dp(it, true) ... }
@@ 重连协程分支二：原代码误用 rearDevice，改为 frontDevice
                                             delay(2000.milliseconds)
-                                            rearDevice?.let {
+                                            resetDeviceFlags(rearDevice)
+                                            frontDevice?.let {
                                                 log("reconnect device frontDevice mac =${it.macAddress} ${it.name}")
@@ 新增
+    private fun resetDeviceFlags(device: DeviceBean?) {
+        device?.let {
+            it.hfpFlag = "0"; it.hfpagFlag = "0"; it.a2dpFlag = "0"; it.a2dpSourceFlag = "0"
+            log("resetDeviceFlags: cleared flags for ${it.macAddress}")
+        }
+    }
--- component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
@@ ProfileConfig 增加清标志回调
+        final BiConsumer<DeviceBean, String> clearFlag1;
+        final BiConsumer<DeviceBean, String> clearFlag2;
@@ 断开发起时同步清标志（不等回调）
             if (Objects.equals(flag1, Constant.BT_STATUS_CONNECT)) {
+                config.clearFlag1.accept(device, Constant.BT_STATUS_DISCONNECT);
                 mBtAdapter.AnWBT_Disconnect_Service(device.macAddress, config.sourceProfile);
             } else if (Objects.equals(flag2, Constant.BT_STATUS_CONNECT)) {
+                config.clearFlag2.accept(device, Constant.BT_STATUS_DISCONNECT);
                 mBtAdapter.AnWBT_Disconnect_Service(device.macAddress, config.sinkProfile);
             }
```
（HFP_CONFIG/A2DP_CONFIG 构造处分别绑定 `setHfpFlag/setHfpagFlag`、`setA2dpFlag/setA2dpSourceFlag`）

## 为什么能修复
分支二的变量纠正让"连前排"真正连前排，设备指向归位；`resetDeviceFlags` 在重连前把四个 profile 标志清零，绕开"标志残留导致跳过连接"的判定；`BtAnwManager` 在发起断开的同步路径上立即置 `BT_STATUS_DISCONNECT`，从源头保证断开期间标志不残留，两层防御覆盖 UI 时序与协议栈时序。隐患：`BT_STATUS_DISCONNECT` 若随后被迟到的"已断开"回调之外的状态事件覆盖为 CONNECT，仍可能脏标志；清零字符串 "0" 与枚举常量混用，类型安全欠佳。

## 复盘与经验
- **复制粘贴代码先查变量名**：日志与代码不一致（log 写 frontDevice、代码连 rearDevice）是典型笔迹，review 时"日志文本 vs 实参"交叉核对可当场抓住。
- **重连逻辑不要信缓存状态**：连接标志这类缓存必须在实际发起连接前重置，或以底层真实状态为准；"标志说连着"不等于"链路连着"。
- **断开要同步置中间态**：发起新动作（重连）前，把依赖状态在动作入口处显式复位，比等异步回调兜底可靠。
