# SIR-7889 · 已记忆但未连接的 WiFi 无法移除

- **提交**：`43d12ec4` | 2026-09-10 | sgh | Setting | bugfix
- **缺陷库**：等级 D · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
对记忆（已保存）的 WiFi 网络执行"移除/取消保存"操作时不能正常移除——只要该网络当前不是连接状态就会失败。

## 根因分析
`WlanDialogFragment` 确认移除回调里调用的是无参版 `mWxWifiManagerI.forgetWifi()`。`WxWifiManagerI.forgetWifi()`（Hardwarelibs）的实现是 `int netId = this.getWifiInfo().getNetworkId()`，即取**当前已连接网络**的 networkId；目标网络未连接时 `getWifiInfo()` 返回的 networkId 为 -1，方法直接走 `if (netId == -1) return false` 分支并打日志 "Forget wifi failed: netId = -1"。也就是说无参版天生只能"忘记当前连接的网络"，对列表里其他记忆网络必然失效（缺陷库记"获取网络id问题"）。而 `forgetWifi(int networkId)` 重载本就支持按任意 id 移除，只是 UI 层没用上。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanDialogFragment.kt（1 文件 +1/-1）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanDialogFragment.kt
@@ 移除确认弹窗 Callback.confirm(content)
                                     lifecycleScope.launch(Dispatchers.IO) {// NOSONAR
-                                        mWxWifiManagerI.forgetWifi()
+                                        mWxWifiManagerI.forgetWifi(it.networkId)
                                     }
```

## 为什么能修复
改为把用户点击的列表项 `it.networkId` 传给 `forgetWifi(int)` 重载，该方法直接 `gxrWifiAdapter.forget(networkId, ...)`（仅过滤 `AccessPoint.INVALID_NETWORK_ID`），不再依赖"当前连接"上下文，未连接的记忆网络即可正常移除。一行改动、无副作用；顺带说明硬件库的无参 `forgetWifi()` 语义应视为"忘记当前网络"，其他调用方（如 ConnectViewModel）若也用于列表移除，存在同样隐患。

## 复盘与经验
- 封装层提供同名多义重载（无参=当前连接、有参=指定网络）时，调用方极易拿错；无参版在语义上应改名（如 forgetCurrentWifi）或直接废弃。
- "对 A 对象操作却读了全局当前态"是典型作用域错位 bug：网络 id、设备地址这类定位信息必须从被操作的数据项上取。
- 失败日志（"Forget wifi failed: netId = -1"）已经写明根因，此 bug 属于"日志能自证"的修复——遇到操作静默失败先查失败分支日志。
