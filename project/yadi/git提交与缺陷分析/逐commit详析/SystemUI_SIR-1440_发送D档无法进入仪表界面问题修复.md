# SIR-1440 · 偶现：挂 D 挡无法进入仪表界面
- **提交**：`ca15d56c` | 2026-07-01 | ljl | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
偶现：发送 D 挡后车机未切换进入仪表界面，停在原页面。

## 根因分析
仪表形态值 `ID_METER_FORM` 的下发源在 `SystemSettingsControllerService`（`application/SystemUI/.../cmdcontroller/systemsetting/SystemSettingsControllerService.java`）。需求变更后地图中间件也会下发仪表状态，主交互因此从"开机取一次"改为"持续监听" `displayState/meterState` 两个 LiveData。而 IviComm 的 `onCommState` 回调里每次都会带来 Get 应答和重复旧值，旧代码无条件 `displayState.postValue(value)`。另一方面，切换到仪表状态 1 的逻辑带 2s 延迟（等待切页动画），延迟期间任何一次旧值 post 都会再次触发 `DigitalKeyVehicleService` 的 observer，把 `PageStateMachine` 刚建立的"切往仪表 1"状态冲掉/取消（延迟任务有容错：状态被切换则放弃通知），于是 D 挡切换偶发丢失。日志时序显示 13:29:44.095 收到 Gear_D、44.138 `PageStateMachine: TRANSITION S0 -> S1`，随后旧 Meter_Form 值再次 post 干扰了状态机。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.java`
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.java
@@ -88,6 +89,10 @@
     public MutableLiveData<Integer> displayState = new MutableLiveData<>();
     public MutableLiveData<Integer> meterState = new MutableLiveData<>();
+    // 仪表形态/模式在 IviComm 回调里会同时收到 Get 应答和 Set 确认，
+    // 很多是重复值；用原子变量去重，避免一直监听的 observer 被旧值刷新。
+    private final AtomicInteger mLastMeterForm = new AtomicInteger(0);
+    private final AtomicInteger mLastMeterStyle = new AtomicInteger(0);
@@ -1075,11 +1080,17 @@
                 //仪表形态
                 case SysUIConfig.ID_METER_FORM:
-                    displayState.postValue(value);
+                    // 去重：IviComm 每次都会回调（含 Get 应答），相同旧值不应刷新 LiveData，
+                    // 否则一直监听的 DigitalKeyVehicleService 会把 PageStateMachine 状态冲掉。
+                    if (mLastMeterForm.getAndSet(value) != value) {
+                        displayState.postValue(value);
+                    }
                     break;
                 //仪表模式
                 case SysUIConfig.ID_METER_STYLE:
-                    meterState.postValue(value);
+                    if (mLastMeterStyle.getAndSet(value) != value) {
                         meterState.postValue(value);
                     }
                     break;
```

## 为什么能修复
在数据源头（`displayState/meterState` 产生处）用 `AtomicInteger.getAndSet` 去重：只有值真正变化才 `postValue`。重复旧值不再触发 observer，也就不会在 2s 延迟窗口内冲掉 `PageStateMachine` 的仪表切换状态，D 挡进仪表恢复稳定；真正的变化（0->1、0->4）仍正常下发。无需改动状态机与监听端。隐患：`mLastMeterForm` 初值 0，若系统开机首值为 0 则首帧不会 post（消费方需有默认态）；进程内单例 Service 下 AtomicInteger 足够，但多实例场景需谨慎。

## 复盘与经验
- "从拉取一次改持续监听"后，回调里的重复值/Get 应答就成了噪声源，事件流入口必须去重，否则下游状态机被无意义刷新。
- 带延迟补偿的状态切换对"重复事件"极其脆弱；去重要放在数据产生处，而不是让每个下游自己防御。
- 偶现问题靠 logcat 时序（Gear_D -> TRANSITION -> 旧值 post）定位，保留关键状态机日志是排查基础。
