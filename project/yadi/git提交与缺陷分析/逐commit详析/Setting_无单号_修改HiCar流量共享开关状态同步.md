# 无单号 · 修改 HiCar 流量共享开关状态同步

- **提交**：`d3e2a811` | 2026-07-24 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
修正 HiCar 流量共享"正常停止"被当成"共享失败"上报的问题：`onShareNetworkStop` 回调改回状态 2（正常关闭），并给共享开关的"用户点击"标志加 200ms 延迟恢复，防止程序性回写误触发回调。

## 实现结构
2 个文件（+5/-1）：
- `init/DeviceConnectManager.kt`：HiCar `onShareNetworkStop` 中 `setShareNetWork(0)` 改回 `setShareNetWork(2)`——`13ba9d1d` 曾把它从 2 改成 0，导致 setShareNetWork 的 state==0 分支弹"流量共享失败"toast 并按失败态刷新 UI，正常停止（如 HiCar 断开自动停共享）被误报；
- `ui/fragment/diologfragment/BluetoothFragment.kt`：`lazyLoadData` 里增加协程 `delay(200ms)` 后 `SIsFromUserClick = true`，即页面数据装载引起的开关回写期间保持"非用户点击"状态，200ms 后才恢复用户点击标记。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
         override fun onShareNetworkStop() {
             LogUtils.d(TAG, "HiCar onShareNetworkStop:")
             if (!BluetoothUtil.SIsStartNetWorkShare) {
-                setShareNetWork(0)
+                setShareNetWork(2)
             }
             BluetoothUtil.SIsStartNetWorkShare = false
         }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
         val list = mutableListOf<MultiBluetoothDevice>()
         BluetoothUtil.handlePairData(mPhonePairedDevices)
         setTrafficSharingView()
+        lifecycleScope.launch {
+            delay(200.milliseconds)
+            SIsFromUserClick = true
+        }
```
实现讲解：状态码语义为 0=失败、1=开启、2=关闭，`13ba9d1d` 把 stop 上报为 0 是为了驱动失败 toast，但 stop 事件同时会被"用户主动关闭之外的正常停止"触发，形成误报；本提交回归 2，失败提示只保留在真实失败回调。`SIsFromUserClick` 的延迟恢复是给"回显覆盖用户操作"窗口兜底。

## 复盘与要点
- 这是 `13ba9d1d` 引入回归后的二次修正（2→0→2），说明共享状态码的语义（0/1/2）没有集中定义，靠各回调各自传值；建议用枚举/常量 + 单一映射函数管理。
- 200ms 魔法延时的防回环是"经验型补丁"：能解当前问题，但设备慢时窗口可能不够、快时多余，更稳的做法是回写前比对目标值或用"期望值匹配"过滤回调。
