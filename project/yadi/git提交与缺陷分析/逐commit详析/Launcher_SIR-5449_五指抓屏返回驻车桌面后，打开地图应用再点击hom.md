# SIR-5449 · 五指抓屏返回驻车桌面后 3D 车模不渲染全屏黑屏

- **提交**：`9d4b7144` | 2026-08-06 | liang-jy | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
D 档行驶中五指抓屏返回驻车（3D 车模）桌面后，再打开地图应用并点 home 键回到桌面，3D 驻车桌面不显示，全屏黑屏。

## 根因分析
车模渲染的启停仲裁逻辑原先只依赖**档位信号**：`KanziDataSourceManager.checkAndPerformRenderState()` 中判断 `mCurrentGear == 1 || mCurrentGear == 2`（D/R 档）即调 `setRenderStop()`，仅 P 档（0）才 `setRenderStart()`。档位变化回调 `OnGearChangeListener` 挂在 `KanziSignalMapping` 的 `ENERGY_PCU_ACTUALGEAR` 信号处理器里，只在档位值变化时触发一次。该性能优化方案遗漏了一个场景：D 档下用户仍可通过五指抓屏回到桌面。此时档位保持 D 档不变（不会触发 gear 回调），即使 `setForegroundState(true)` 走到仲裁逻辑，`mCurrentGear` 仍为 1，渲染被再次判停——车模画面从此不再渲染，桌面只剩黑屏。本质是"用档位间接推断桌面可见性"这一条件不完备：档位为 D 不代表车模画面不可见。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java`、`application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java`、`application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java`
```diff
// application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java
-    private int mCurrentGear = 0; // 0=P, 1=D, 2=R
+    /** 当前仪表形态(Meter_Form)，0/3=车模形态可渲染，其余形态暂停渲染 */
+    private volatile int mCurrentMeterForm = 0;
+    private VehicleService.OnMeterFormChangeListener mMeterFormChangeListener;
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java（核心仲裁逻辑重写）
-        if (!mIsForeground || mCurrentGear == 1 || mCurrentGear == 2) {
-            if (mIsForeground && mCurrentGear == 1) {
-                ThreadUtils.getMainHandler().postDelayed(mRenderStopRunnable, DELAY_D_GEAR_STOP_MS);
-            } else {
-                setRenderStop();
-            }
-        } else if (mCurrentGear == 0) {
+        if (!mIsForeground) {
             setRenderStart();
+        } else if (mCurrentMeterForm == 0 || mCurrentMeterForm == 3) {
+            setRenderStart();
+        } else {
+            setRenderStop();
         }
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java（新增仪表形态信号分发）
+            if ("Meter_Form".equals(id)) {
+                for (OnMeterFormChangeListener listener : mMeterFormChangeListeners) {
+                    listener.onMeterFormChange(value);
+                }
+            }
```
同时在 `VehicleService` 新增 `OnMeterFormChangeListener` 接口与 `CopyOnWriteArrayList` 监听器列表，`KanziDataSourceManager` 注册回调并把渲染仲裁条件从档位切换为仪表形态（0/3=车模形态），并删除了 `KanziSignalMapping` 中的 `OnGearChangeListener` 整条链路、D 档延迟 600ms 停渲染的 `mRenderStopRunnable`。

## 为什么能修复
修复把渲染条件从"档位是否为 P"换成"仪表形态（Meter_Form）是否为车模画面形态"。五指抓屏回桌面、home 回桌面等任何入口只要让车模画面重新显示，仪表形态信号都会变化并触发 `handleMeterFormChangeForRendering` → `checkAndPerformRenderState()` → `setRenderStart()`，不再依赖档位变化事件，场景覆盖完整。副作用方面：D 档 600ms 延迟停渲染（保护车模动效播完）被一并删除，若动效期间 Meter_Form 尚未切走，可能出现动效被提前暂停的小瑕疵；回调用主线程 Handler post 且监听列表为 CopyOnWriteArrayList，线程安全处理到位。

## 复盘与经验
- 用"间接状态"（档位）推断"真实意图"（车模画面是否可见）时，必须穷举所有能让画面可见的入口（物理按键、手势、语音等），否则优化条件天然漏场景。
- 渲染/功耗类性能优化改动，回归测试范围应覆盖所有返回桌面的交互方式，而不仅是档位切换主链路。
- 事件驱动的状态机要区分"事件触发同步"与"状态查询兜底"：原实现初始档位是主动查询的，但后续只靠事件，事件覆盖不全即产生脏状态；改为订阅更贴近真实语义的信号（Meter_Form）后链路更短更可靠。
- 平台侧新增信号分发（VehicleService 的 `"Meter_Form".equals(id)` 字符串匹配）时注意信号 id 的协议约定，字符串硬编码建议收敛为常量。
