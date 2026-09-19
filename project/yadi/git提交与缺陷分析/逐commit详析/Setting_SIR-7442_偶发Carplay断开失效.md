# SIR-7442 · 偶发 CarPlay 断开失效
- **提交**：`93eb2f0f` | 2026-09-04 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 手车互联

## 问题
偶发场景下 CarPlay 无法断开（或断开后状态残留），操作断开开关无效。

## 根因分析
`DeviceConnectManager` 用单一 `mCurrentConnectType`（注释：1=CarPlay、2=HiCar、3=CarLink）记录当前互联类型，所有断开/切换逻辑都依据它路由。HiCar 应用服务连上时（`HiCarApp onServiceConnected`），旧代码无条件按 `mHiCarAppManager?.hiCarSessionStatus` 重算：等于 `SessionState.DEVICE_CONNECTED` 才置 2，否则置 0。问题在于该"会话状态"并不代表有真实设备通过 CarLink/HiCar 已连接，而置 0 会把已有的 CarPlay 状态（`mCurrentConnectType == 1`）覆盖抹掉——如果 HiCar 服务恰好在 CarPlay 已连接后才完成绑定（服务绑定时机不定，故"偶发"），当前连接类型被清零，后续断开 CarPlay 的请求按 0 类型路由，找不到 CarPlay 会话，断开失效。缺陷库"hicar初始化时，判断是否连接逻辑有误"与代码完全对应。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt`

```diff
--- application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
                 TAG, "HiCarApp onServiceConnected: ${mHiCarAppManager?.hiCarSessionStatus}"
             )
             mHiCarAppConnection = true
-            mCurrentConnectType =
-                if (mHiCarAppManager?.hiCarSessionStatus == HiCarConstants.SessionState.DEVICE_CONNECTED) 2 else 0
+            mHiCarDeviceListManager?.hiCarDeviceList?.forEach {
+                LogUtils.d(TAG, "CarLink Device onServiceConnected: $it")
+                if (it.deviceStatus == HiCarConstants.DeviceStatus.CONNECTED) {
+                    setDeviceConnectStatus(CARLINK, true)
+                    return@forEach
+                }
+            }
             mHiCarAppManager?.registerHiCarStateListener(mHiCarStateListener)
```

## 为什么能修复
新逻辑不再以"会话状态"粗粒度覆写 `mCurrentConnectType`，而是查询 HiCar 设备列表、只有存在 `DeviceStatus.CONNECTED` 的真实设备时才通过 `setDeviceConnectStatus(CARLINK, true)`（内部仅在类型不同才改写 `mCurrentConnectType = 3`）登记连接；没有真实设备时什么都不做，CarPlay 已建立的状态（1）得以保留，断开操作可正确路由，"偶发失效"随初始化时序差异被消除。隐患：如果 HiCar 服务晚于设备连接且设备列表在 onServiceConnected 时点尚未同步完成，仍可能漏登记，需要依赖后续 `mHiCarStateListener` 状态回调补齐。

## 复盘经验
- 单一"当前连接类型"字段被多个互联协议（CarPlay/HiCar/CarLink）的初始化回调共享写入时，任何一方在初始化时的粗粒度赋值（尤其无条件置 0）都可能抹掉他方状态；初始化代码只应"确认事实"，不应"重置全局"。
- 服务绑定（onServiceConnected）时机不确定，凡是在该回调里恢复状态的逻辑，都必须容忍"其他协议已先行连接"的情形——偶发互联 bug 大多长在时序交叉点上。
- 判断是否连接要用设备级状态（device list 里每台设备的 deviceStatus），而不是会话级/应用级状态，两者语义不同。
