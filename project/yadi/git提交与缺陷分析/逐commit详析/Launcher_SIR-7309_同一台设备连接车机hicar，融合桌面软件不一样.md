# SIR-7309 · 同一设备连 HiCar 时融合桌面应用列表不一致

- **提交**：`d9dec7f0` | 2026-09-03 | dufan | Launcher | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 手车互联

## 问题
同一台手机连接车机 HiCar，融合桌面展示的软件列表不完整/与另一次进入时不一致。

## 根因分析
HiCar 应用列表是**分包**回调的：`IAppInfoChangeListener.onLoadAllAppInfo(appInfos, p1, p2)` 中 p1=总包数、p2=当前包。旧实现忽略分包语义，每收到一包都直接 `updateHiCarAppList()`，而该方法内部走 `getHiCarAppList()` 同步 binder 查询——在流式加载中途查询，拿到的自然是残缺列表（[why]："hicar返回数据不全"）并立刻通知桌面刷新，于是桌面按"半截数据"渲染，且时机不同结果不同。此外缓存字段割裂：HiCar 侧甚至没有自己的缓存写入路径，`mCachedCarLinkAppList`（不可变 `emptyList()` 初始化）与 `mTempCarLinkAppList` 只服务 CarLink，两套逻辑不对称。

## 关键代码修改
改动文件：DeviceConnectManager.kt、LinkActivity.kt
```diff
// application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt
         override fun onLoadAllAppInfo(appInfos: List<AppInfo?>?, p1: Int, p2: Int) {
+            if (p2 == 1) {
+                mTempAppList.clear()          // 第一包：清空累积容器
+            }
             appInfos?.forEach {
                 if (it != null) {
                     mTempAppList.add(AppDetailInfo(-1, it.name, it.type, it.packageName, it.icon))
                 }
             }
+            if (p2 == p1) {
+                updateHiCarAppList(mTempAppList)   // 最后一包才发布完整列表
+            }
         }
```
```diff
// 同文件 updateHiCarAppList：发布时写入统一缓存
-                val appInfoList = getHiCarAppList()
+                val appInfoList = if (list == null) getHiCarAppList() else {
+                    mCachedAppList = list
+                    list
+                }
```

## 为什么能修复
改为"分包累积（mTempAppList）+ 收齐最后一包（p2==p1）再发布"的正确流式聚合，发布的数据必然完整；发布的同时写入统一缓存 `mCachedAppList`，断连时清空缓存，HiCar 与 CarLink 两条链路的缓存模型对齐，桌面任意入口读到的都是同一份完整数据，显示不再漂移。`onAppInfoAdd` 也改为向 `mCachedAppList` 追加后全量重发，保证增量与缓存一致。隐患：若某包丢失且后续不再回调，`p2==p1` 永不满足，列表不刷新，缺少超时兜底；`getCachedCarLinkAppList` 从防御性拷贝改为直接返回引用，外部可变共享有被污染的风险。

## 复盘与经验
- 处理分包/流式 IPC 数据的铁律：先累积、收齐再发布；在中间态发布就是"数据不全/时好时坏"类缺陷的直接来源。
- 同一管理类里 CarPlay/HiCar/CarLink 三条链路的数据结构应统一建模（本例把 mCachedCarLinkAppList/mTempCarLinkAppList 合并为 mCachedAppList/mTempAppList），不对称的缓存路径就是不一致显示的温床。
- 跨进程列表查询（getHiCarAppList）的结果不可当作实时真相，应以事件流的完整快照为准。
