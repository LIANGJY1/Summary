# SIR-8349 · 耳机已连接但设置页偶现不显示连接状态
- **提交**：`2e0755a5` | 2026-09-15 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 偶现-低于10% · 状态 关闭 · 域 车控车设

## 问题
前排蓝牙耳机已连接（声音调节页也能看到耳机声音），但副蓝牙设置界面的连接状态偶现不显示/不刷新。

## 根因分析
连接状态的处理入口挂错了层：`BluetoothAnwFragment` 的 intent 处理分支中，`BtAnwManager.getInstance().handleConnectState(originalIntent)` 位于某个 UI 相关的 else 分支里——只有界面在场（未被关闭）时才会执行。连接过程中若"连接中界面"被关闭（缺陷库 rc："连接中界面关闭，无法触发回调逻辑"），`handleConnectState` 不被调用，Manager 内部连接状态标志不更新；之后打开设置页读到的是陈旧标志，已连接耳机显示为未连接。同时 `setRefreshAdapter` 回调直接 `mAdapter.notifyDataSetChanged()`，Fragment 已 detach 时回调会崩溃或刷新无效，进一步放大"状态不准"的观感。另有角色切换流程中 `resetDeviceFlags` 在重连前就清了设备标志，干扰状态重建。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt；component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
```diff
--- a/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
+++ b/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
@@ -961,6 +961,9 @@
             updateSecondaryDeviceAvrcpAndVoice(intent, address);
             dispatchConnectStateChanged(state, name, address, profileId, intent);
+            if (state != BtAdapterMessage.CONNECT_STATE.STATE_CONNECT_FAILED) {
+                handleConnectState(intent);
+            }
         }
     }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
+++ b/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
@@         BtAnwManager.getInstance().setRefreshAdapter {
-            mAdapter.notifyDataSetChanged()
+            if (isAdded) {
+                mAdapter.notifyDataSetChanged()
+            }
         }
@@ (Fragment intent 处理中删除)
-        } else {
-            BtAnwManager.getInstance().handleConnectState(originalIntent)
         }
@@ (角色切换重连流程)
-                    resetDeviceFlags(resetDevice)
+//                    resetDeviceFlags(resetDevice)
```

## 为什么能修复
把 `handleConnectState` 从"Fragment 在场才走"的 UI 路径上移到 `BtAnwManager` 的连接状态广播处理尾部，紧跟 `dispatchConnectStateChanged` 之后无条件执行（仅排除 CONNECT_FAILED），界面是否关闭不再影响状态机的推进——连接完成时 Manager 标志必然刷新，之后再进设置页即读到正确状态。`isAdded` 守卫消除 detach 后刷新的崩溃/无效刷新；注释掉重连前的 `resetDeviceFlags` 避免标志被过早清掉。隐患：注释而非删除的代码需后续确认是否彻底废弃；UI 缺场时的刷新借 Adapter 回调补发，依赖 `setRefreshAdapter` 在 Fragment 重新 onCreate 时重新注册。

## 复盘与经验
- 设备/业务状态推进必须放在与界面生命周期无关的层（Manager/Service 的广播回调），UI 只做观察者；"界面关着就丢状态"是这类偶现显示 bug 的通用根因。
- Manager 回调 UI（Adapter 刷新）要用 `isAdded`/`isAttached` 守卫，异步蓝牙回调与 Fragment 生命周期天然错拍。
- 复现率低于 10% 的问题，从"回调注册位置/生命周期窗口"入手往往比反复操作复现更有效。
