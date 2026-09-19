# SIR-2138 · 反复开关机压测中"车控车设"闪退（档位回调重复触发回Home）

- **提交**：`d2c42986` | 2026-07-08 | ljl | SystemUI(Carlib) | bugfix
- **缺陷库**：等级 A · 频次 偶现-低于10% · 状态 关闭 · 域 车控车设

## 问题
反复开关机压测中，车控车设偶发闪退（等级 A）。表现为 P 档等档位信号回调重复触发"回 Home"逻辑。

## 根因分析
缺陷库 rc："档位注册接收回调未去重"。代码层机制：SystemUI 等业务进程内会创建**多个 `CarServiceManager` 实例**，每个实例 `init()` 都独立调用 `Car.createCar(...)` 并各自挂 `CarServiceLifecycleListener`——连接成功后每个实例都会调 `PropertyManager.onServiceConnected(car)`，底层 `CarPropertyManager` 被反复覆盖、属性回调被**重复注册**。于是同一条档位信号（如 propertyId=4022 的 P 档）从 N 条注册通道各回调一次，上层"P 档回 Home"逻辑被重复执行；开关机压测下连接反复断建，注册泄漏叠加，最终压垮进程。新增的 `CarConnectionManager` KDoc 把这一背景写得非常明确。

## 关键代码修改
改动文件：`component/Carlib/src/main/java/com/neusoft/libcar/manager/CarConnectionManager.kt`（新增 120 行）、`.../manager/CarServiceManager.kt`（-88 行重构）、`.../manager/PropertyManager.kt`、`application/SystemUI/.../digitalkey/DigitalKeyVehicleService.kt`
```kotlin
// --- component/Carlib/src/main/java/com/neusoft/libcar/manager/CarConnectionManager.kt（新增，进程级单例）
object CarConnectionManager {
    private var mCar: Car? = null
    private var mIsConnected = false
    private val mListeners = mutableSetOf<CarServiceManager.CarServiceLifecycleListener>()
    private val mCarServiceLifecycleListener = Car.CarServiceLifecycleListener { car, ready ->
        mCar = car; mIsConnected = ready
        if (ready) PropertyManager.onServiceConnected(car)   // 每进程只初始化一次
        else PropertyManager.onServiceDisconnected()
        mListeners.toList().forEach { l -> try { l.onLifecycleChanged(ready) } catch (e: Exception) { Logcat.e(...) } }
    }
    @Synchronized fun init(context: Context) {
        if (mCar != null || mIsInitializing) return   // 多次调用仅第一次生效
        mIsInitializing = true
        mCar = Car.createCar(context, mHandler, CONNECTION_TIMEOUT_MS, mCarServiceLifecycleListener)
    }
    fun registerListener(listener: ...) { if (mListeners.add(listener)) listener.onLifecycleChanged(mIsConnected) }
}
```
```diff
// --- CarServiceManager.kt：不再各自 createCar，改为向单例注册
-    private val carServiceLifecycleListener = Car.CarServiceLifecycleListener { car, ready ->
-        if (ready) onServiceConnected(car) else { PropertyManager.onServiceDisconnected(); 重试逻辑 }
-    }
     fun init(context: Context, callback: CarServiceLifecycleListener) {
         this.serviceCallback = callback
-        mCar = Car.createCar(context, mHandler, CONNECTION_TIMEOUT_MS, carServiceLifecycleListener)
+        CarConnectionManager.registerListener(connectionCallback)
+        CarConnectionManager.init(context)
     }
```

## 为什么能修复
`Car.createCar()` 从"每实例一次"收敛为"每进程一次"（`@Synchronized init` + `mCar != null || mIsInitializing` 双守卫），`PropertyManager.onServiceConnected` 只执行一次，属性回调不再重复挂载，P 档信号单次到达单次回调，"回 Home"不再被重复触发，闪退根因消除。重构还顺带删除了原每实例的 3 次重连逻辑，由 `Car.createCar` 自带的重连（lifecycle listener ready=false）替代；`registerListener` 立即同步当前连接状态，避免后注册者错过已就绪事件。隐患：`release()` 会清掉所有业务的共享连接，注释已警示"通常不应在单个业务释放时调用"；`mListeners` 为普通 MutableSet，注册/回调跨线程时依赖调用方约束（通知时做了 toList 快照 + try-catch）。

## 复盘与经验
- **系统服务连接必须进程级单例**：`Car.createCar`/`ServiceConnection` 这类资源被多实例重复创建，回调重复注册是必然结果——连接管理（1 次）与业务使用（N 个监听者）要分层。
- **"回调去重"的治本位置在注册端**：与其在各业务里对回调做幂等/去重，不如保证信号源只注册一次。
- 压测（反复开关机）是暴露连接泄漏/重复注册类问题的最佳手段，偶现 A 级问题优先跑压力场景复现。
