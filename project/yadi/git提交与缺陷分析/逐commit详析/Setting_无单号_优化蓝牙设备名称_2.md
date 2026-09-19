# SRS_BT_LinkSetting_002 · 优化蓝牙设备名称

- **提交**：`e08e4e51` | 2026-08-27 | sgh | Setting | feature
- **关联单**：SRS_BT_LinkSetting_002（标题引用，无系统单号）

## 需求/目标
蓝牙设置页头部显示的设备名在底层名称为空时不再显示空白，回退为系统默认设备名。

## 实现结构
仅改动 `BluetoothFragment.kt` 头部初始化 1 处：读取 `mWxBtManager.name` 后先判空，空则取 `DeviceUtils.getDeviceName()` 作为展示兜底。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ -69,7 +69,8 @@
         mBindingHeader.sw.setTitle(getString(R.string.bluetooth))
-        mBindingHeader.tvDeviceName.text = mWxBtManager.name
+        val btName = mWxBtManager.name
+        mBindingHeader.tvDeviceName.text = if (btName.isNullOrEmpty()) DeviceUtils.getDeviceName() else btName
```

实现讲解：一行式兜底（elvis/三元）是 UI 展示外部数据的标准防御手法；先取出局部变量再判空，避免重复调用 manager 取值。改动极小，属界面细节优化。

## 复盘与要点
- 外部子系统（蓝牙协议栈）返回值永远可能为 null/空串，UI 展示前统一做默认值兜底，可避免"设置页显示空白"这类低级体验缺陷。
- 该兜底只修了展示层；若底层名称确实未初始化，更彻底的做法是在名称写入时机（初始化流程）保证非空，可对比同日 `8b3394fb`"优化设备默认名称初始化"——那是源头修法。
