# 无单号 · 车控主分支静态代码扫描（Setting 模块技术债清理）

- **提交**：`6322e182` | 2026-07-16 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
对车控主分支 Setting 模块做一轮静态代码扫描整改，消除扫描告警（无用/注释代码、冗余转换、过长方法等），不改变任何业务行为。

## 实现结构
25 个文件改动（+1151/-1121），几乎全部为等价重构：
- 删除整文件：`init/InitCallback.kt`（整文件本就被注释掉）、`receiver/EasyConnStatusReceiver.java`（亿连状态广播接收器已无人使用）；
- `init/DeviceConnectManager.kt`：删除注释代码与未使用局部变量，匿名 `object` 监听器改为 SAM 构造 `IHiCarFusionUiDisplayListener { ... }`，超大 `when` 块拆分为 `handleDeviceConnect/handleDeviceDisconnect` 等独立方法；
- `ui/adapter/BluetoothAdapter.kt`、`BluetoothAnwAdapter.kt`、`WlanAdapter.kt` 等：把 `convert` 里上百行的 `when(item.itemType)` 分支体抽成 `convertDivider/convertTitleMine/convertBluetoothItem` 等私有方法（降低圈复杂度/方法长度告警）；
- `NavItem.java`：`(Fragment) fragment` 冗余强转删除；若干 ViewModel/Fragment 为空 `if` 体加 `// NOSONAR` 或删除空分支。

数据流无变化：监听器仍向 `mListener` 回调列表广播，UI 绑定逻辑原样搬移。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
-    val mHiCarFusionUiDisplayListener = object : IHiCarFusionUiDisplayListener {
-        override fun onFusionUiDisplayChanged(type: Int, p1: String?) {
-            ...
-            synchronized(mListener) {
-                for (callback in mListener) {
-                    callback.onFusionUiChanged(type, p1)
-                }
-            }
-        }
-    }
+    val mHiCarFusionUiDisplayListener = IHiCarFusionUiDisplayListener { type, p1 ->
+        LogUtils.d(TAG, "onFusionUiDisplayChanged: $type    $p1")
+        synchronized(mListener) {
+            for (callback in mListener) {
+                callback.onFusionUiChanged(type, p1)
+            }
+        }
+    }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
-        when (deviceType) {
-            CARPLAY -> { ...mCurrentConnectType = 1 ... }
-            HICAR -> { ... }
-            CARLINK -> { ... }
-        }
+        val type = when (deviceType) {
+            CARPLAY -> 1
+            HICAR -> 2
+            CARLINK -> 3
+            else -> return
+        }
+        if (mCurrentConnectType != type && !isConnected) return
+        when {
+            isConnected -> handleDeviceConnect(type, deviceType)
+            else -> handleDeviceDisconnect(deviceType)
+        }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/bean/NavItem.java
     public Fragment getFragment() {
-        return (Fragment) fragment;
+        return fragment;
     }
```
实现讲解：三类典型扫描整改手法集中体现——删除死代码（含整文件注释代码）、SAM 化匿名内部类、长方法/高圈复杂度拆分。`setDeviceConnectStatus` 重构时把"类型字符串→int"映射提前并统一了 `mCurrentConnectType != type && !isConnected` 的早退判断，比原来每个分支重复一遍更不易漏。

## 复盘与要点
- 纯技术债清理提交，可作为"扫描整改"范例：先删死代码、再 SAM 化、再拆长方法，diff 巨大但零行为变更，便于 review（逐条对照即可）。
- 大量 `// NOSONAR` 出现说明有些空 `if` 是有意保留的占位逻辑，整改时选择压制告警而非删除，保留了"后续可能填逻辑"的语义，但也埋下理解成本。
- 遗留风险：adapter 拆分后 `holder` 生命周期仍绑定在 `convert` 调用链内，若后续在子方法里缓存 View 引用易引发复用错乱，需保持"现取现用"。
