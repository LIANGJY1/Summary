# 无单号 · [SIR-XXX] 优化手车互联 PIN 码界面来回切换
- **提交**：`73ce61ae` | 2026-09-17 | dufan | Launcher | feature（行为优化/缺陷修复性质）
- **关联单**：无（SIR-XXX 占位单号）

## 需求/目标
修复手车互联 PIN 码界面（LinkActivity）"来回切换"（应用列表反复进入/已被打开时再次进入）时状态错乱的问题：原来 `mIsCarLink`/`mConnectFailType` 用 `by lazy` 只在首次创建时读 Intent，Activity 复用（singleTask/onNewIntent）后新参数被丢弃，界面显示与实际场景不符。

## 实现结构
- `function/link/LinkActivity.kt`（+33/-24）：
  - `mIsCarLink`、`mConnectFailType` 从 `by lazy` 的不可变字段改为 `var`，可随新 Intent 刷新；
  - 把 `initView` 里的场景初始化逻辑（蓝牙未开则 `enable()`、已配对设备反射 `disconnect`、失败页/正常页可见性切换）抽取为 `setInterface()`；
  - 新增 `onNewIntent()`：`setIntent(intent)` 后重跑 `setInterface()`，复用实例时按最新参数重建界面状态；
  - 前台状态上报从 `onStop` 挪回 `onPause`（`setFusionUiForegroundState`）。
- `function/applist/CarConnectFragment.kt`（+1）：启动 LinkActivity 的 Intent 加 `FLAG_ACTIVITY_NEW_TASK`，保证从 Fragment 上下文能正确拉起并可走 `onNewIntent` 通道。

## 关键代码
```kotlin
// application/Launcher/src/main/java/com/yadea/launcher/function/link/LinkActivity.kt
-    private val mIsCarLink by lazy { intent.getBooleanExtra("is_car_link", false) }
-    private val mConnectFailType by lazy { intent.getIntExtra("connect_fail_type", 0) }
+    private var mIsCarLink = false
+    private var mConnectFailType = 0
...
+    override fun onNewIntent(intent: Intent?) {
+        super.onNewIntent(intent)
+        setIntent(intent)
+        setInterface()
+    }
+
+    @SuppressLint("MissingPermission")
+    private fun setInterface(){
+        mIsCarLink = intent.getBooleanExtra("is_car_link", false)
+        mConnectFailType = intent.getIntExtra("connect_fail_type", 0)
+        mIsConnectFail = mConnectFailType != 0
+        if (mIsConnectFail) {
+            setViewVisibility(mConnectFailType)
+        } else { /* 蓝牙开启检查 + 已配对设备断开 + 正常页显示 */ }
+    }
```

实现讲解：核心是让 Activity 对"带新参数的二次启动"具备自愈能力——`onNewIntent` 中先 `setIntent` 再重跑初始化，参数从"实例生命周期只读一次"变为"每次进入都刷新"。配合 `FLAG_ACTIVITY_NEW_TASK`，从应用列表反复点入时走复用+刷新通道而不是堆叠新实例，来回切换不再出现旧参数残留。

## 复盘与要点
- **`by lazy` 读 Intent 是 Activity 复用场景的经典坑**：凡可能被 `onNewIntent` 复用（launchMode/NEW_TASK）的页面，参数一律在 `onCreate`+`onNewIntent` 双入口刷新；本提交是该坑的标准修复范式，可直接套用。
- **初始化逻辑抽成幂等函数**：`setInterface()` 同时被 `initView` 与 `onNewIntent` 调用，"首次进入"与"再次进入"共享同一条状态重建路径，避免两份逻辑漂移。
- **遗留风险**：`BluetoothDevice::class.java.getMethod("disconnect")` 反射调用非公开 API，且从 `onStop` 挪回 `onPause` 后该重活在每次离开页面都会执行（`onPause` 触发更频繁），快速来回切换时的蓝牙操作频率上升，需关注卡顿与栈内状态。
