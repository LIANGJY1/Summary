# SIR-3219 · 不兼容耳机多次发起连接失败（未知devType走了fallbackProfile）

- **提交**：`f2733011` | 2026-07-23 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设（rc/sol 在库中未填，按提交单分析）

## 问题
副蓝牙"已可配对列表"中的耳机主动多次发起连接时，车机侧始终无法连接成功。

## 根因分析
`BtAnwManager.connectProfile()` 按 `devType`（设备类型）选择要连接的 profile：命中 `config.sinkDevTypes` 用 `sinkProfile`，命中 `config.sourceDevTypes` 用 `sourceProfile`，都不命中时走 `config.fallbackProfile`。对主动发起连接的不兼容耳机（元数据："耳机不兼容"），其 `devType` 恰恰不在两个已知列表里，于是每次都走 `fallbackProfile`——而这类耳机并不支持该 fallback profile，协议栈连接请求发出去后配不上，多次重试全部失败。修复思路是"兜底别用生僻 profile，回到最通用的 sink 通道"。

## 关键代码修改
改动文件：component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
```diff
--- a/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
@@ private void connectProfile(DeviceBean device, boolean isConnect, ProfileConfig config, ...)
         int profileSupport = device.getProfile_support();
+        Log.d(TAG, "connectProfile() device=" + device.macAddress + ",isConnect=" + isConnect
+                + ",config=" + config.toString() + ",flag1=" + flag1 + ",flag2=" + flag2);
         if (isConnect) {
             ...
                 if (containsInt(config.sinkDevTypes, devType)) {
                     mBtAdapter.AnWBT_Connect_Service(device.macAddress, config.sinkProfile);
                 } else if (containsInt(config.sourceDevTypes, devType)) {
                     mBtAdapter.AnWBT_Connect_Service(device.macAddress, config.sourceProfile);
                 } else {
-                    mBtAdapter.AnWBT_Connect_Service(device.macAddress, config.fallbackProfile);
+                    // 兼容异常特殊场景：未知 devType 也用 sinkProfile 连接
+                    mBtAdapter.AnWBT_Connect_Service(device.macAddress, config.sinkProfile);
                 }
```
（其余 hunks 为日志文案统一：如 `"Connect " + config.name` → `"connectProfile " + config.name`，便于按方法名过滤日志，无逻辑变化。）

## 为什么能修复
未知 `devType` 的耳机从 `fallbackProfile` 改走 `sinkProfile`（耳机类设备普遍支持的通用 sink 通道），连接命令落在对端真实支持的 profile 上，"多次发起都连不上"的链路被打通；入口新增的 `connectProfile()` 全参日志让后续同类问题可直接看到选型过程。隐患：`fallbackProfile` 从此成为死配置（若确有设备只能靠 fallback 连接，会回归）；用 sink 兜底对"期望 source 通道"的异常设备可能建立错误方向的连接，需要测试覆盖。

## 复盘与经验
- 兼容性问题先看"未命中分支"：`if/elseif/else` 里的 else 兜底最容易被塞进一个从未验证过的配置（fallbackProfile），对存量老设备反而最致命。
- 蓝牙 profile 选型应以对端实际支持为准，未知类型设备退到最大公约数（sink/HFP）比退到专用 profile 稳。
- 修兼容性 bug 时顺手统一日志前缀（此提交把散乱日志归到 `connectProfile` 前缀），下次排查能按方法一键过滤。
