# SIR-7390 · 耳机蓝牙界面点击刷新（搜索）无响应/直接结束
- **提交**：`5c9b2529` | 2026-09-07 | caohongliang | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设（根因：自动回连执行中，终止其他操作 → 取消自动回连）

## 问题
耳机蓝牙连接界面，点击刷新（搜索）按钮后搜索直接结束、无任何结果，表现为"点了没反应"。

## 根因分析
蓝牙协议栈（AnW SDK）在开机/进入页面后执行耳机自动回连；回连执行期间协议侧会拒绝/终止其他操作——此时发起 `AnWBT_StartInquiry` 搜索会立刻被终结，UI 层搜索按钮看似无响应。原代码 `BtAnwManager.operationBluetooth()` 里 `TYPE_START_INQUIRY`/`TYPE_STOP_INQUIRY` 直接调 `mBtAdapter.AnWBT_StartInquiry(10, 60, 0)` / `AnWBT_StopInquiry()`，完全没有处理"自动回连占用中"这一冲突场景，属于典型的并发操作时序缺陷。修复方案是：在执行任何蓝牙操作前先通过底层新增的 `AnWBT_Set_Auto_Connect_Interrupt_Flag()` 打断自动回连，再延迟 150ms 执行真正的搜索启停，给协议栈留出退出回连状态的时间。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt、component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/BtAdapter.java、component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
```diff
--- component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
@@ -189,15 +190,22 @@
+            case TYPE_AUTO_CONNECT_INTERRUPT:
+                mOperationState = OperationState.AUTO_CONNECT_INTERRUPT;
+                mBtAdapter.AnWBT_Set_Auto_Connect_Interrupt_Flag();
+                break;
             case TYPE_START_INQUIRY:
                 mOperationState = OperationState.SCANNING;
-                mBtAdapter.AnWBT_StartInquiry(10, 60, 0);
+                mBtAdapter.AnWBT_Set_Auto_Connect_Interrupt_Flag();
+                ThreadUtils.runOnUiThread(() -> mBtAdapter.AnWBT_StartInquiry(10, 60, 0), 150);
                 break;
             case TYPE_STOP_INQUIRY:
                 mOperationState = OperationState.IDLE;
-                mBtAdapter.AnWBT_StopInquiry();
+                mBtAdapter.AnWBT_Set_Auto_Connect_Interrupt_Flag();
+                ThreadUtils.runOnUiThread(() -> mBtAdapter.AnWBT_StopInquiry(), 150);
                 break;
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
@@ -179,17 +180,20 @@
     private fun handleDeviceItemClick(device: DeviceBean) {
-        if (device.isBonded) { ... }
+        BtAnwManager.getInstance().operationBluetooth(OperationType.TYPE_AUTO_CONNECT_INTERRUPT)
+        runOnUiThread({ ... 原连接/配对逻辑 ... }, 150)
     }
```
（`BtAdapter.java` 新增 AIDL 透传方法 `AnWBT_Set_Auto_Connect_Interrupt_Flag()`；`OperationState`/`OperationType` 枚举新增 `AUTO_CONNECT_INTERRUPT`/`TYPE_AUTO_CONNECT_INTERRUPT`。）

## 为什么能修复
打中断标志让协议栈退出自动回连状态，150ms 延迟保证打再生效后再启动 inquiry，搜索不再被回连流程"秒杀"；设备点击路径同样先打断回连再连接，避免同类冲突。隐患：所有操作引入固定 150ms 延迟属于经验值而非事件驱动——若回连退出耗时超过 150ms，问题仍可能低概率复现（与本单"低概率"频次特征吻合）；更优做法是等待协议栈回连结束的回调通知。

## 复盘经验
- 蓝牙/底层硬件操作存在互斥状态机（回连、扫描、配对互斥），UI 层发起操作前必须先处理状态冲突，而不是直接下发指令。
- "打中断标志 + 延迟执行"是工程上的快速止血手段，但要意识到固定延迟是竞态窗口，能拿到底层状态回调时应改为事件驱动。
- 底层新增能力（AnWBT_Set_Auto_Connect_Interrupt_Flag）需要 SDK/服务端配合时，应用层要确认目标版本已包含该 AIDL 方法。
