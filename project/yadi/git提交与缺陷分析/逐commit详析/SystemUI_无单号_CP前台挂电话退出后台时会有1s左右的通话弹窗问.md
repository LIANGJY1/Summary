# 无单号 · CarPlay 前台挂断退后台闪现"通话结束"弹窗 & 音量 OSD 适配黑白主题

- **提交**：`396999dc` | 2026-08-06 | ljl | SystemUI（核心改动在 BTPhone） | bugfix
- **缺陷库**：未关联单号

## 问题
两个问题：1) CarPlay 前台通话时挂断电话并退出 CarPlay 回后台，屏幕会闪现约 1 秒的"通话结束"通话弹窗；2) 音量 OSD 相关 drawable 硬编码深灰色 `#3C4558`，黑白主题下颜色不适配。

## 根因分析
问题 1 是典型的状态时序竞态：`CarPlayCallManager.handleCallStateUpdate()` 收到 `CALL_STATE_DISCONNECTED` 时，无论 CarPlay 前后台统一走"`mCallMap.put(uuid, copyOf(info))` + `scheduleRemoveEndedCall(uuid)`"的"结束卡片保留 1.5s 再移除"流程。该策略只为 CP 后台挂断设计；当挂断发生在 CP 前台时（弹窗本就不显示），残留路在 Map 里活 1.5 秒，若用户在此窗口内退出 CarPlay（`onCarPlayDisplayStatusChanged(false)` → `refreshWindow()`），`refreshWindow` 会把 Map 里这条"通话结束"残留路渲染成弹窗，造成闪现。根因即提交所述"CP 电话状态时序问题"：通话路的生命周期管理没有区分前台/后台可见性。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallManager.java`、`application/SystemUI/src/main/res/drawable/`（5 个 bg_volume_* drawable）、`application/SystemUI/src/main/res/drawable/vector_volume_to_muti_arrow.xml`、`application/SystemUI/src/main/res/layout/layout_volume_bar_item.xml`
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallManager.java
-            // 挂断：展示"通话结束"卡片，延时 1.5s 后移除该路
-            mCallMap.put(uuid, copyOf(info));
-            scheduleRemoveEndedCall(uuid);
+            if (isCarPlayForeground()) {
+                // CP 前台时挂断：弹窗本就不显示，不走"结束卡 1.5s"流程直接移除，
+                // 否则 1.5s 窗口期内 CP 退到后台会闪现"通话结束"卡片
+                removeCall(uuid);
+            } else {
+                // CP 后台时挂断：展示"通话结束"卡片，延时 1.5s 后移除该路
+                mCallMap.put(uuid, copyOf(info));
+                scheduleRemoveEndedCall(uuid);
+            }
```
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallManager.java（前后台切换兜底）
                                 mCarPlayForeground = displaying;
+                                if (!displaying) {
+                                    // 前台->后台切换：先清掉已结束（DISCONNECTED/UNKNOW）的残留路再刷新
+                                    removeEndedCalls();
+                                }
                                 refreshWindow();
```
```diff
// application/SystemUI/src/main/res/drawable/bg_volume_expand_circle.xml（其余 4 个 drawable 同理）
-    <solid android:color="#0F3C4558" /><!-- @color/gray_800_6 -->
+    <solid android:color="@color/bg_segmentbutton_default" />
```
新增 `removeEndedCalls()` 用迭代器遍历 `mCallMap`，移除 `CALL_STATE_DISCONNECTED`/`CALL_STATE_UNKNOW` 状态的残留路，保留 ACTIVE/HELD/RINGING 等通话中的路。

## 为什么能修复
双保险：一是挂断事件按前后台分流，CP 前台挂断直接 `removeCall`，根本不产生 1.5s 残留窗口；二是即使时序再乱（如挂断与前后台切换交错），退后台时 `removeEndedCalls()` 先清残留再刷新，渲染层永远看不到已结束的路。通话中的路不受影响，后台挂断的"结束卡片"体验保留。OSD 侧把硬编码色值替换为主题语义色（`bg_segmentbutton_default`、`icon_default_default`），黑白主题自动切换。副作用：`isCarPlayForeground()` 读取瞬间的竞态仍可能存在（挂断时前台、刷新时后台），但兜底逻辑已覆盖该组合。

## 复盘与经验
- "延时移除"这类为特定可见性场景设计的缓冲策略，必须限定触发条件（如仅后台可见时），否则窗口期内的状态切换会放大成 UI 异常。
- 时序竞态修复讲究"分流 + 兜底"两层：入口处分流正确路径，出口处再按状态过滤一次，比只修一处更稳。
- drawable 硬编码 `#RRGGBBAA` 是主题适配的最大障碍，即便注释里写了对应色名，也应直接引用主题语义色资源。
