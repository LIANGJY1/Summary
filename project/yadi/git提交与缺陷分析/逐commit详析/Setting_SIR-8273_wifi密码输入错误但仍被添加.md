# SIR-8273 · WiFi 密码错误仍被添加且无法移除

- **提交**：`7088c252` | 2026-09-11 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 偶现-低于10% · 状态 关闭 · 域 车控车设
- 说明：提交信息中 [what] 段误粘贴了 SIR-8119 的文案（开机音乐），实际内容以标题与 diff 为准。

## 问题
WiFi 密码输错后网络仍被添加进列表，状态显示"请检查密码，然后重试"，点击仅提示刷新，没有任何重新输入密码的交互，该条目也无法移除。

## 根因分析
`WlanCustomEditDialogFragment` 中处理认证失败的逻辑是：查到该网络的 `config` 后执行 `mWxWifiManagerI.forgetWifi(networkId)` 删除残留配置。但 `config` 的查询存在竞态：偶现场景下认证失败回调先于系统把该配置写入 `configuredNetworks` 到达，此时按 networkId 查不到 config，原代码 `config?.apply {...}` 没有 else 分支，直接静默跳过删除——错误配置残留在已保存列表里，成为一条"密码错、连不上、删不掉"的死条目。缺陷库根因"wifi连接状态逻辑问题"、提交信息"连接错误没有移除"均指向这一删除兜底缺失。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanCustomEditDialogFragment.kt`
```diff
             config?.apply {
                 mWxWifiManagerI.forgetWifi(networkId)
+            } ?: run {
+                // 偶现：认证失败可能早于系统把该配置写入 configuredNetworks，
+                // 此刻查不到会直接跳过删除导致残留，稍后按 SSID 再兜底删一次
+                MyApplication.Companion.getHandler().postDelayed({
+                    mWxWifiManagerI.removeConfiguration(tSsid)
+                }, 500)
             }
             LogUtils.e(TAG, "forgetNetwork:$tSsid")
```

## 为什么能修复
为"查不到 config"补上兜底分支：延迟 500ms（给系统完成 configuredNetworks 写入留出窗口）后改用 `removeConfiguration(tSsid)` 按 SSID 删除，覆盖了"失败回调早于配置落库"的时序场景，残留条目得以清除，用户可重新发起连接并输入密码。隐患：500ms 是经验值，系统写入更慢时仍可能漏删；按 SSID 删除在同名多网络场景可能多删，但车机 WiFi 场景影响可忽略。

## 复盘与经验
- `config?.apply {}` 这种可选链+无 else 的写法会把"查不到"静默吞掉，删除/清理类逻辑必须显式处理 null 分支。
- 系统 WiFi 事件顺序不保证（认证失败回调 vs 配置落库），对偶现竞态用"延迟重试 + 换一种匹配键（SSID）"是务实的兜底。
- 提交信息模板复制粘贴会串单（本例 [what] 是别的单号文案），影响后续复盘检索，值得在流程上卡一道。
