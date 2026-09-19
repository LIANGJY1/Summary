# 无单号 · 蓝牙和热点设备名称设置（SRS_BT_LinkSetting_001）
- **提交**：`86d1cd04` | 2026-08-21 | sgh | Setting | 需求实现（标题标注 bugfix，实际为 SRS 条目功能开发）
- **缺陷库**：未关联单号（无缺陷记录）

## 问题
设置-连接页缺少"本机设备名称"查看与编辑能力，蓝牙名称、热点名称与系统设备名三者各自为政，不满足 SRS_BT_LinkSetting_001 对设备名称的要求。

## 根因分析
属功能缺失而非代码缺陷：连接页 `ConnectFragment` 原先没有设备名展示行与编辑入口，蓝牙/热点子页面也未回显当前名称。本提交按 SRS 条目一次性补齐：新增 `DeviceNameEditDialogFragment`（带 24 字节等价长度限制的 `DeviceNameInputFilter`、超限 toast+截断、空内容禁确认）、`HotspotPwdEditDialogFragment`，以及公共工具 `DeviceUtils.getDeviceName()`（读 `Settings.Global.DEVICE_NAME`）。确认修改后在 `ConnectFragment.setDeviceName(name)` 中三处同步：写回 `Settings.Global.DEVICE_NAME`、`BluetoothUtil.mWxBtManager.name`、热点名 GSetting。

## 关键代码修改
改动文件：ConnectFragment.kt、DeviceNameEditDialogFragment.kt（新增）、HotspotPwdEditDialogFragment.kt（新增）、DeviceUtils.kt（新增）、BluetoothFragment.kt、HotspotDialogFragment.kt 及 4 个 layout、中英 strings（共 12 文件，+616/-26）
```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/utils/DeviceUtils.kt（新增）
+object DeviceUtils {
+    fun getDeviceName(): String {
+        return getGSetting(Settings.Global.DEVICE_NAME)
+    }
+}
```
```diff
--- a/.../ui/fragment/ConnectFragment.kt
+    fun setDeviceName(name: String) {
+        //修改设备名称
+        setGSetting(Settings.Global.DEVICE_NAME, name)
+        //修改蓝牙名称
+        BluetoothUtil.mWxBtManager.name = name
+        //修改热点名称
+        setGSetting(HotspotDialogFragment.HOTSPOT_NAME, name)
+    }
```
`BluetoothFragment` 头部补 `mBindingHeader.tvDeviceName.text = mWxBtManager.name ?: DeviceUtils.getDeviceName()` 回显。注：热点改名后重启热点生效的逻辑 `restartHotspotWithNewName()` 已写好但整段注释未启用，热点名实际生效时机存疑。

## 为什么能修复（功能达成情况）
编辑弹窗 → 确认回调 → 三处名称同步写入，主链路闭环；字节级过滤保证蓝牙名称不超协议 248 字节/UTF-8 等价长度约束。遗留隐患：热点名称只写了 GSetting，生效需重启热点（代码已注释掉），切换可能要等下次热点开关；`DeviceUtils.kt` 文件末尾无换行符属小瑕疵。

## 复盘与经验
- 提交消息标注 `[bugfix]` 但 ids 为空、内容为纯需求开发，标签滥用会污染缺陷统计，建议按实际类型打标。
- "一处输入、三处生效"的设备名联动必须枚举所有消费点（系统设置、蓝牙栈、热点 SSID）并统一写入，漏一处就是下一个缺陷。
- 输入限制用 InputFilter + TextWatcher 双层（过滤进不来、漏网截断并提示）是文本约束类需求的稳妥范式。
