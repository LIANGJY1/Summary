# SIR-6742 · 其他蓝牙设备关闭时仍显示"可配对设备"和"刷新"按钮
- **提交**：`bddd4d81` | 2026-09-01 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
连接设置 → 蓝牙（副蓝牙/其他蓝牙设备）开关关闭后，界面仍显示"可配对设备"分组标题与"刷新"入口，且空态文案不正确。

## 根因分析
`BluetoothAnwFragment.handleBluetoothData()` 组装列表时，"可用的设备"（`TITLE_AVAILABLE`）标题与 `mAvailableDevices` 的添加逻辑没有包裹在开关状态判断内：无论 `BtAnwManager.isBtOn` 与用户主动关闭标志 `mIsUserClosingBluetooth` 如何，标题项都会加入列表；只有可用设备条目本身被 `if (isBtOn && !mIsUserClosingBluetooth)` 保护，但该判断位置在标题项之后、且原先不包含标题与 `tvNoConnect` 可见性——副蓝牙关闭后"可配对设备"标题照常渲染。`tvNoConnect` 的可见性原来放在函数末尾统一按 `isDeviceEmpty` 计算，关闭态下设备列表为空反而会把它隐藏，导致既显示标题又没有空态提示。

## 关键代码修改
改动文件：`application/Setting/.../ui/fragment/diologfragment/BluetoothAnwFragment.kt`
```diff
--- .../diologfragment/BluetoothAnwFragment.kt @@ handleBluetoothData()
-        }
-        list.add(MultiBluetoothAnwDevice(MultiBluetoothAnwDeviceType.TITLE_AVAILABLE.type, null))
-        )
-        if (BtAnwManager.getInstance().isBtOn && !mIsUserClosingBluetooth) {
+            list.add(
+                MultiBluetoothAnwDevice(MultiBluetoothAnwDeviceType.TITLE_AVAILABLE.type, null)
+            )
             BtAnwManager.getInstance().mAvailableDevices.forEach {
                 list.add(MultiBluetoothAnwDevice(...))
             }
+            mBinding.tvNoConnect.visibility =
+                if (BtAnwManager.getInstance().isDeviceEmpty) View.VISIBLE else View.GONE
+        } else {
+            mBinding.tvNoConnect.visibility = View.VISIBLE
         }
```
（重构后 `TITLE_AVAILABLE` 标题与可用设备列表、空态可见性全部进入 `isBtOn && !mIsUserClosingBluetooth` 分支；关闭时直接显示 `tvNoConnect` 空态。）

## 为什么能修复
把"可配对设备"标题、可用设备条目、空态提示三者绑定到同一个开关状态条件：副蓝牙关闭（或用户主动关闭）时不再向列表插入 `TITLE_AVAILABLE`，"刷新"按钮区随之不渲染，`tvNoConnect` 固定可见给出关闭态反馈。逻辑收敛后也不再出现"空列表却隐藏空态文案"的矛盾。风险小，仅需回归副蓝牙开/关、扫描中、用户主动关闭三种组合。

## 复盘与经验
- 列表组装逻辑里"分组标题"必须与组内容同条件渲染，否则出现"有标题无内容"的破碎 UI。
- 条件判断的包裹范围要覆盖标题、内容、空态三件套，只保护内容是常见的半截防护。
- 副蓝牙这类双模蓝牙（主/副）页面，`isBtOn` 与 `mIsUserClosingBluetooth` 双状态叠加，测试矩阵要交叉覆盖。
