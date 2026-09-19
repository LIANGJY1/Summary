# SIR-1003 · 副蓝牙开关打开后，开关点击失效

- **提交**：`508639bc` | 2026-06-26 | daizhecheng | Setting (+Hardwarelibs) | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
副蓝牙（ANW 蓝牙）开关打开后，再次点击开关无效，界面开关状态与真实蓝牙状态脱节。

## 根因分析
缺陷库根因为"开关状态数据同步问题"，与 diff 高度吻合：蓝牙开关状态被冗余保存在 UI 层静态字段 `Constant.BT_ON` 中，`BluetoothAnwFragment` 的 `initView()`/`onBtStateChanged()`/`setDeviceData()` 各处分别读写这个全局静态变量，而 `BtAnwManager` 内部并不持有该状态。任何一处漏更新（或 Fragment 重建后 `initView` 用过期值回写 `sw.isChecked`）都会造成"界面认为已开、底层认为已关"的错位，后续点击被状态守卫逻辑吞掉。此外并发层面隐患明显：`mPairedDevices`/`mAvailableDevices` 是普通 `ArrayList`，被 Binder 回调线程与 UI 线程同时读写；扫描状态无去重守卫，重复触发 `TYPE_START_INQUIRY` 也会让 UI 停在"扫描中"假态。

## 关键代码修改
改动文件：application/Setting/.../diologfragment/BluetoothAnwFragment.kt、application/Setting/.../adapter/BluetoothAnwAdapter.kt、component/Hardwarelibs/.../bean/DeviceBean.java、component/Hardwarelibs/.../bt/BtAdapter.java、component/Hardwarelibs/.../bt/anwBt/BtAnwManager.java、component/Hardwarelibs/.../bt/anwBt/IAnwBluetoothListener.java

```diff
--- application/Setting/.../diologfragment/BluetoothAnwFragment.kt
@@ initView
-        Constant.BT_ON = BtAnwManager.getInstance().isEnable
-        mBindingHeader.sw.isChecked = Constant.BT_ON
+        BtAnwManager.getInstance().setBtOn(BtAnwManager.getInstance().isEnable)
+        mBindingHeader.sw.isChecked = BtAnwManager.getInstance().isBtOn
```

```diff
--- component/Hardwarelibs/.../bt/anwBt/BtAnwManager.java
@@ 状态收敛到 Manager + 扫描状态机
-    public List<DeviceBean> mPairedDevices = new ArrayList<>();
-    public List<DeviceBean> mAvailableDevices = new ArrayList<>();
+    public List<DeviceBean> mPairedDevices = new CopyOnWriteArrayList<>();
+    public List<DeviceBean> mAvailableDevices = new CopyOnWriteArrayList<>();
+    public enum OperationState { IDLE, SCANNING, PAIRING, CONNECTING, DISCONNECTING, UNPAIRING }
+    private volatile OperationState mOperationState = OperationState.IDLE;
+    private boolean mBtOn = false;
+    public boolean isBtOn() { return mBtOn; }
+    public void setBtOn(boolean btOn) { mBtOn = btOn; }
     case TYPE_START_INQUIRY:
+        mOperationState = OperationState.SCANNING;
         mBtAdapter.AnWBT_StartInquiry(10, 60, 0);
```

```diff
--- application/Setting/.../diologfragment/BluetoothAnwFragment.kt
@@ 扫描点击去重 + Fragment 存活守卫
             override fun onScanningStateChanged(isScan: Boolean) {
-                logClick(...)
+                if (isScan && mIsScanInProgress) {
+                    log("$TAG-> scan click ignored: already scanning")
+                    return
+                }
+                mIsScanInProgress = isScan
     override fun onBtStateChanged(state: Int) {
+        if (!isAdded) return
@@
-            Constant.BT_ON = true
+            BtAnwManager.getInstance().setBtOn(true)
```

## 为什么能修复
核心思路是把"开关状态"的唯一事实源从 UI 静态变量 `Constant.BT_ON` 迁移进 `BtAnwManager.mBtOn`，所有读写都走同一入口，消除多处写副本导致的失步；`OperationState` 状态机 + `mIsScanInProgress` 让重复点击/扫描中断不再产生假状态。配套修复同样重要：设备列表改 `CopyOnWriteArrayList` 防并发修改、`DeviceBean` 按 `macAddress` 重写 equals/hashCode、卸配对立即从 `mPairedDevices` 移除、`TYPE_GET_PAIR` 刷新时用 oldDeviceMap 恢复 hfp/a2dp/role 标志位（否则状态被清零后点击连接会被 `connectProfile` 的"未完全断开"守卫跳过——这正是"点击失效"的直接表现之一）。副作用：`IAnwBluetoothListener` 全部方法改为 default 空实现，实现方漏写回调将静默无提示，属于可接受的取舍。

## 复盘与经验
- **单一事实源**：硬件/服务状态不应镜像到 UI 静态变量（`Constant.BT_ON`），应收敛到 Manager 并提供 `getCurrentState()` 快照，UI 只读不写。
- **刷新列表必须保留派生状态**：`TYPE_GET_PAIR` 重建 `mPairedDevices` 时若丢弃 hfp/a2dp/role 标志，后续所有依赖这些标志的守卫都会误判——"重新拉数据"要带"状态合并"语义。
- **回调线程与生命周期**：Binder 回调里加 `isAdded` 守卫、`onDestroy` 取消协程 Job、跨线程容器用 COW 列表，是 Fragment+后台服务组合的标准防御三件套。
- **异步等待要有超时兜底**：`mPendingRoleSwitchAddress` 增加 5 秒超时后仍执行连接，防止角色回调丢失导致点击永久无响应。
