# 无单号 · 优化设备配对后角色设置逻辑

- **提交**：`464e833a` | 2026-08-21 | dufan | Setting | feature（实质为缺陷修复性优化）
- **关联单**：无

## 问题（diff 定性）
蓝牙免提（BT Anw）设备配对成功后的角色分配逻辑会对已设置过角色的设备重复执行角色写入，导致角色被重置。本提交在角色设置入口加"已配对设备直接跳过"的防重入判断。

## 根因分析
`BluetoothAnwFragment` 的角色设置方法收到回调时未区分设备是否已在 `BtAnwManager.mPairedDevices` 配对列表中，一律按 `isHasFront/isHasBack` 缺席情况执行 `setRole` 写入；配对回调可能多次触达，第二次到达时会把已分配的角色再次覆盖。

## 关键代码修改
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
@@     device: DeviceBean?, address: String, isHasFront: Boolean, isHasBack: Boolean
     ) {
         device?.let {
+            BtAnwManager.getInstance().mPairedDevices.forEach {
+                if (it.getMacAddress() == address) {
+                    return
+                }
+            }
             if (!isHasFront) {
                 it.role = 1
                 BtAnwManager.getInstance().setRole(it.macAddress, 1)
```
## 为什么能修复
以 MAC 地址比对已配对列表，命中的设备直接 `return`，角色只会在"首次配对"这一时机分配一次；后续重复回调不再触碰 `setRole`，角色状态保持稳定。

## 复盘与经验
- "配对回调可能重放"是蓝牙模块常见坑：凡是副作用型写入（setRole/改名/授权）都应带幂等保护或状态门控。
- 5 行修复标为 feature 属于定性偏差，建议提交信息按 fix 类模板写明复现路径，便于回归测试锁定场景（双设备先后配对）。
- 线性遍历已配对列表做 MAC 匹配，设备数少时无碍；若列表变大数据量可换 Set/Map 索引。
