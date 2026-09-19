# SIR-7099 · 后摄异常 Toast 去掉去重逻辑（需求变更）

- **提交**：`40e7a345` | 2026-09-14 | ljl | SystemUI | bugfix（需求变更落地）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 仪表信息

## 问题
后视摄像头故障提示 Toast 此前做了"一次挂 R 档只弹一次"的去重，需求变更后要求每次收到故障状态都弹提示，原去重逻辑需要移除。

## 根因分析
原实现在 `SystemSettingsControllerService` 里维护去重标志 `mRearCameraFaultShown`（AtomicInteger），`checkRearCameraFault` 用 `getAndSet(1) == 0` 保证一次挂 R 档期间只弹一次；复归逻辑放在另一个类 `DigitalKeyVehicleService` 的档位回调（`gear != 2` 时调用 `resetRearCameraFaultToast()`）。这套"跨类状态去重"带来两个问题：一是故障持续期间用户换页面后再挂 R 才能再看到提示，需求上不希望漏报；二是标志的置位与复归分散在两个 Service，状态生命周期复杂、易漏复归。缺陷库根因/方案均为"需求变更"——最终按产品要求去掉去重，依赖 `ToastUtils.showMsgICToast` 自身"只保留最新一条"的机制防止叠加。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt`、`application/SystemUI/src/main/java/com/android/systemui/digitalkey/DigitalKeyVehicleService.kt`
```diff
// SystemSettingsControllerService.kt
-    // 后摄故障 Toast 去重：一次挂 R 档期间只弹一次；档位切到非 R 档时由 DigitalKeyVehicleService 复归
-    private val mRearCameraFaultShown = AtomicInteger(0)
...
-        if (cameraOpt != 0 && com0 != 0 && mRearCameraFaultShown.getAndSet(1) == 0) {
+        if (cameraOpt != 0 && com0 != 0) {
             LogUtils.d(TAG, "rear camera fault -> toast (opt=$cameraOpt, com0=$com0)")
             ToastUtils.showMsgICToast(mAppContext, mAppContext.getString(R.string.rear_camera_fault_toast))
         }
-    /** 档位切到非 R 档（P/D）时复归后摄故障 Toast 去重标志 */
-    fun resetRearCameraFaultToast() { mRearCameraFaultShown.set(0) }
```
```diff
// DigitalKeyVehicleService.kt
-        // 切到非 R 档（P/D）复归后摄故障 Toast 去重，下次挂 R 档若仍故障可再次提示
-        if (gear != 2) {
-            mSystemSettingsControllerService.resetRearCameraFaultToast()
-        }
```

## 为什么能修复
删除去重标志与跨类复归调用后，`checkRearCameraFault` 变成纯"状态即提示"的直通逻辑：每次故障信号到达都弹 Toast，不再依赖档位变化复位；防叠加交给 `showMsgICToast` 的"仅保留最新一条"语义，不会连续弹多条。逻辑链路缩短、跨 Service 耦合（`resetRearCameraFaultToast`）被移除。隐患：故障信号高频重发时会反复触发 Toast 更新（被 ICT 机制吸收为持续显示最新一条），需确认这种"常驻提示"正是产品期望；同时原子标志删除后不再有节流，信号风暴时日志量增加。

## 复盘与经验
- 提示类需求的"去重策略"应先确认产品语义（漏报可接受性），自作主张去重会随需求变更被整体推翻——本例的跨类复归机制全部报废。
- 防 Toast 叠加应依赖统一的 Toast 管理器（保留最新一条），而不是在业务侧自建标志位，职责更清晰。
- 状态置位与复归分散在两个类（Service）是难维护的信号，若确需去重也应收敛到信号源附近并用明确的生命周期管理。
