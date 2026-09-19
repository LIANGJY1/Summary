# SIR-8730 · 偶现断开 Carlink 后仍显示融合应用列表

- **提交**：`d2561eea` | 2026-09-18 | dufan | Launcher（applist，提交信息标注 Setting，以 diff 为准）| bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 解决方案 · 域 手车互联

## 问题
偶现：Carlink 断开后，Launcher 的融合桌面/融合应用列表仍然显示，未切回车机本地应用列表。

## 根因分析
Launcher 的 `CarConnectFragment` 通过 `onDeviceAppListLoaded(deviceType, appInfoList)` 应用列表接口回调刷新数据：无条件 `mAppInfoList.clear()` 后 `addAll(appInfoList)`。断开 Carlink 时，连接状态回调先置 `isCarLinkConn = false`，但设备侧应用列表接口的回调是异步的——断开前后在途的一次 `CARLINK` 类型回调仍会到达并覆盖 `mAppInfoList`，把融合应用列表重新刷进 UI。由于列表回调不校验连接状态，只要"断开与列表回调"时序交错（偶现），就会出现"断了还显示融合桌面"。缺陷库 `[why]收到应用接口回调 / [how]先判断是否连接` 与 diff 完全一致。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt（+2/-2）
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt
@@ onDeviceAppListLoaded
         LogUtils.i(
-            TAG, ("onDeviceAppListLoaded deviceType:" + deviceType
-                    + ", appInfoList:" + appInfoList.toString())
+            TAG, ("onDeviceAppListLoaded deviceType:$deviceType, isCarLinkConn:$isCarLinkConn, appInfoList:$appInfoList")
         )
+        if (DeviceConnectManager.CARLINK == deviceType && !isCarLinkConn) return
         mAppInfoList.clear()
         mAppInfoList.addAll(appInfoList)
         if (activity == null) return
```

## 为什么能修复
新增的守卫把"应用列表回调"与"连接状态"挂钩：CARLINK 类型的列表回调到达时若 `isCarLinkConn == false` 直接丢弃，断开瞬间的在途回调不再污染 `mAppInfoList`，融合列表随断开状态正确收起；其他 deviceType 的回调不受影响。同时日志补充了 `isCarLinkConn`，后续偶现问题可直接从日志对出时序。潜在边界：若"断开后主动要求刷新一次本地兜底列表"也走同一回调，则依赖其 deviceType 非 CARLINK 才能通过守卫，从代码看各类型区分明确，风险低。

## 复盘与经验
- 异步回调进入 UI 前必须校验"当前状态是否仍与产生该回调的前提一致"（连接、页面存活、会话 id 等），断开/取消只改状态位挡不住在途回调，"先判断再消费"是标准防御。
- 偶现时序 bug 的高杠杆修复往往是一行守卫；让日志带上判定所用的全部状态（本例补打 isCarLinkConn），复现时可即时定位是时序问题还是状态问题。
- 状态位（isCarLinkConn）应作为 UI 数据源的唯一裁决入口：所有可能改列表的路径都过同一守卫，避免"清了又加、加了没人清"。
