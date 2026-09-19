# 无单号 · 主交互切换去掉拉起导航逻辑（职责移交导航中间件）

- **提交**：`f9b58932` | 2026-07-07 | ljl | SystemUI | feature
- **关联单**：无

## 需求/目标
主交互（仪表/页面状态机）切换状态时不再由 SystemUI 拉起导航应用，该职责移交给导航中间件实现；本提交以一行默认值翻转落地。

## 实现结构
- 修改 `digitalkey/mainaction/PageStateMachine.kt`：`isNeedStartNavi` 默认值 `true` → `false`（1 行）。

`PageStateMachine` 是数字钥匙/主交互的页面状态机（`State.S0_Android_Park` 等状态 + `DataContext` + `PlatformNotifier` 通知），`isNeedStartNavi` 控制状态切换时是否附带"拉起导航"动作。默认值翻转后，除非运行时显式置真，SystemUI 不再主动拉导航。

## 关键代码
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/PageStateMachine.kt
@@ -28,7 +28,7 @@ object PageStateMachine {
     private lateinit var notifier: PlatformNotifier
     private lateinit var appContext: Context
     private var initialized = false
-    private var isNeedStartNavi = true //是否需要拉起导航操作
+    private var isNeedStartNavi = false //是否需要拉起导航操作
```

实现讲解：这是典型的"职责边界收敛"提交——拉起导航属于导航域的业务编排，SystemUI 作为系统 UI 层只应响应状态、渲染界面；由导航中间件监听主交互状态自行决定拉起，SystemUI 退到纯状态广播角色。用开关变量而非直接删代码，保留了回退空间（运行时可再打开），属于低风险的能力下线方式。

## 复盘与要点
- 系统应用层与中间件的职责划分原则：谁拥有业务语义，谁负责触发。SystemUI 之前"顺手拉导航"是越界行为，切换时序变化时容易双拉或漏拉。
- 用默认值开关下线行为便于灰度与回退，但注释与提交信息必须同步说明"为什么"（本提交标题已写清"由导航中间件实现"，可追溯性尚可）。
- 影响等级 C、测试范围"主交互仪表切换"定位准确：一行改动也可能改变核心交互链路，改动小不等于测试轻。
