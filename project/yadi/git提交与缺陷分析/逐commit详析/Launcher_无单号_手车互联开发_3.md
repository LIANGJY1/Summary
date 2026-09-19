# 无单号 · 手车互联开发：修改CarPlay连接（Launcher 侧无线 CarPlay 连接与状态机）

- **提交**：`ad2d6de5` | 2026-06-27 | dufan | Launcher | 类型：手车互联开发提交（[bugfix] 标签、影响等级 D、测试范围"无"，实为功能补全）
- **缺陷库**：未关联单号

## 问题
无对应缺陷单。Launcher 侧 CarPlay 连接缺少无线连接的前置校验与会话状态机：直接 `connectCarPlay` 不检查无线 CarPlay 可用性；CarPlay 会话回调用二值化 `status == SESSION_STATUS_ACTIVATED` 广播，无 DEACTIVATED 明确分支、不维护 `mCurrentConnectType`；HiCar 融合 UI 的 CONNECT_FAIL 事件也没有失败页跳转。

## 根因分析
以 diff 实际内容为准，本提交与 Setting 侧 `80139723` 配套，把 CarPlay 交互链路补完整：其一，`DeviceConnectManager.connectCarPlay(activity, address)` 新增封装——先 `checkWirelessCarPlayAvailability`，可用才 `connectCarPlay(ConnectRequest)`，不可用则在 UI 线程弹 `carplay_connect_fail` Toast；`CarConnectFragment` 原地私有实现（直接摸 `mCarPlayDeviceManager`）删除并改调新入口。其二，CarPlay 会话回调改为 `when` 显式处理 `SESSION_STATUS_ACTIVATED`（`mCurrentConnectType=1`）与 `SESSION_STATUS_DEACTIVATED`（=0），并写 `SPUtils` productName。其三，`onProjectionRequested` 对无线类型（`CONNECTION_TYPE_WIRELESS`）自动发起连接；`onFusionUiDisplayChanged` 增加 `HiCarConstants.FusionUiType.CONNECT_FAIL` → 带 `is_connect_fail` extra 拉起 `LinkActivity` 失败页。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt、application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt、application/Launcher/src/main/java/com/yadea/launcher/function/link/LinkActivity.kt

```diff
--- application/Launcher/.../control/DeviceConnectManager.kt
@@ 无线 CarPlay 可用性校验后连接，失败提示
+    fun connectCarPlay(activity: Activity?, address: String) {
+        getCarPlayDeviceManager()?.apply {
+            checkWirelessCarPlayAvailability(address) { p0, p1 ->
+                if (p1) {
+                    connectCarPlay(ConnectRequest().apply { deviceId = address })
+                } else {
+                    activity?.runOnUiThread {
+                        ToastUtils.showMsgToast(activity, getString(R.string.carplay_connect_fail))
+                    }
+                }
+            }
+        }
+    }
```

```diff
--- application/Launcher/.../control/DeviceConnectManager.kt
@@ CarPlay 会话状态显式双态
-                    for (callback in mListener) {
-                        callback.onDeviceStatusChanged(CARPLAY,
-                            status == CarPlayConstants.SessionStatus.SESSION_STATUS_ACTIVATED, mProductName)
-                    }
+                    when (status) {
+                        CarPlayConstants.SessionStatus.SESSION_STATUS_ACTIVATED -> {
+                            mCurrentConnectType = 1
+                            SPUtils.setParam("productName", mProductName)
+                            for (callback in mListener) callback.onDeviceStatusChanged(CARPLAY, true, mProductName)
+                        }
+                        CarPlayConstants.SessionStatus.SESSION_STATUS_DEACTIVATED -> {
+                            mCurrentConnectType = 0
+                            ...广播 false...
+                        }
+                    }
```

## 为什么能修复
可用性前置校验把"连不上也无反馈"变成明确的失败 Toast，交互闭环；`when` 双态让 `mCurrentConnectType` 与持久化的连接类型只随确定事件变化，杜绝中间态误写（与 HiCar 修复 `407c86d1` 同一手法）；CONNECT_FAIL 拉起失败页补上了融合连接失败的可视出口。隐患：`connectCarPlay(activity, address)` 持有 Activity 引用并在回调里 `runOnUiThread`，若页面已销毁存在泄漏/崩溃窗口；`mProductName = ""` 在 ACTIVATED 分支也清空产品名略可疑（CarPlay 无产品名可取，属权宜）。

## 复盘与经验
- **连接前先校验能力**（wireless availability）：把协议层失败前置为用户可理解的提示，是车机多协议连接的通用要求。
- **回调持 Activity 引用要弱化**：Manager 长生命周期对象回调里直接操作 Activity 是典型泄漏点，宜改为回调接口由页面自行注册/注销。
- **多协议状态机统一写法**：HiCar、CarLink、CarPlay 三个 `onSessionStateChanged` 全部收敛为 `when + 显式状态 + mCurrentConnectType 赋值`，本系列提交已形成可复制的模板。
