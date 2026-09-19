# 无单号 · CarPlay 通话页面联调问题 2（SDK 回调防抖三守卫）

- **提交**：`15d7230d` | 2026-07-31 | ljl | BTPhone | feature（**实为联调缺陷修复**）
- **关联单**：无

## 问题
实车联调发现创达 CarPlay SDK 在监听注册后会"回放"一批脏回调：uuid 为空的垃圾回调、同一 `uuid+status` 连发 2~6 次的重复回调、以及针对 Map 中不存在通话的回放 `DISCONNECTED/UNKNOW`，导致悬浮窗出现空卡片、重复渲染与莫名的"通话结束"卡片。

## 根因分析
`handleCallStateUpdate()` 把 SDK 回调不加甄别地写入 `mCallMap` 并触发 `refreshWindow()`，而 SDK 的回放行为没有契约保障；空 uuid 无法唯一定位一路通话，重复状态重复渲染，未知 uuid 的结束态会凭空渲染结束卡片。

## 关键代码修改
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallManager.java
@@ -431,13 +432,40 @@
+        // 守卫1：SDK 在监听注册后会回放 uuid 为空的垃圾回调（实车 logcat 确认），
+        // 空 uuid 无法定位一路通话，直接忽略，不进 Map、不触发任何 UI
+        if (TextUtils.isEmpty(uuid)) {
+            LogUtils.w(TAG, "handleCallStateUpdate: ignore empty uuid callback, status=" + status);
+            return;
+        }
+
+        CallStateInfo existing = mCallMap.get(uuid);
+        int oldStatus = existing != null ? safeStatus(existing) : Integer.MIN_VALUE;
+
+        // 守卫2（幂等）：同一 uuid+status 会连发 2~6 次，与 Map 中已有状态相同则跳过重复渲染
+        if (oldStatus == status) {
+            LogUtils.d(TAG, "handleCallStateUpdate: duplicate status, skip");
+            return;
+        }
+
         if (status == CallStateInfo.CALL_STATE_UNKNOW) {
+            // 守卫3：Map 中不存在的回放回调直接忽略
+            if (existing == null) {
+                return;
+            }
             removeCall(uuid);
         } else if (status == CallStateInfo.CALL_STATE_DISCONNECTED) {
+            // 守卫3：不是我们正在显示的一路（注册后回放的垃圾 DISCONNECTED），直接忽略，
+            // 只有 Map 中已存在的路收到 DISCONNECTED 才渲染"通话结束"卡片
+            if (existing == null) {
+                return;
+            }
             mCallMap.put(uuid, copyOf(info));
             scheduleRemoveEndedCall(uuid);
```

## 为什么能修复
三道守卫把"不可信输入"隔离在状态写入之前：守卫1 拦截无法定位通话的空 uuid；守卫2 以 Map 中旧状态做幂等键，重复通知不再触发重渲染；守卫3 要求结束类事件必须对应 Map 中已存在的一路，回放的垃圾 DISCONNECTED/UNKNOW 不再凭空生成/移除卡片。UI 只反映 Map 的合法迁移，脏数据被完全挡在门外。

## 复盘与经验
- 对第三方/系统 SDK 回调要默认"可能有垃圾回放"，入口处按"uuid 有效性 → 状态幂等 → 状态机合法性"分层过滤，是悬浮窗类 UI 防抖的通用套路。
- 注释里保留实证依据（"实车 logcat 确认""连发 2~6 次"），把联调结论沉淀为防回归文档，值得效仿。
- 遗留风险：守卫2 以"状态相同"为幂等依据，若同一状态但信息字段（联系人名等）更新，会被误拦；可改为整对象 equals。
