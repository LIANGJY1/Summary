# SIR-6629 · 连接 CarLink 后点击应用列表卡顿约 3 秒
- **提交**：`ac3d9bd3` | 2026-08-31 | caohongliang | Launcher | bugfix（cherry-pick 自 6ed64be0）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
手机通过 CarLink 连接后，点击 Dock 栏"应用列表"图标，页面冻结约 3 秒才显示应用列表。

## 根因分析
CarLink 的应用详情（图标/名称/可用性）来自跨进程的同步 Binder 调用 `DeviceConnectManager.getCarLinkAppList()`，逐个查询应用详情耗时秒级。两处调用都发生在主线程：`CarConnectFragment` 连接类型为 3（CarLink）时直接 `onDeviceAppListLoaded(..., getCarLinkAppList())` 同步取数；`DeviceConnectManager.updateCarLinkAppList()` 更离谱——用 `ThreadUtils.runOnUiThread { ... getCarLinkAppList() ... }` 把同步 Binder 调用整个包进了主线程。主线程被 Binder 阻塞期间无法渲染首帧，点击后冻 3 秒，正是单据"Carlink获取应用详情是同步调用，比较耗时，会卡线程"。

## 关键代码修改
改动文件：DeviceConnectManager.kt、CarConnectFragment.kt
```diff
--- a/.../launcher/control/DeviceConnectManager.kt
     private fun updateCarLinkAppList() {
-        ThreadUtils.runOnUiThread {
-            synchronized(mListener) {
-                val appInfoList = getCarLinkAppList()
-                for (callback in mListener) {
-                    callback.onDeviceAppListLoaded(CARLINK, appInfoList)
-                }
-            }
-        }
+        // CarLink 应用详情是同步 Binder 调用，后台查询完成后再通知主线程更新界面。
+        ThreadUtils.getIoPool().execute {
+            val appInfoList = getCarLinkAppList()
+            ThreadUtils.runOnUiThread {
+                synchronized(mListener) {
+                    for (callback in mListener) {
+                        callback.onDeviceAppListLoaded(CARLINK, appInfoList)
+                    }
+                }
+            }
+        }
     }
```
```diff
--- a/.../function/applist/CarConnectFragment.kt
             3 -> {
                 isCarLinkConn = true
-                onDeviceAppListLoaded("", DeviceConnectManager.getInstance().getCarLinkAppList())
+                val cachedAppInfoList = deviceConnectManager.getCachedCarLinkAppList()
+                mBinding.layoutTopCard.visibility = View.GONE
+                mBinding.recyclerViewCarConn.visibility = View.VISIBLE
+                if (cachedAppInfoList.isNotEmpty()) {
+                    mAdapter?.setArrayData(cachedAppInfoList, "cachedCarLinkAppList")
+                }
+                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
+                    val appInfoList = deviceConnectManager.getCarLinkAppList()
+                    withContext(Dispatchers.Main) {
+                        onDeviceAppListLoaded("", appInfoList)
+                    }
+                }
             }
```
配套新增 `@Volatile mCachedCarLinkAppList` 缓存（连接断开清空、仅查询成功后更新）与 `getCachedCarLinkAppList()`。

## 为什么能修复
取数迁到 IO 线程（协程 Dispatchers.IO / IoPool），主线程只做"展示缓存 → 收到新数据后刷新 UI"，首帧不再被 Binder 阻塞，卡顿消除；缓存让二次进入即时呈现，后台刷新保证数据新鲜；连接态先隐藏引导页避免异步期间闪现。隐患：缓存与异步刷新并存时可能出现"先旧后新"的两次渲染；`getCarLinkAppList` 非线程安全的话多入口并发查询需确认 Binder 代理可复用——当前以 @Volatile + 只在成功后写缓存控制风险。

## 复盘与经验
- "runOnUiThread { 慢调用 }"是反模式：包进主线程块不会让慢调用变快，只会把耗时摊到帧渲染上；主线程只该做 UI 赋值。
- 跨进程同步调用（Binder/AIDL）一律按网络请求对待：后台线程发起、缓存兜底、回调切主线程刷新。
- 性能类缺陷（卡顿/ANR）验证标准是首帧时间，修复后应实测冷进/热进列表的打开耗时，而非仅看功能正确。
