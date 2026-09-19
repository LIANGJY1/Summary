# SIR-8578 · 外部信号改变 SOC 充电上限后，能量中心充电上限显示不刷新

- **提交**：`6848f2a9` | 2026-09-18 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 解决方案 · 域 能量中心

## 问题
通过信号把 SOC 充电上限改为新值后，能量中心主页的充电上限（百分比、滑条、提示文案）仍停留在旧值，不随外部反馈变化。

## 根因分析
`MainActivity`（能量中心主页）此前只把用户拖动 `seekbarRangeMode2` 后的反馈校验（`mChargeLimitFeedbackRunnable` 里读回值再 `updateChargeLimitUi`）作为唯一刷新来源，从未注册 `BMS_SOCThrdSetFeed`（BMS 充电上限阈值反馈信号）的监听。因此凡是非"本页拖动"路径产生的变化（车端/其他入口下发），主页既收不到回调、也不会重读，UI 与真实状态脱节；且旧的反馈校验代码拿到越界值直接 `return`、拿回合法值也不做拖动冲突保护，逻辑分散在多处。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java（+53/-7）
```diff
--- .../energymanagement/view/ui/MainActivity.java
@@ 监听器定义
+    private final VehicleService.EnergyBmsSocThrdSetFeedListener mBmsSocThrdSetFeedListener = value ->
+            handleBmsSocThrdSetFeedChanged(value, SOURCE_CALLBACK);
+
@@ initView
             public void onStopTrackingTouch(SeekBar seekBar) {
+                mChargeLimitTracking = false;
                 int actualValue = seekBar.getProgress() + CHARGE_LIMIT_MIN;
                 LogUtils.d(TAG, "[ChargingLimit] onStopTrackingTouch: finalValue=" + actualValue + "%");
                 sendChargingRestrictionRange(actualValue);
             }
         });
+        registerBmsSocThrdSetFeedListener();
@@ onResume
         refreshCruiseMileageDispModeFromFramework(CALLER_ON_RESUME);
+        refreshChargeLimitFromFramework(CALLER_ON_RESUME);
@@ 新增统一入口
+    private void handleBmsSocThrdSetFeedChanged(int value, String source) {
+        if (isFinishing() || isDestroyed()) {
+            return;
+        }
+        if (value < CHARGE_LIMIT_MIN || value > CHARGE_LIMIT_MAX) {
+            LogUtils.w(TAG, "[ChargingLimit] invalid feedback ignored: value=" + value + ", source=" + source);
+            return;
+        }
+        // 拖动时保留用户正在选择的值，松手下发后由反馈或延迟读值同步。
+        if (mChargeLimitTracking) {
+            return;
+        }
+        LogUtils.d(TAG, "[ChargingLimit] feedback updated: value=" + value + "%, source=" + source);
+        updateChargeLimitUi(value);
+    }
```
配套改动：`onDestroy()` 中 `unregisterBmsSocThrdSetFeedListener()`；旧反馈校验 runnable 的裸读值逻辑改为统一走 `handleBmsSocThrdSetFeedChanged(actualValue, "feedbackVerify")`。

## 为什么能修复
现在有三条互补路径保证 UI 跟随真实值：① 注册 `EnergyBmsSocThrdSetFeedListener` 后，外部信号变化实时回调刷新（消除根因）；② `onResume` 里 `readBmsSocThrdSetFeedOnce` 主动读一次，兜住"监听注册前/页面恢复"期间的错位；③ 统一入口做了越界过滤、`isFinishing/isDestroyed` 守卫和 `mChargeLimitTracking` 拖动保护，避免回调把用户正在拖的滑条抢走。副作用很小：回调频率受信号驱动，且拖动期间被有意忽略。

## 复盘与经验
- "设置类" UI 必须区分"下发"与"反馈"两个信号：本模块设置了下发（Set）但漏了反馈（Feed）监听，是典型的车机信号对接遗漏；对接清单应按"每个 Set 必有对应 Feed 监听"核对。
- 用户正在交互（拖动）时到达的回调会打架，需要 `mChargeLimitTracking` 这类交互状态门闩，否则会出现"滑条自己回跳"的新 bug。
- 页面恢复（onResume）时主动读一次当前值作为兜底，是"信号 + 轮询兜底"双保险的标准做法；读值、回调、验证应收敛到同一个带校验的入口函数。
