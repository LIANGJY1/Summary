# SIR-7372 · 连接页 WiFi 显示"未连接"，无线网络详情页却显示"已连接"
- **提交**：`42f17f82` | 2026-09-03 | sgh | Setting | bugfix
- **缺陷库**：等级 D · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
车机连接 WiFi 后，设置-连接页汇总栏显示"未连接"（或"connect_on"文案），点开无线网络列表页却显示该 WiFi 已连接，两处状态互相矛盾。

## 根因分析
连接页 `ConnectViewModel.getWifiText()` 判断已连接的口径是 `mWxWifiManagerI.isWifiConnected`，该口径带有"可用性/联网校验"语义（WiFi 已关联但尚未验证通过或无外网时返回 false，即 Android 体系里 connected ≠ validated）；而无线网络详情/列表页的判断口径是"已关联到某网络"（`connectedNetworkId != AccessPoint.INVALID_NETWORK_ID`）。同一时刻两个页面用不同口径渲染，出现"汇总栏未连接、详情页已连接"的矛盾。WiFi 关联成功但 IP/DHCP 未就绪或无外网窗口期内尤其容易触发（对应"高概率"而非必现）。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/ConnectViewModel.kt`、`application/Setting/src/main/res/layout/dialog_wlan_pwd_edit.xml`

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/ConnectViewModel.kt
     fun getWifiText(): String {
         mWxWifiManagerI.let {
             return if (it.isEnable) {
-                if (mWxWifiManagerI.isWifiConnected)
+                // 判断口径与WiFi弹框一致：已关联到某个网络（无论是否有外网）即显示当前 WiFi 名称
+                if (mWxWifiManagerI.connectedNetworkId != AccessPoint.INVALID_NETWORK_ID)
                     mWxWifiManagerI.ssid
                 else getString(R.string.connect_on)
             } else {
```

```diff
--- application/Setting/src/main/res/layout/dialog_wlan_pwd_edit.xml
             android:layout_weight="1"
             android:background="@null"
+            android:maxLength="16"
             android:imeOptions="flagNoExtractUi|flagNoFullscreen"
             android:inputType="textPassword"
```

## 为什么能修复
连接页改用与 WiFi 弹框/详情页完全相同的"关联即连接"口径（`connectedNetworkId != AccessPoint.INVALID_NETWORK_ID`），只要 WiFi 已关联就显示 SSID，不再受联网验证状态影响，两处显示一致。捎带改动：WiFi 密码输入框加 `maxLength="16"` 限制密码长度。隐患：口径放宽后，"已关联但无网"也会显示 WiFi 名称而非"未连接"，若产品本意是展示"可上网状态"则需另找信号口径，但从本单结论看"关联即连接"才是全App统一语义。

## 复盘与经验
- "两处状态显示不一致"类 bug，先对比双方的判断口径：Android WiFi 有 associated / connected / validated 三层状态，`isWifiConnected` 与 networkId 判断语义不同，同一 App 内必须统一口径。
- 修复显示不一致时，注释里明确写出对齐的目标口径及理由（本例"与WiFi弹框一致"），防止后人再按直觉改回。
- 状态类封装（如 `WxWifiManager`）最好同时暴露多种语义的方法并在命名上区分（如 `isAssociated` / `isOnline`），避免调用方各取所需造成分歧。
