# 无单号 · 3D车模渲染优化：按挡位/前后台启停渲染

- **提交**：`b145f346` | 2026-07-17 | liang-jy | Launcher | feature
- **关联单**：无

## 需求/目标
为 Kanzi 3D 车模增加"停止/启动渲染"接口：D 挡、R 挡或 Launcher 退后台时停止 3D 渲染以省 GPU/CPU，P 挡且回到前台时恢复渲染。

## 实现结构
3 个文件（+82）：
- `control/KanziSignalMapping.java`：新增 `OnGearChangeListener` 接口与 setter，在原有挡位信号处理（发 `CarModel.GEAR` 给 Kanzi）之后把原始挡位值回调出去；
- `control/KanziDataSourceManager.java`：新增状态对 `mIsForeground`/`mCurrentGear`，构造时把自己注册为挡位回调；新增 `setForegroundState()`（由 MainActivity 生命周期驱动）、`handleGearChangeForRendering()`、核心仲裁 `checkAndPerformRenderState()`，以及薄封装 `setRenderStart()/setRenderStop()`（判空 `isKanziConnected && kanziManager` 后调 kanziManager）；
- `function/main/view/MainActivity.kt`：`onResume/onPause` 中调用 `setForegroundState(true/false)`。

数据流：挡位信号 → KanziSignalMapping → 回调 → KanziDataSourceManager 仲裁（前台？挡位？）→ kanziManager.setRenderStart/Stop；生命周期事件直接进同一仲裁入口。初始化时还会主动读 `ENERGY_PCU_ACTUALGEAR` 做初始状态，避免错过启动前的挡位。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java
+    /**
+     * 核心仲裁逻辑：根据前后台状态和档位状态，决定是否暂停/恢复渲染
+     */
+    private void checkAndPerformRenderState() {
+        // 1. 如果在后台，或者档位是 D档(1) / R档(2)，则停止渲染
+        if (!mIsForeground || mCurrentGear == 1 || mCurrentGear == 2) {
+            setRenderStop();
+        } else if (mCurrentGear == 0) { // 2. 如果在前台，且档位是 P档(0)，则恢复渲染
+            setRenderStart();
+        }
+    }
```
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
                 sendToKanzi(KanziType.CarModel.GEAR, gear);
             }, gear == 1 ? 0 : 500);
+
+            // 通过回调将档位状态同步给外部监听者
+            if (mGearChangeListener != null) {
+                mGearChangeListener.onGearChange((int) event.getValue());
+            }
         });
```
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/function/main/view/MainActivity.kt
         mKzManager?.onResume()
+        kanziDataSourceManager?.setForegroundState(true)
     }
     override fun onPause() {
         super.onPause()
+        kanziDataSourceManager?.setForegroundState(false)
         mKzManager?.onPause()
```
实现讲解：典型的"多输入源 + 单一仲裁函数"设计——前台/后台与挡位两个独立事件都只更新状态变量再统一 `checkAndPerformRenderState()`，避免两路事件各自直接控制渲染造成状态竞争。渲染接口做成空安全的薄封装，未连接 Kanzi 时静默跳过。

## 复盘与要点
- 可复用模式：两个状态位 + 一个仲裁函数，比在两个事件源里写 if-else 组合清晰得多，后续加"低电量暂停"之类条件只需改仲裁函数。
- 细节亮点：初始化时"主动拉一次当前挡位"消除了注册时机造成的初始状态盲区；但挡位回调挂在原有 `sendToKanzi` 的延迟投递路径上，依赖该路径必达。
- 遗留风险：`checkAndPerformRenderState` 在 `mCurrentGear` 为非 0/1/2（如未知值）时既不 stop 也不 start，依赖默认挡位恒为 0(P)；重复调用 `setRenderStop` 未做去重，Kanzi 侧需幂等。
