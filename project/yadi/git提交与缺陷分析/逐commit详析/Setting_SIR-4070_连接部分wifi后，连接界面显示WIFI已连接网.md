# SIR-4070 · 连接部分 Wi-Fi 后列表不显示已连接网络且会断连

- **提交**：`449df220` | 2026-07-30 | dufan | Setting（实改 component/Hardwarelibs） | bugfix
- **缺陷库**：等级 C · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
连接"部分" Wi-Fi 后，连接界面能显示已连接网络和名称，但进入 Wi-Fi 列表页却不显示该已连接网络，且操作会导致断连。关键线索是"部分"——与特定加密类型的网络有关。

## 根因分析
`AccessPoint.matches(WifiConfiguration)` 是扫描结果与已保存配置的匹配核心，原实现有两处"数据对比错误"：
1. **安全类型枚举不含 WPA3/SAE**：`getSecurity(ScanResult)` 只识别 WEP/PSK/EAP，WPA3 网络（capabilities 含 SAE、无 PSK）被归为 0（开放）；`getSecurity(WifiConfiguration)` 只查 `allowedKeyManagement` 的 BIT1（WPA_PSK），SAE（BIT4/KeyMgmt.SAE）落到分支末尾按 WEP/open 计算。扫描侧算出的 security 与配置侧算出的 security 不相等 → `matches()` 返回 false → 已连接网络的 AP 无法与保存配置关联，列表重建时"已连接/已保存"状态丢失，表现为列表中不显示已连接网络；再次点击连接时系统走全新连接流程，触发断连。
2. **matches 分类混乱**：非 Passpoint AP 也会拿 Passpoint 配置来比 SSID（`config.FQDN.equals(this.mConfig.providerFriendlyName)` 字段还配错了），Passpoint 与普通网络互相误比，另有无空值保护（config 为 null 直接 NPE 风险）。
配套问题：`WifiTracker.updateAccessPoints` 构建 saved 列表时未按 `networkId` 去重，重复配置会生成重复 AP 条目，加剧"列表显示异常"。

## 关键代码修改
改动文件：component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/network/wifi/AccessPoint.java；component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/network/wifi/WifiTracker.java

```diff
--- component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/network/wifi/AccessPoint.java
@@ matches(WifiConfiguration)
-        if (isPasspoint(this.mConfig)) {
-            return config.FQDN.equals(this.mConfig.providerFriendlyName);
+        if (config == null) return false;
+        if (isPasspoint(this.mConfig) && isPasspoint(config)) {
+            return Objects.equals(config.FQDN, this.mConfig.FQDN);
+        } else if (!isPasspoint(this.mConfig) && !isPasspoint(config)) {
+            String targetSsid = removeDoubleQuotes(config.SSID);
+            return Objects.equals(this.ssid, targetSsid) && this.security == getSecurity(config);
         } else {
-            return this.ssid.equals(removeDoubleQuotes(config.SSID)) && this.security == getSecurity(config);
+            return false;
         }
@@ getSecurity(WifiConfiguration)
-        if (config.allowedKeyManagement.get(1)) {
+        boolean isPersonal = config.allowedKeyManagement.get(1) || config.allowedKeyManagement.get(4);
+        if (isPersonal) {          // BIT1:WPA_PSK  BIT4:SAE(WPA3)
             return 2;
         } else if (!config.allowedKeyManagement.get(2) && !config.allowedKeyManagement.get(3)) {
+            if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) {
+                return 2;
+            }
             return (config.wepKeys != null && config.wepKeys[0] != null && !config.wepKeys[0].isEmpty()) ? 1 : 0;
```

```diff
--- component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/network/wifi/AccessPoint.java
@@ getSecurity(ScanResult)
-        } else if (result.capabilities.contains("PSK")) {
+        if (cap.contains("PSK") || cap.contains("SAE")) {   // PSK(WPA2) || SAE(WPA3个人) 统一归为2
             return 2;
```

```diff
--- component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/network/wifi/WifiTracker.java
+            Set<Integer> addNetworkIds = new HashSet<>();
             while (var7.hasNext()) {
                 WifiConfiguration config = (WifiConfiguration) var7.next();
+                if (addNetworkIds.contains(config.networkId)) {
+                    continue;
+                }
+                addNetworkIds.add(config.networkId);
```

## 为什么能修复
两侧 `getSecurity` 对 SAE/WPA3 归一到同一枚举值 2 后，"扫描结果 vs 保存配置"的 security 对比在 WPA3 网络上首次相等，`matches()` 能正确把已连接 AP 关联到配置，列表恢复"已连接"展示，不再误触发新连接/断连；`matches` 按"同为 Passpoint 比较较 FQDN、同为普通网比较较 SSID+security、类型不同直接 false"重写后消除跨类误配与 NPE；`networkId` 去重消除重复条目。"部分 Wi-Fi"的神秘性被解释——正是 WPA3/SAE 类网络。隐患：`matches` 对"类型不同返回 false"更严格，若存在历史上靠跨类误配"碰巧工作"的场景会行为变化；大量 `Log.d` 调试日志留在生产代码中。

## 复盘与经验
- **匹配逻辑两侧的"归一化"必须同步演进**：新增加密协议（WPA3/SAE）时，扫描侧与配置侧的安全枚举要同时更新，只改一侧即造成"部分网络"匹配失败的隐性分区。
- **"部分设备/部分网络才复现"优先怀疑枚举缺项**：老协议 WPA2 全对、新协议 SAE 全错，是典型的枚举遗漏指纹。
- **身份匹配（matches）要分类互斥 + 空安全**：先分同类，再在同类别内比较键值；跨类直接 false，杜绝 Passpoint 与普通网的字段错配（FQDN 对 providerFriendlyName 这类字段张冠李戴）。
- **列表构建去重要在源头做**（按 networkId），UI 层的"显示异常"常常是数据层重复/未关联的直接投影。
