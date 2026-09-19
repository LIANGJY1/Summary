# SIR-7883 · 手机侧发起配对时车机不弹配对码（副蓝牙与主蓝牙同名）

- **提交**：`ebfb6abc` | 2026-09-11 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
手机侧主动向车机发起蓝牙配对连接请求时，手机正常弹出配对码，车机端却不弹配对码确认框。

## 根因分析
车机有两个蓝牙通道：主蓝牙（`BluetoothUtil.mWxBtManager`，手机互联）与副蓝牙（`BtAnwManager`，耳机/通话通道，UI 在 `BluetoothAnwFragment`）。副蓝牙的本机名 `setLocalDevName(deviceName)` 直接取自与主蓝牙相同的 `deviceName`，两个通道广播**完全相同的设备名**（提交 [why]：耳机蓝牙名称和本机名称一样）。手机侧发起配对时，车机端按设备名匹配/去重远端设备，同名导致配对请求被归并到已有"同名设备"上，配对码确认弹窗（PairDialogActivity 链路）的触发条件不成立，车机不弹框（缺陷库记"ui 显示错误/按需求文档开发"）。

## 关键代码修改
改动文件：init/SettingVehicleService.kt、ui/fragment/ConnectFragment.kt、ui/fragment/diologfragment/BluetoothAnwFragment.kt、utils/BluetoothUtil.kt（4 文件 +12/-5）
```diff
--- application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
+        // 耳机蓝牙（副蓝牙）本机名称后缀，用于与手机蓝牙名称区分
+        const val HEADSET_NAME_SUFFIX = "_Headset"
--- application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
@@ 设备名变化处理
             if (deviceName.isNotEmpty()) {
-                BtAnwManager.getInstance().setLocalDevName(deviceName)
+                //耳机蓝牙名称在本机名称后追加后缀，与手机蓝牙区分
+                BtAnwManager.getInstance()
+                    .setLocalDevName(deviceName + BluetoothUtil.HEADSET_NAME_SUFFIX)
             }
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/ConnectFragment.kt
@@ 修改蓝牙名称入口
-        BtAnwManager.getInstance().setLocalDevName(name)
+        BtAnwManager.getInstance().setLocalDevName(name + BluetoothUtil.HEADSET_NAME_SUFFIX)
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
@@ 头部设备名显示
-        mBindingHeader.tvDeviceName.text = DeviceUtils.getDeviceName()
+        mBindingHeader.tvDeviceName.text =
+            DeviceUtils.getDeviceName() + BluetoothUtil.HEADSET_NAME_SUFFIX
```

## 为什么能修复
所有副蓝牙改名入口统一追加 `_Headset` 后缀后，两个蓝牙通道的本机名不再冲突，手机发起配对时按名匹配不再歧义，车机端配对码弹窗链路正常触发；副蓝牙设置页显示同步加后缀，用户看到的名字与广播名一致。风险点：`ConnectFragment` 之外若还有修改主蓝牙名的路径未同步副蓝牙，两者会再次漂移；且后缀方案使"同名"判重从根上不可靠的假设长期存在，后续可考虑按 MAC 地址判重替代。

## 复盘与经验
- 双蓝牙通道（互联+通话）架构下，本机名冲突会让"按名匹配"的所有逻辑（配对、去重、图标选择）全部失真，通道命名必须在架构层保证唯一。
- 状态/名称类常量（HEADSET_NAME_SUFFIX）应集中定义并让所有写入点引用，本提交 4 处修改正是"多个入口写同一状态"的收敛案例。
- 蓝牙配对类问题排查要区分"弹窗触发条件"与"弹窗 UI"：先确认 stack 层配对请求是否到达、被哪层吞掉，再查对话框显示逻辑。
