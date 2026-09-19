# SIR-5749 · 未连接手车互联时连 CarPlay 误弹"断开 HiCar 切换"弹窗

- **提交**：`8a1573bf` | 2026-08-10 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 手车互联（rc：变量未置空 / sol：变量置空）

## 问题
当前没有任何手车互联连接时，连接 CarPlay 却弹出"是否断开已连接的 HUAWEI HiCar，并切换至新设备连接"的确认弹窗——凭空出现一个并不存在的"已连接 HiCar"。

## 根因分析
弹窗逻辑（`BluetoothUtil.showConnectHintDialog`）依据静态变量 `SCurrentThirdDevice`（当前第三方互联设备，见 `7a84e55b` 的分析）判断新旧连接是否同一设备：非空且设备不同就弹切换确认。`DeviceConnectManager.handleDeviceDisconnect()` 在设备断开时只重置了 `mCurrentConnectType = 0`，**没有清空 `SCurrentThirdDevice`**。上一次 HiCar 会话断开后，该变量仍残留旧的 HiCar 设备对象；此时连接 CarPlay，弹窗逻辑看到"当前设备=旧 HiCar，新设备=CarPlay"，误判为设备切换，弹出荒谬的提示。低概率(10%~40%)是因为必须先有"连过 HiCar 又断开"的前置序列才复现。第二个问题在同一文件：`callback.invoke()` 之后缺少 `return`，同设备分支执行完回调后继续落入后续弹窗逻辑，存在多余弹窗路径。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt`、`application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt`
```diff
// application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
     private fun handleDeviceDisconnect(deviceType: String) {
         mCurrentConnectType = 0
+        SCurrentThirdDevice = null
         when (deviceType) {
             CARPLAY, CARLINK -> {
```
```diff
// application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
                             } else {
                                 callback.invoke()
+                                return
                             }
```

## 为什么能修复
断开事件发生时同步清空 `SCurrentThirdDevice`，使"未连接"状态下该变量必然为 null，CarPlay 连接进来时弹窗逻辑的 `SCurrentThirdDevice == null` 兜底判定为"同一设备/无需切换"，误弹窗消失；`callback.invoke()` 后补 `return` 截断了同设备路径继续执行弹窗逻辑的可能。与 `7a84e55b`（把变量登记得更准）形成配套：那次修复让"已连接"时变量可靠非空，本次修复让"已断开"时变量可靠为空——一个状态变量的赋值与清理必须成对设计。副作用：`SCurrentThirdDevice` 是跨文件静态变量，若存在绕过 `handleDeviceDisconnect` 的断开路径（如蓝牙级断链回调），仍可能残留。

## 复盘与经验
- 全局/静态"当前 X"变量是弹窗误判类 bug 的常见源头，赋值点之外必须逐一盘点清空点，断开/注销/超时路径都要置空。
- "凭空出现上个会话的名字"这类现象，第一反应就应怀疑残留状态，顺着提示文案里的实体（HiCar）反查它何时被记录、何时应被清除。
- 分支内执行完语义动作后显式 `return`（Kotlin 中提前返回）可避免隐式落入后续逻辑，多分支共用的工具函数尤应如此。
- 同一变量在相邻提交中反复出 bug（7a84e55b 与本提交），说明该状态缺少统一的生命周期Owner，建议收敛到 DeviceConnectManager 内部并以事件对外分发。
