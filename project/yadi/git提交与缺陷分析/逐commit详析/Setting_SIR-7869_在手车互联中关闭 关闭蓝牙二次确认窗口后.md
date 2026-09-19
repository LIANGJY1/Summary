# SIR-7869 · 关蓝牙二次确认弹窗关闭后"当前无可连接设备"文案闪现

- **提交**：`d3240c0c` | 2026-09-09 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设

## 问题
在手车互联页面点关闭蓝牙、弹出二次确认窗口并确认关闭后，界面上会闪一下"当前无可连接设备"的文言。

## 根因分析
`BluetoothFragment`（DialogBluetoothChildBinding）对无设备文案 `tvNoConnect` 的可见性刷新逻辑有缺陷：初始化时无条件按 `mPhonePairedDevices.isEmpty()` 把文案设为 `View.VISIBLE`，设备列表刷新处也用 `if (isHasCarPhone) GONE else VISIBLE` 的二元写法，只要列表为空就强制显示。关闭蓝牙过程中设备列表被清空、`mWxBtManager` 状态回刷 UI，两处逻辑都会在"蓝牙正在关闭/已关闭"这个中间态把文案点亮，造成闪现；而按需求语义，蓝牙关闭后整个页面处于无服务状态，本就不应显示"无可连接设备"。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt、application/Setting/src/main/res/layout/dialog_bluetooth_child.xml（2 文件 +7/-3）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
@@ 初始化监听处
-                mBinding.tvNoConnect.visibility =
-                    if (mPhonePairedDevices.isEmpty()) View.VISIBLE else View.GONE
+                if (mWxBtManager.isEnable&&mPhonePairedDevices.isEmpty()){
+                    mBinding.tvNoConnect.visibility = View.VISIBLE
+                }
@@ 设备列表刷新处
-                mBinding.tvNoConnect.visibility = if (isHasCarPhone) View.GONE else View.VISIBLE
+                if(isHasCarPhone){
+                    mBinding.tvNoConnect.visibility=View.GONE
+                }
--- application/Setting/src/main/res/layout/dialog_bluetooth_child.xml
@@ tvNoConnect
                 android:text="@string/bluetooth_no_connect"
+                android:visibility="gone"
```

## 为什么能修复
布局默认值改为 gone 后，文案只有"蓝牙已开启且配对列表为空"这一条路径能将其置 VISIBLE，其他任何刷新（含关闭蓝牙的中间态回调）都只会把它藏起来或保持隐藏，从机制上消除了闪现。副作用：`else if (mPhonePairedDevices.isNotEmpty() || ...)` 分支仍保留 GONE 兜底，正常蓝牙开启下的空列表提示不受影响；若需求日后要求"蓝牙开但无设备"之外的场景也提示，需要重新补显式分支。

## 复盘与经验
- 可见性二元写法 `if (x) GONE else VISIBLE` 会把"中间态"误判为应显示态；对状态文案应默认隐藏、只在明确条件满足时点亮（default-gone + 单向显示）。
- 布局 XML 里给瞬态提示文案设置默认 visibility，比依赖代码首帧赋值更稳，可避免初始化时序导致的闪现。
- 涉及开关类操作的 UI 刷新，要把"开关切换的中间态"纳入测试用例（关闭中、已关闭、回调乱序），低概率闪现多半来自这些窗口期。
