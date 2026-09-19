# SIR-7936/7938/7922 · 仪表 D/R 档白屏 & 从能量中心回主界面桌面白屏
- **提交**：`83ab8df5` | 2026-09-09 | ljl | SystemUI（Launcher/Carlib） | bugfix
- **缺陷库**：SIR-7936 A·必现·仪表信息（Carlib 信号注册冲突）；SIR-7938 A·必现·仪表信息（同 7936）；SIR-7922 C·偶现·能量中心（kanzi 断开后 onresume 未复归通知渲染）

## 问题
两个 A 级必现问题：进入行车仪表后 D/R 档有车速时仪表白屏（档位/车速信号收不到）；以及从能量中心点状态栏 home 键回主界面时桌面（Kanzi 渲染）白屏。

## 根因分析
① **Carlib 信号注册覆盖**（SIR-7936/7938，A 级）：`PropertyManager.registerPropertyCallbacks` 在车服务未连接时把回调暂存进 `pendingCallbacks`（以 callback 对象为 key 的 map），暂存逻辑是 `pendingCallbacks[callback] = propertyIds to immediateCallback`——同一 callback 分批注册多组属性（如 DigitalKeyVehicleService 先注册档位/车速等核心属性、再注册滚轮属性）时第二次直接整体覆盖第一次，先注册的属性组永久丢失；服务重连后按 `pendingCallbacks` 补注册时档位/车速根本不在列表里，仪表拿不到信号无法进入行车页，D/R 档白屏。
② **Kanzi 渲染启动命令被静默丢弃**（SIR-7922）：`KanziDataSourceManager.setRenderStart()` 只在 `isKanziConnected && kanziManager != null` 时调用 `kanziManager.setRenderStart()`，Kanzi 断开窗口期到达的启动命令被静默丢弃且无人补发；Launcher onResume 时若 Kanzi 尚未重连，重连后也没有机制重跑渲染仲裁，桌面永久白屏。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java；component/Carlib/src/main/java/com/neusoft/libcar/manager/PropertyManager.kt
```diff
--- component/Carlib/src/main/java/com/neusoft/libcar/manager/PropertyManager.kt
@@ -191,9 +191,15 @@
         } else {
-            // 如果服务未连接，按 callback 维度暂存
-            pendingCallbacks[callback] = propertyIds to immediateCallback
+            // 同一 callback 可能分批注册多组属性，必须合并而不是整体覆盖，
+            // 否则先注册的属性组会丢失（SIR-7936：档位/车速丢失导致仪表无法进入、主界面白屏）
+            val existing = pendingCallbacks[callback]
+            val mergedIds = ((existing?.first ?: emptyList()) + propertyIds).distinct()
+            val mergedImmediate = (existing?.second ?: false) || immediateCallback
+            pendingCallbacks[callback] = mergedIds to mergedImmediate
         }
```
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java
@@ -338,6 +359,9 @@
         sendSeatNamesToKanzi();
+        // Kanzi连接/重连后重跑渲染仲裁：补发renderStart，避免重连前被静默丢弃的启动命令造成永久白屏（SIR-7922）
+        ThreadUtils.getMainHandler().post(this::checkAndPerformRenderState);
     }
+
+    private final Runnable mRenderWatchdog = new Runnable() {
+        @Override
+        public void run() {
+            if (mIsForeground && (mCurrentMeterForm == 0 || mCurrentMeterForm == 3) && !mRenderStarted) {
+                LogUtils.w(TAG, "render watchdog: foreground with renderable form but rendering stopped, re-issue setRenderStart");
+                setRenderStart();
+            }
+            ThreadUtils.getMainHandler().postDelayed(this, RENDER_WATCHDOG_INTERVAL_MS);
+        }
+    };
```
`setRenderStart/setRenderStop` 维护 `mRenderStarted` 标志；连接建立入口注册 2 秒周期看门狗，"前台+可渲染形态+未启动渲染"持续一个周期即自动补发 `setRenderStart`。

## 为什么能修复
① 暂存从"覆盖"改"合并"（`distinct()` 去重、`immediateCallback` 取或），分批注册的所有属性组在服务重连后一次性补注册，档位/车速信号恢复，仪表可正常进入行车页——两个 A 级白屏随之消除；② Kanzi 连接成功回调里 post 一次 `checkAndPerformRenderState()` 重跑仲裁，加上 2 秒看门狗周期性自愈，重连后渲染必然恢复，覆盖"resume 落在断开窗口"的竞态。风险：`immediateCallback` 合并取或后，原本不需要立即回传的批次也会立即回传，属行为放宽；看门狗补发渲染启动依赖 `mRenderStarted` 状态准确，若外部直接改渲染状态需同步该标志。

## 复盘与经验
- 以 callback 为 key 的暂存 map，"覆盖写"默认假定一个 callback 只注册一次——分批注册场景必须合并，这类缺陷（A 级、必现、跨模块）值得在 Carlib 层加单测守护。
- 渲染类"命令可能被静默丢弃"的链路（命令端与渲染端生命周期解耦），必须有重连补偿 + 周期看门狗双保险，单靠时序对齐无法根治竞态。
- 白屏类问题排查路径：先确认数据/信号是否到达（PropertyManager 注册表日志），再查渲染仲裁（setRenderStart 是否到达 Kanzi），两个 A 级单指向同一提交说明根因梳理到位。
