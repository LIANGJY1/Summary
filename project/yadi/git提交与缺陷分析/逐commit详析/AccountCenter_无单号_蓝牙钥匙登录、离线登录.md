# Usercenter008/009 · 蓝牙钥匙登录、离线登录

- **提交**：`c824fc01` | 2026-08-28 | liqingqing | AccountCenter | feature
- **关联单**：Usercenter008 / Usercenter009

## 需求/目标
实现"蓝牙钥匙登录"：车端监听数字钥匙信号 `SAM_USERID1`（6122），收到蓝牙钥匙携带的 userId 后自动向 TSP 发起数字钥匙登录（loginType=2），实现用户上车即静默登录（含离线链路基础）。

## 实现结构
10 个文件、+89/-12：核心新增 `service/BluetoothKeyLoginManager.kt`（注意：该文件被 git 判为 binary，疑含 BOM/特殊字符，diff 无法逐行审阅——本身是个流程问题）；`Carlib` 新增信号定义 `SAM_USERID1=6122`（数字钥匙段 6122~6199）并在 `CarPropertyMapping` 注册 ByteArray 接收映射；AccountCenter 侧补齐标准三层链路——`BluetoothKeyLoginRequest`（userId/sn/vin/loginType=2/timestamp/deviceSign）、`ApiService.bluetoothKeyLogin` POST `auth/api/v1/digitalkey/login`、`RequestRepository` RxJava 封装；`BootService.onCreate/onDestroy` 挂载/释放 manager；`build.gradle` 引入 `:component:Carlib`；顺带注释掉氛围灯 6 个信号的映射并把 BASE_URL 注释补 uat 环境。数据流：车信号 SAM_USERID1 → CarPropertyMapping（ByteArray）→ ICarPropertyEventCallback → parseUserId → SignUtil 签名 → RequestRepository → TSP 登录 → LoginStateResponse 更新账号态。

## 关键代码
```diff
--- /dev/null  (component/Carlib/src/main/java/com/neusoft/libcar/map/CarPropertyMapping.kt)
+        //蓝牙钥匙登录用户id
+        CarPropertyIds.SAM_USERID1 to CarPropertyIdWrapper(
+            recId = VehiclePropertyIds.SAM_USERID1,
+            recType = ByteArray::class
+        ),
```
```kotlin
// application/AccountCenter/.../service/BluetoothKeyLoginManager.kt（blob 直读，git 判为 binary）
/** 防重复登录的最小间隔（毫秒） */
private const val MIN_LOGIN_INTERVAL = 10_000L
...
CarPropertyIds.SAM_USERID1 -> {
    // SAM_USERID1 的数据类型为 ByteArray
    val userId = parseUserId(value)
    if (!userId.isNullOrEmpty()) {
        onBluetoothKeyUserIdReceived(userId)
    }
}
```
实现讲解：信号驱动的静默登录——把"用户身份"抽象为一条车控信号（SAM 解析的 userId ByteArray），Manager 用 10s 最小间隔 + lastLoginUserId 去重防止信号抖动导致重复登录；请求体带设备签名 deviceSign 走 TSP 数字钥匙接口。链路完全复用既有 Retrofit/RxJava 三层架构，改动面小而清晰。

## 复盘与要点
- 最大风险点：核心类 `BluetoothKeyLoginManager.kt` 在仓库中是"二进制"状态的文本文件（编码异常），code review 与 grep 都会失效，应修复编码重新入库。
- 信号去重用 (lastLoginUserId, lastLoginTime) 双条件，适合信号重复上报场景，是车信号→网络请求的通用防抖模式。
- 该提交还夹带了两处无关改动（氛围灯映射注释化、BASE_URL 注释更新），违反单一职责，回滚时容易误伤。
