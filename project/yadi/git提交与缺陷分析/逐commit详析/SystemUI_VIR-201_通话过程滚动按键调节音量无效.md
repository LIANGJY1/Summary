# VIR-201 · 通话中滚动按键调节音量无效（音量回调注册时机过晚）

- **提交**：`fa2e1328` | 2026-07-21 | ljl | SystemUI | bugfix
- **缺陷库**：未关联单号（缺陷库无记录）

## 问题
通话过程中滚动物理按键/滚轮调节音量无效，SystemUI 不响应、音量 OSD 不弹出。

## 根因分析
SystemUI 的音量链路依赖 `CarAudioVolumeController` 注册的 `CarVolumeCallback`（回调 `onGroupVolumeChanged` 后弹 OSD/更新音量）。原实现存在两个问题：一是该类是普通类、各使用方（`VolumeDialogActor` 的 `mAudioVolumeController`、`PlatformNotifierImpl` 的 `volumeController`）各自 `CarAudioVolumeController(context)` new 一个实例，回调可能被多处注册、且都处于"懒注册"状态；二是注册发生在第一次使用时——开机后如果没有任何音量操作触发过它，Car 服务就绪后回调一直没注册，通话中滚动按键时 `onGroupVolumeChanged` 根本不会送达 SystemUI。提交说明点明："音量回调注册时机不正确"。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/SystemUIApplication.kt；application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/notifier/PlatformNotifierImpl.kt；application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/CarAudioVolumeController.kt；application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/VolumeDialogActor.kt
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/SystemUIApplication.kt
@@ Car 服务就绪回调
                         LogUtils.d(TAG, "Car service ready: $isReady")
                         if (isReady) {
                             try {
+                                // 提前注册 CarVolumeCallback，确保开机后按键/滚轮调节音量
+                                // 能立即收到 onGroupVolumeChanged 并弹出音量 OSD
+                                CarAudioVolumeController.getInstance(applicationContext).init()
--- a/application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/CarAudioVolumeController.kt
-class CarAudioVolumeController(private val context: Context) {
+class CarAudioVolumeController private constructor(private val context: Context) {
+        @Volatile
+        private var instance: CarAudioVolumeController? = null
+        private const val INIT_RETRY_MAX = 20
+        private const val INIT_RETRY_DELAY_MS = 500L
+        fun getInstance(context: Context): CarAudioVolumeController {
+            return instance ?: synchronized(this) {
+                instance ?: CarAudioVolumeController(context.applicationContext).also { instance = it }
+            }
+        }
+    fun init() {
+        if (getCarAudioManager() != null) { initRetryCount = 0; return }
+        if (initRetryCount++ < INIT_RETRY_MAX) {
+            initRetryHandler.postDelayed({ init() }, INIT_RETRY_DELAY_MS)
+        } else { /* fallback to lazy registration */ }
+    }
--- a/application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/VolumeDialogActor.kt
-    private val mAudioVolumeController by lazy { CarAudioVolumeController(mContext) }
+    private val mAudioVolumeController by lazy { CarAudioVolumeController.getInstance(mContext) }
```

## 为什么能修复
三处改动形成闭环：① 私有构造 + 双检锁单例，保证全 SystemUI 只有一个 `CarAudioVolumeController`、`CarVolumeCallback` 只注册一次；② 在 `SystemUIApplication` 收到 Car 服务就绪（`isReady=true`）时立即调 `init()` 提前拿 `CarAudioManager` 并注册回调，把注册时机从"第一次用音量"提前到"开机服务就绪"；③ 就绪瞬间 `getCarManager` 可能仍返回 null，用 500ms×20 次重试兜底，失败再退回懒注册。通话中滚动按键于是必然有已注册的回调接收 `onGroupVolumeChanged`。风险点是 10 秒重试上限内若 Car 服务一直不可用仍会退回旧问题，但有日志可查。

## 复盘与经验
- 系统回调的注册时机要与"事件可能发生的最早时刻"对齐：车机开机后用户随时可能按音量键，注册就不能晚于 Car 服务就绪。
- 带注册副作用的组件应做成显式单例，多处 `lazy { Xxx(context) }` 会造成回调重复注册、状态分裂等隐蔽问题。
- 对"依赖另一服务就绪"的初始化，写"就绪通知 + 有限次重试 + 懒注册兜底"三层防线，比单点依赖就绪广播更稳。
