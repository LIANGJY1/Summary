# 无单号 · 优化项目依赖（BTMusic 摘除 LinkApi/Applib 与启动期 HiCar 清理逻辑）

- **提交**：`1ce08a1d` | 2026-07-03 | dufan | BTMusic | feature
- **关联单**：无

## 需求/目标
BTMusic 模块依赖瘦身：删除 LinkApi、Applib 两个旧组件依赖及 8 个未使用的三方库，并同步删除 `App` 启动期依赖 neusoft `HiCarManager` 的清理代码。

## 实现结构
- 修改 `application/BTMusic/build.gradle`（-17 行）：删除 `libs.recyclerview`、`activity.ktx`、`core.ktx`、`blurview`、`juniversalchardet`、`jaudiotagger`、`exoplayer-core`、`eventbus` 及 `project(':component:LinkApi')`、`project(':component:Applib')`；删除块状 `dataBinding { enabled }`（由统一 buildFeatures 管理）。
- 修改 `application/BTMusic/.../App.kt`：删除 `init()` 方法——其中通过 neusoft `HiCarManager` 在启动时关闭 HiCar、反注册三类监听的逻辑，以及悬空引用 `BluetoothController` 的占位语句。
- 修改 `component/CommonTools/.../view_state_loading_button.xml`：颜色资源微调（顺带带入）。

数据流变化：BTMusic 启动时不再主动复位 HiCar 服务连接；HiCar 生命周期改由 Launcher 的 DeviceConnectManager 统一管理（与本批次互联系列提交呼应）。

## 关键代码
```diff
--- a/application/BTMusic/src/main/java/com/yadea/btmusic/App.kt
@@ -2,8 +2,6 @@
-import com.neusoft.link.lib.hicar.HiCarManager
-import com.yadea.btmusic.manager.BluetoothController
@@ -11,22 +9,9 @@
-    private fun init() {
-        BluetoothController
-
-        if (HiCarManager.getInstance().isServiceConnected()) {
-            HiCarManager.getInstance().closeHiCar()
-            HiCarManager.getInstance().unRegisterIHiCarServiceConnectListener()
-            HiCarManager.getInstance().unRegisterHiCarCallOrHandUpListener()
-            HiCarManager.getInstance().unRegisterConnectedStateListener()
-        }
-    }
```

```diff
--- a/application/BTMusic/build.gradle
@@ -63,30 +63,16 @@
-    implementation libs.blurview
-    implementation libs.juniversalchardet
-    implementation libs.jaudiotagger
-    implementation libs.exoplayer.core
-    implementation libs.eventbus
-    implementation project(':component:LinkApi')
-    implementation project(':component:Applib')
```

实现讲解：删依赖必须连带删调用点，因此 `App.init()` 里唯一的 neusoft HiCarManager 使用被一并移除——这段"启动时若 HiCar 服务连着就强关并反注册"的逻辑属于多应用各自为政时期的补丁，互联统一到 Launcher 管理后即为冗余甚至有害（可能误关其他应用建立的 HiCar 连接）。`BluetoothController` 单独一行是仅为触发类加载的副作用写法，删除不影响功能。

## 复盘与要点
- 依赖清理的正确闭环：依赖 → 调用点 → 行为归属一起评估。本例中删除的不只是 import，还有一段启动期行为，这种"依赖治理顺手改变运行时行为"的提交要在复盘时特别标注。
- exoplayer/eventbus/jaudiotagger 等库的删除说明 BTusic 的媒体管线早已不用它们，定期用 `./gradlew dependencies` 或 lint 未使用依赖检查可以持续发现这类"僵尸库"。
- 改 BTMusic 提交顺带改 CommonTools 布局文件，跨模块小改动混入仍是提交卫生问题。
