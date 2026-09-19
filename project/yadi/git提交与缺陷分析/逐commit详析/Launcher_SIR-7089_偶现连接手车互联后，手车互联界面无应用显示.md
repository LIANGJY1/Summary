# SIR-7089 · 偶现连接手车互联后手车互联界面无应用显示
- **提交**：`764f03a5` | 2026-09-01 | dufan | Launcher | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 能量中心（题头为手车互联）

## 问题
连接手车互联（CarLink）后偶现融合桌面/手车互联界面应用列表空白。

## 根因分析
`DeviceConnectManager.kt` 中应用列表来自底层 AIDL 回调 `onAppListLoaded(listState, totalPacket, currentPacket, appInfos)`，是分包推送的。原实现里该回调只打日志、不组装数据；真正的刷新走 `updateCarLinkAppList()` → `getCarLinkAppList()`（同步 Binder 查询）。当底层响应超时/查询失败时，`getCarLinkAppList()` 返回空，界面拿不到数据；而已缓存在 `mCachedCarLinkAppList` 的历史列表也没有被本次回调数据填充，导致"偶现无应用"。另外成员变量 `mCachedCarLinkAppList` 原本就是本次修复前加的缓存骨架（batches 元数据称"添加缓存策略"），此提交把"回调分包数据 → 缓存"的链路真正接上。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt`
```diff
--- .../java/com/yadea/launcher/control/DeviceConnectManager.kt
@@ 成员变量
     private var mCachedCarLinkAppList:
-        List<AppDetailInfo> = emptyList()
+        List<AppDetailInfo> = emptyList()
+    private var mTempCarLinkAppList: MutableList<AppDetailInfo> = mutableListOf()

@@ mICarLinkPhoneAppListChangedListener.onAppListLoaded(...)
+                if (currentPacket == 1) {
+                    mTempCarLinkAppList.clear()
+                }
+                appInfos?.forEach {
+                    mTempCarLinkAppList.add(AppDetailInfo(
+                        it!!.appGeneraIInfo.appId, it.appGeneraIInfo.label,
+                        it.appGeneraIInfo.category, it.packageName, it.icon))
+                }
+                if (currentPacket == totalPacket) {
+                    updateCarLinkAppList(mTempCarLinkAppList)
+                }

@@ private fun updateCarLinkAppList(list ... = null)
-            val appInfoList = getCarLinkAppList()
+            val appInfoList = if (list == null) {
+                getCarLinkAppList()
+            } else {
+                mCachedCarLinkAppList = list
+                list
+            }
```

## 为什么能修复
回调路径从"只记日志"改为：首包清空临时列表 → 逐包填充 → 末包（`currentPacket == totalPacket`）把整表写入 `mCachedCarLinkAppList` 并走原有 UI 通知链。这样即使同步 Binder 查询 `getCarLinkAppList()` 超时，界面也能拿到推送数据，消除空白。隐患：`mTempCarLinkAppList` 无同步保护，若两轮分包回调交错可能混包；且回调内构造的 `AppDetailInfo` 未判空 `packageName` 等字段，异常数据仍可能引发问题。

## 复盘与经验
- 对分包推送的列表数据，必须在回调侧自组包（首包清零 + 末包提交），不能假设"反正还有同步查询兜底"。
- "偶现"类缺陷优先看数据到达路径：同步查询超时 + 回调被忽略是最常见组合。
- 回调写入共享集合要注意线程安全，建议 `synchronized` 或改用并发容器。
