# SIR-5651 · 3D车模尾箱盖打开切D档无消息通知报警

- **提交**：`8aed83b4` | 2026-08-04 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
尾箱盖打开状态下切换到 D 档，系统没有弹出任何报警通知，存在安全风险。

## 根因分析
3D 车模链路此前只把尾箱盖状态渲染到车模动画，没有任何代码监听"尾箱盖打开 + 非 P 档"这一组合安全条件并通知 SystemUI——即跨模块的告警通知链路缺失（缺陷库：没通知 systemUI）。补齐该链路需要在 Launcher 侧组合监听 `THREE_D_MODEL_PCU_REARBOXCOVERSTATUS`（尾箱盖）与 `ENERGY_PCU_ACTUALGEAR`（实际档位）两个车况属性，再经 `NotificationManager` 发布告警。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/RearBoxCoverAlertManager.java（新增）、application/Launcher/src/main/java/com/yadea/launcher/utils/NotificationUtils.java、application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java、application/Launcher/src/main/java/com/yadea/launcher/Myapplication.kt、application/Launcher/src/main/res/drawable/broken.png、application/Launcher/src/main/res/values/strings.xml、application/Launcher/src/main/res/values-en/strings.xml
```diff
// application/Launcher/src/main/java/com/yadea/launcher/control/RearBoxCoverAlertManager.java（新增，节选）
     private void handleCarEvent(AppCarPropertyValue<?> event) {
         ...
             if (propertyId == CarPropertyIds.THREE_D_MODEL_PCU_REARBOXCOVERSTATUS) {
                 mRearBoxCoverStatus = value;
             } else if (propertyId == CarPropertyIds.ENERGY_PCU_ACTUALGEAR) {
                 mActualGear = value;
             }
             updateWarningLocked();
     }
     private void updateWarningLocked() {
         if (mRearBoxCoverStatus == UNKNOWN || mActualGear == UNKNOWN) return;
         boolean shouldWarn = mRearBoxCoverStatus == 1 && mActualGear != 0;
         if (mLastWarningCondition != null && mLastWarningCondition == shouldWarn) return;
         mLastWarningCondition = shouldWarn;
         if (shouldWarn) NotificationUtils.showRearBoxCoverWarning(mContext);
         else NotificationUtils.cancelRearBoxCoverWarning(mContext);
     }
```
```diff
// application/Launcher/src/main/java/com/yadea/launcher/utils/NotificationUtils.java
+    public static void showRearBoxCoverWarning(Context context) {
+        ...
+        createChannelIfNeeded(nm, CHANNEL_VEHICLE_SAFETY_ALERT, ...);
+        Bundle extras = new Bundle();
+        extras.putString("notification_level", "l0");
+        Notification notification = new Notification.Builder(context, CHANNEL_VEHICLE_SAFETY_ALERT)
+                .setSmallIcon(R.drawable.broken)
+                .setCategory(CATEGORY_CAR_WARNING)
+                .setPriority(Notification.PRIORITY_HIGH)
+                .setOnlyAlertOnce(true)
+                .setOngoing(true)
+                .addExtras(extras)
+                .build();
+        nm.notify(ID_REAR_BOX_COVER_WARNING(10011), notification);
```
接线：`Myapplication` 启动时 `RearBoxCoverAlertManager.getInstance(this).init()`，`init` 中 `mVehicleService.setEventChangeListener(...)` 注册监听并 `readInitialState()` 读取两属性初值。通知 extras 带 `notification_level=l0`、category `car_warning` 供 SystemUI 识别展示。

## 为什么能修复
单例管理器对两个车况属性做"状态缓存 + 边沿判断"（`mLastWarningCondition` 去重），条件从 false→true 时发持续型告警（`setOngoing(true)`），true→false 时取消，开机/中途进入都能靠 `readInitialState()` 补齐初值，覆盖"先开尾箱盖再切 D"与"行驶中打开尾箱盖"两种时序。风险：`mActualGear != 0` 隐含 0=P 的协议约定，未用具名常量；UNKNOWN 哨兵值与真实取值需确保不冲突。

## 复盘与经验
- 车况安全告警的本质是"多信号组合条件的边沿检测"，需要缓存各信号最近值、判条件、再对条件变化去重，而不是在单个信号回调里直接弹通知。
- 监听器初始化时必须回读一次属性初值（getProperty），否则"条件在注册前已成立"的场景会漏报。
- 跨应用通知要约定 extras/Category 协议（如 notification_level），让 SystemUI 能按告警级别渲染，而不是只发文本。
