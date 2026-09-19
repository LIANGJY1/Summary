# SIR-1435 · 副蓝牙已配对设备主动发起连接无响应
- **提交**：`c876c314` | 2026-06-29 | daizhecheng | Setting(Hardwarelibs 蓝牙组件) | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
首次打开副蓝牙连接配对后，在"已可配对设备列表"中点击设备主动发起连接，车机无任何响应。

## 根因分析
连接入口在 `BtAnwManager`（`component/Hardwarelibs/.../anwBt/BtAnwManager.java`）。旧逻辑先调用 `AnW_Determine_Paired_Device_Type(profileSupport)` 得到设备类型 `devType`，随后做双重过滤：除了 `containsInt(config.sinkDevTypes, devType)` 的类型匹配外，还要求 `(profileSupport & config.sinkBitmask) != 0`（source 同理）位掩码校验；并且当 `profileSupport == 0 || -1 || 1` 时直接打日志 "don't know device support" 后什么都不做。问题在于：刚配对完的设备上报的 `profileSupport` 常常是 0/-1/1 这类"未知"值，或位掩码与本地 `sinkBitmask/sourceBitmask` 配置不吻合，于是设备类型明明在 `sinkDevTypes/sourceDevTypes` 列表里，连接请求却被这两个前置条件静默吞掉——用户点了连接，代码走到 else 分支只打了一条 debug 日志，不发起任何 `AnWBT_Connect_Service` 调用，表现为"无响应"。

## 关键代码修改
改动文件：`component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java`
```diff
--- a/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
@@ -401,20 +401,21 @@ public class BtAnwManager {
-                if (profileSupport == 0 || profileSupport == -1 || profileSupport == 1) {
-                    Log.d(TAG, "don't know device support " + config.name);
-                } else if (containsInt(config.sinkDevTypes, devType) && (profileSupport & config.sinkBitmask) != 0) {
+                if (containsInt(config.sinkDevTypes, devType)) {
                     mBtAdapter.AnWBT_Connect_Service(device.macAddress, config.sinkProfile);
-                } else if (containsInt(config.sourceDevTypes, devType) && (profileSupport & config.sourceBitmask) != 0) {
+                } else if (containsInt(config.sourceDevTypes, devType)) {
                     mBtAdapter.AnWBT_Connect_Service(device.macAddress, config.sourceProfile);
                 } else {
-                    Log.d(TAG, "device doesn't support " + config.name);
+                    Log.d(TAG, config.name + ": devType=" + devType
+                            + " does not match any known device type, trying fallback");
+                    mBtAdapter.AnWBT_Connect_Service(device.macAddress, config.fallbackProfile);
                 }
```

## 为什么能修复
修复删掉了两道"静默拦截"：一是 `profileSupport` 为 0/-1/1 时直接放弃连接的早退分支，二是 sink/source 匹配时的位掩码附加校验。现在只要 `devType` 命中已知设备类型就走对应 profile 连接；即使类型完全识别不出，也兜底用 `config.fallbackProfile` 发起连接，保证点击必有响应。隐患在于：对识别不出的设备一律走 fallback 连接，可能对确实不支持的设备发起无效连接尝试，依赖底层连接失败回执兜底；同时日志增强了 hex 输出，便于后续定位。

## 复盘与经验
- 连接/配对流程中"识别不出就静默 return"是典型的无响应来源，对用户操作必须有兜底路径（fallback），失败也应回显。
- 位掩码校验依赖对端设备上报值的准确性，刚配对阶段协议栈状态未稳定时这类值不可靠，不宜作为强拦截条件。
- 遇到"点击无响应"类问题，先查分支里是否只有 Log 没有 action——本次旧代码每个不满足条件都只打日志。
