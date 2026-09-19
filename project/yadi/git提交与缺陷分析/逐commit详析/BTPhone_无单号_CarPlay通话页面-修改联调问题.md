# 无单号 · CarPlay 通话页面联调修复（实车 NoClassDefFoundError 崩溃治理 + 会话状态时序）

- **提交**：`94ccc07e` | 2026-07-29 | ljl | BTPhone | feature（**实为 a3bb821d 的联调缺陷修复**）
- **关联单**：无

## 问题
CarPlay 通话弹窗在无 `ts.platform.library` 共享库的实车上出现崩溃循环：`CarPlayCallManager` 字段初始化器中 `new IServiceConnectionListener(){...}` 匿名类，在类缺失时于**构造器内**抛 `NoClassDefFoundError`，外层任何 try/catch 都接不住（Error 在字段初始化阶段）。另有通话监听注册时机过早、开机自启缺失等联调问题。

## 根因分析
1. ART 对字段初始化器中的匿名类实例化会立即触发 ts 接口类加载，发生在单例构造函数内，且发生在 `BtPhoneApp.onCreate` 的调用栈里 → 应用启动即崩、循环重启。
2. 初版在 `onServiceConnected` 就注册 `ICallStateListener`，但 CP 会话未 `SESSION_STATUS_ACTIVATED` 时状态回调不可靠（按 CP 供应商指正：投屏/会话监听可早注册，通话监听必须等会话激活）。
3. 车机重启后 `InCallServiceImpl` 前台服务与默认拨号器未恢复，CarPlay 通话监听不生效。

## 关键代码修改
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallManager.java
@@ -44,6 +51,17 @@
+    /** init 时探测的 SDK 类清单，全部存在才允许绑定 */
+    private static final String[] SDK_CLASSES = {
+            "com.ts.car.core.ServiceProvider",
+            "ts.car.service.IServiceConnectionListener",
+            "ts.car.service.carplay.ICarPlayAppManager",
+            "ts.car.service.carplay.ICallStateListener",
+            "ts.car.service.carplay.ICarPlayAppListener",
+            "ts.car.service.carplay.ICarPlayStateListener",
+            "ts.car.service.carplay.data.CallStateInfo",
+    };
@@ -91,14 +122,45 @@
+        mSdkAvailable = probeSdkAvailable();
+        if (!mSdkAvailable) {
+            LogUtils.e(TAG, "init: CarPlay SDK classes unavailable, skip service binding");
+            return;
+        }
+        bindCarPlayService();
+
+    /** Class.forName 逐个探测 SDK 类，log 出缺失的类名 */
+    private static boolean probeSdkAvailable() {
+        for (String className : SDK_CLASSES) {
+            try {
+                Class.forName(className);
+            } catch (Throwable t) {
+                LogUtils.e(TAG, "CarPlay SDK class missing: " + className + " (" + t + ")");
+                return false;
+            }
+        }
+        return true;
+    }
```
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallManager.java
@@ -73,17 +107,14 @@
-    private final IServiceConnectionListener mConnectionListener = new IServiceConnectionListener() { ... };
+    /**
+     * ts 接口监听器：只声明、不初始化。
+     * 字段初始化器中 new 匿名类会在类缺失时于构造器内 NoClassDefFoundError（实车已复现崩溃循环），
+     * 必须在使用处 try/catch 内懒创建。
+     */
+    private IServiceConnectionListener mConnectionListener;
+    private ICallStateListener mCallStateListener;
+    private ICarPlayAppListener mCarPlayAppListener;
+    private ICarPlayStateListener mCarPlayStateListener;
+    /** ICallStateListener 是否已注册（延迟到 SESSION_STATUS_ACTIVATED 后注册，防重复） */
+    private boolean mCallListenerRegistered = false;
```
同时：`BtPhoneApp.onCreate` 对 `init()` 再包一层 try/catch 双保险；`BootReceiver` 启动前台服务/设置默认拨号器加 try/catch 并注册 BOOT_COMPLETED；manifest 补 `car.permission.CARPLAY`、`CARPLAY_DEVICE_LIST` 权限并同步 privapp 白名单；`uses-library` 由 required=false 改为必选（实车确认均有该库）；删除供应商指正不需要的 `ICommunicationsListener`。

## 为什么能修复
- `Class.forName` 预探测在实例化任何 ts 类之前进行，类缺失直接跳过绑定，从根上避开"构造器内 NoClassDefFoundError 不可捕获"的问题；监听器全部改为使用处 try/catch 内懒创建，字段/方法签名上的 ts 类型仅是声明（ART 懒解析，安全）。
- 通话监听延迟到 `SESSION_STATUS_ACTIVATED`（先查 `getCarPlaySessionStatus()` 补偿错过的回调，`mCallListenerRegistered` 防重复），DEACTIVATED/CONNECT_FAILED 反注册并清空通话列表，状态机闭环。
- BootReceiver + 白名单权限保证重启后监听链路自动恢复。

## 复盘与经验
- compileOnly 可选依赖的头号大坑：**匿名监听器绝不能放字段初始化器**，必须"null 声明 + 使用处 try/catch 内懒创建 + 入口 Class.forName 预探测"三件套；这条经验适用于所有依赖平台共享库的车机模块。
- 供应商 SDK 的监听注册有时序契约（会话激活前后分批注册），接入前要拿到状态机文档，并用"连接成功后主动查询一次当前状态"补偿竞态。
- 联调类修复一次收敛了崩溃、时序、权限、自启四类问题，diff 里保留了大段"教训注释"，是极好的防回归文档。
