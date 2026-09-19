# 无单号 · 能量中心 V3 UI 修改

- **提交**：`618e9251` | 2026-08-22 | liqingqing | EnergyManagement | feature（大型 UI 重构提交）
- **关联单**：无

## 需求/目标
能量中心 V3 改版：主界面/续航管理页 UI 重构、充电限值 SeekBar 刻度组件化、电池电量与粒子特效帧动画资源、夜间模式资源补齐，并接入 TBOX 服务客户端（预约充电状态）。

## 实现结构
682 个文件、+1760/-925（约 640 个为 png 帧资源）。代码侧：
- 新增 `module/TboxClientManager.java`（438 行）：基于 `vendor.hardware.tbox.ITboxService`（HIDL/AIDL binder，ServiceManager 直取 `.../default`），clientId=9 注册，DeathRecipient + 3s 重连，HandlerThread 串行处理 onMessage，解析 JSON 预约充电状态（ReservationState 默认 23:00~7:00）并回调 `ReservationListener`；
- `module/VehicleService.java` +70：扩展车信号缓存；
- 自定义控件重写：`EnergyBarSeekBar`（331 行改动，充电限值滑杆）、`EnergyDistributionView`（229 行改动，能量分配图）、新增 `ScaleTickView`（自绘刻度尺）、`ChargeLimitInfoDialog`、`LabelValueLayout`；
- `MainActivity`（355 行改动）、`MileageManagementActivity` 适配新布局 `activity_main.xml`（614 行改动）、`InitService` 接线；
- 资源：battery_energy_000~xxx、particle_effect_000~359 两套帧序列动画，drawable-night 夜间全套补齐。
数据流：TBOX binder 消息 → TboxClientManager（工作线程解析）→ ReservationListener → MainActivity/Dialog 刷新预约充电 UI；车信号走 VehicleService 缓存；充电限值由 EnergyBarSeekBar + ScaleTickView 呈现并回写。

## 关键代码
```diff
--- /dev/null  (application/EnergyManagement/src/main/java/com/yadea/energymanagement/module/TboxClientManager.java)
+    private final IBinder.DeathRecipient mDeathRecipient = () -> {
+        LogUtils.e(TAG, "TBOXservice binder died");
+        synchronized (mServiceLock) {
+            clearServiceLocked(false);
+        }
+        scheduleReconnect();
+    };
+
+    private final ITboxCallback mCallback = new ITboxCallback.Stub() {
+        @Override
+        public void onMessage(TboxMessage message) {
+            ...
+            Handler handler = mWorkerHandler;
+            if (handler != null) {
+                String content = message.content;
+                handler.post(() -> handleIncomingMessage(content));
+            }
+        }
```
```diff
--- /dev/null  (application/EnergyManagement/src/main/java/com/yadea/energymanagement/view/custom/ScaleTickView.java)
+        for (int value = minValue; value <= maxValue; value += minorStep) {
+            float ratio = (value - minValue) / (float) (maxValue - minValue);
+            float x = w * ratio;
+            if ((value - minValue) % majorStep == 0) {
+                boolean isFirstOrLast = (value == minValue) || (value == maxValue);
+                float halfH = isFirstOrLast ? dpToPx(4) : dpToPx(2);
+                canvas.drawLine(x, cy - halfH, x, cy + halfH, majorPaint);
+            } else {
+                canvas.drawCircle(x, cy, dpToPx(1), minorPaint);
+            }
+        }
```
实现讲解：TboxClientManager 是标准的车机 binder 客户端模板——linkToDeath 感知服务死亡、指数化外的固定 3s 重连、binder 回调抛到 HandlerThread 串行解析，避免 binder 线程阻塞；ScaleTickView 则把设计稿的刻度规则（大刻度蓝线 2dp、首尾 8dp 加高、小刻度 2dp 圆点、minorStep=majorStep/5）参数化为自绘 View，与 SeekBar 有效范围(50~100)对齐。帧动画选择"预渲染 png 序列 + 逐帧切换"而非 Lottie/APNG，牺牲包体（约 640 张图）换低端机上确定的渲染表现。

## 复盘与要点
- 640+ 张帧序列 png 直接进 mdpi 目录且无密度变体，包体与内存（逐帧 decode）都有优化空间，可评估 AnimatedVectorDrawable 或 Lottie。
- TboxClientManager 的"DeathRecipient + 重连 + 单例状态缓存"可直接抽成公共组件，本项目已有多个类似 manager。
- 影响等级 A 但测试范围仅"能量中心UI显示"，实际新增了 TBOX binder 依赖与预约充电数据链路，测试范围偏窄。
