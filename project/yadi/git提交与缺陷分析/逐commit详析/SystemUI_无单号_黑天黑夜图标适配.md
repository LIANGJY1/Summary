# [SIR-XXXX] · 黑天黑夜图标适配（Launcher/Kanzi 侧主题适配 + 信号映射重构）

- **提交**：`54d746cf` | 2026-06-29 | liujinfeng | SystemUI（实际改动主体在 Launcher） | feature
- **关联单**：SIR-XXXX 占位号，无具体单号

> 大型混合提交：标注 SystemUI，实际核心代码全在 Launcher（Kanzi 3D 车模）模块，且携带大量 `build/` 产物进入版本库。按大型首期开发提交处理，讲清结构不逐文件剖析。

## 需求/目标
白天/黑夜模式主题适配在 Launcher Kanzi 侧的落地：新增 Kanzi 信号类型常量与处理器注册表、五指抓取车模进出动画，以及 keyguard 相关布局/Binding 的黑白资源更新。

## 实现结构
- `manager/KanziType.java`（新增，203 行）：按 `组名.属性名` 格式（对应 Kanzi javaIF.xml）整理的常量类，内含 `CarModel`、`Light` 等嵌套类，替代散落的魔法字符串。
- `control/KanziSignalMapping.java`（±732 行）：车灯/充电/转向等 CAN 信号 → Kanzi 属性的映射重构——构造期把 `CarPropertyIds → Consumer<AppCarPropertyValue<?>>` 注册进 `HashMap signalHandlers`，替代原先长 if-else；充电电流/电压字段 int→float；`VehicleService.getInstance` 改为 `BaseManager.getInstance`。
- `function/main/view/MainActivity.kt`：`setOnKanziLoadCompleteListener` 从 lambda 改为显式 object 并新增 `onFiveFingerCapture(enter)` 回调，五指抓取时对 `kanziSurface` 播放 `res/anim/car_model_enter/exit.xml` 动画。
- `control/KanziDataSourceManager.java`（±370 行）：配合新增回调接口与数据源调整。
- SystemUI 侧仅 `ActivityKeyguardSettingsBinding`、`ActorKeyguardBinding` 等 ViewBinding 产物和 layout 微调；其余 90% stat 是 `build/` 产物（dex、prof、R.jar、zip-cache）。

数据流：CAN 信号 → VehicleService → KanziSignalMapping.signalHandlers 分发 → kanziManager.setValue(KanziType.XXX) → Kanzi 3D 渲染；uiMode 切换由 drawable-night/colors 资源自动生效。

## 关键代码
```diff
# application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
+    private final Map<Integer, Consumer<AppCarPropertyValue<?>>> signalHandlers = new HashMap<>();
+
+    private void initSignalHandlers() {
+        signalHandlers.put(CarPropertyIds.CCU_SETHANDLEHEATSWREQ, event -> {
+            mHandleHeatLevel = (int) event.getValue();
+            updateHandlebarStateToKanzi();
+        });
+        signalHandlers.put(CarPropertyIds.THREE_D_MODEL_LCM_HIGHBEAMSTS, event -> {
+            int highBeam = (int) event.getValue();
+            kanziManager.setValue("", KanziType.Light.FAR_BEAM, highBeam);
+            kanziManager.setValue("", KanziType.CarModel.SMARTLIGHT_STATE, highBeam);
+        });
+        signalHandlers.put(CarPropertyIds.THREE_D_MODEL_LCM_TURNINGLIGHTSTS_LEFT, this::handleLeftTurnSignal);
+        ...
+    }
```
```diff
# application/Launcher/src/main/java/com/yadea/launcher/function/main/view/MainActivity.kt
+                override fun onFiveFingerCapture(enter: Int) {
+                    runOnUiThread({
+                        if (enter == 1) {
+                            enterVehicleModelAnimation()
+                        } else {
+                            exitVehicleModelAnimation()
+                        }
+                    })
+                }
...
+    private fun enterVehicleModelAnimation() {
+        val enterAnim = AnimationUtils.loadAnimation(this, R.anim.car_model_enter)
+        mBinding.kanziSurface.startAnimation(enterAnim)
+    }
```

实现讲解：信号分发从"每来一个属性值走一长串 if (propId == X)"改成构造期建表、回调期 `signalHandlers[propId]?.accept(event)` 一次命中，新增信号只需注册一条，圈复杂度显著下降。KanziType 常量类把 Kanzi 侧属性 key 收敛到一处，与 javaIF.xml 形成对照文档。

## 复盘与要点
- 可复用手法：`Map<信号ID, Consumer<信号值>>` 注册表是车载信号转发的通用模式，比 if-else 链条可测试、可扩展。
- 风险：本提交把 `Launcher/build` 下的 dex/R.jar/baseline.prof 全部提交入库（几十 MB），后续每次构建都会产生噪音 diff；与 f3f3d60d 补的 `.gitignore` 形成对照——ignore 规则应先行。
- `KanziSignalMapping` 同一提交内混入重构（建表）与适配（night 资源），标题却只写"图标适配"，回溯定位成本高，建议拆分。
