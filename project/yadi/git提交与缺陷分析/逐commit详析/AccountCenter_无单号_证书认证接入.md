# 无单号 · [账号中心]证书认证接入

- **提交**：`fc9c99c3` | 2026-09-01 | liqingqing | AccountCenter | feature
- **关联单**：无

## 需求/目标
账号中心与云端通信从默认 TLS 升级为双向证书认证（mTLS）：跨进程从消息中心服务获取设备证书链（根/中间/设备证书 + 设备私钥），装入 AndroidKeyStore，并为 OkHttp 配置双向 SSL，用于扫码登录等接口的安全请求。

## 实现结构
- 新增 `CertificateAuthManager.kt`（317 行，单例）：绑定 `com.yadea.messagecenter.MessageCenterService`（AIDL `ICommonManager`），注册 `KeystoreCallback` 接收 `KeystoreInfo`（四个 PEM 字段）；断连/绑定死亡时 2 秒重连；`init()` 时先尝试从 `/data/vendor/tbox/cert/...` 本地文件直接装载；`createKeyStore()` 清空旧别名后写入 root/intermediate/device 证书与 RSA 私钥；`waitForReady()` 提供 RxJava Completable + 超时；监听列表用 CopyOnWriteArrayList。
- 新增 AIDL 三件套：`ICommonManager.aidl`（注册/反注册 keystore 回调、setSessionId）、`KeystoreCallback.aidl`、`KeystoreInfo.java`（+aidl）。
- `RetrofitManager.kt`：新增 `applyCertificateAuth()`——keystore 就绪时构建 KeyManagerFactory（客户端证书）+ TrustManagerFactory（信任链）+ SSLContext 注入 OkHttpClient；新增 `rebuildInstance()` 供证书晚到时重建 Retrofit；删除 ActivityThread 反射取 Context 的做法。
- `MyApplication.kt` 启动 init；AndroidManifest 声明；`Commons/RequestApi/RequestRepository` 配合调整。

数据流：MessageCenterService（TBOX 证书源）→ AIDL 回调/本地文件 → AndroidKeyStore → CertificateAuthManager.keyStore → RetrofitManager SSL → HTTPS mTLS 请求。

## 关键代码
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/utils/CertificateAuthManager.kt
@@ -0,0 +1,317 @@
+    private fun createKeyStore(info: KeystoreInfo): KeyStore {
+        val androidKeyStore = KeyStore.getInstance("AndroidKeyStore")
+        androidKeyStore.load(null)
+        deleteEntryQuietly(androidKeyStore, appContext.packageName)
+        deleteEntryQuietly(androidKeyStore, ROOT_ALIAS)
+        deleteEntryQuietly(androidKeyStore, INTERMEDIATE_ALIAS)
+        deleteEntryQuietly(androidKeyStore, CLIENT_ALIAS)
+
+        val rootCert = getCertificateFromPem(info.rootCrt)
+        val intermediateCert = getCertificateFromPem(info.intermediateCrt)
+        val deviceCert = getCertificateFromPem(info.deviceCrt)
+        val privateKey = getPrivateKeyFromPem(info.deviceKey)
+
+        androidKeyStore.setCertificateEntry(ROOT_ALIAS, rootCert)
+        androidKeyStore.setCertificateEntry(INTERMEDIATE_ALIAS, intermediateCert)
+        androidKeyStore.setCertificateEntry(appContext.packageName, deviceCert)
+        androidKeyStore.setKeyEntry(CLIENT_ALIAS, privateKey, null, arrayOf(deviceCert, intermediateCert, rootCert))
+        return androidKeyStore
+    }
```
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/utils/RetrofitManager.kt
@@ -72,20 +75,45 @@
+    private fun applyCertificateAuth(builder: OkHttpClient.Builder) {
+        val keyStore = CertificateAuthManager.getKeyStore()
+        if (keyStore == null) {
+            LogUtils.d(TAG, "Certificate keystore is not ready, use default TLS")
+            return
+        }
+        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
+        keyManagerFactory.init(keyStore, null)
+        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
+        trustManagerFactory.init(keyStore)
+        val trustManager = trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
+        ...
+        val sslContext = SSLContext.getInstance("TLS")
+        sslContext.init(keyManagerFactory.keyManagers, arrayOf(trustManager), SecureRandom())
+        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
+    }
```
```diff
--- a/application/AccountCenter/src/main/aidl/com/yadea/commonaidl/ICommonManager.aidl
@@ -0,0 +1,12 @@
+interface ICommonManager {
+    void registerMessageCallback(int processType, MessageCallback callback);
+    void unregisterMessageCallback(int processType, MessageCallback callback);
+    void registerKeystoreInfoCallback(KeystoreCallback callback, String packageName);
+    void unRegisterKeystoreInfoCallback(KeystoreCallback callback, String packageName);
+    void setSessionId(String id);
+}
```

实现讲解：三层降级设计是亮点——先本地证书文件（冷启动无服务也可用）、再跨进程回调（证书轮换实时生效）、`waitForReady` 给业务一个带超时的异步等待点；Retrofit 侧"keystore 为空则回退默认 TLS"，保证证书体系故障不阻断基础功能。证书解析对 X509/RSA 工厂都做了自定义 Provider（NtsPKIApi）兜底，适配车机安全芯片环境。

## 复盘与要点
- "本地文件直读 + 服务回调热更新 + 带超时等待"三通道取证书的模式可直接复用到任何跨进程凭据分发场景。
- `rebuildInstance()` 重建单例 Retrofit 是"凭据晚于首个请求到达"的必要补丁；更优做法是把 Retrofit 构建延迟到 `waitForReady` 之后或使用动态 SSLSocketFactory。
- 风险提示：设备私钥以 PEM 字符串跨进程传输并落盘 `/data/vendor/tbox`，属架构既定方案但安全评审需确认文件权限；`onBindingDied` 重连兜底写得较完备（ce48a575 随后补了退出反注册，说明本提交有生命周期遗漏）。
