# 无单号 · 隐私协议接口请求报401
- **提交**：`b0994ec2` | 2026-07-16 | hedeyuan | AccountCenter | bugfix
- **缺陷库**：未关联单号

## 问题
账号中心"获取最新启用协议"（隐私协议）接口请求返回 401。

## 根因分析
`RetrofitManager` 的全局 `headerInterceptor` 对**所有**请求无差别追加 `Authorization: Bearer $accessToken`，未登录时还兜底塞入硬编码的占位 token `"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"`。而"获取最新协议" `getLatestTerms` 是登录前就要调用的匿名接口，服务端校验到无效/匿名 token 直接回 401。根因是拦截器"一刀切加认证头"，缺少匿名接口的豁免机制——接口的认证属性分散在服务端定义，客户端却没有对应的声明位。

## 关键代码修改
改动文件：application/AccountCenter/src/main/java/com/yadea/accountcenter/data/api/ApiService.kt（+2）、application/AccountCenter/src/main/java/com/yadea/accountcenter/utils/RetrofitManager.kt（+12/-5）；另有 3 个布局/drawable 的背景资源统一（新增 bg_main.xml、三处 layout 背景收敛），与本 bug 无关。
```diff
--- application/AccountCenter/src/main/java/com/yadea/accountcenter/data/api/ApiService.kt
+    @Headers("X-No-Auth: true")
     @GET(Commons.API_GET_LATEST_TERMS)
     fun getLatestTerms(...): Observable<BaseResponse<AgreementResponse?>>
--- application/AccountCenter/src/main/java/com/yadea/accountcenter/utils/RetrofitManager.kt
         val headerInterceptor = okhttp3.Interceptor { chain ->
+            val originalRequest = chain.request()
+            val isNoAuth = originalRequest.header("X-No-Auth") != null
             val accessToken = SettingsUtils.getGSetting(...ACCESS_TOKEN) ?: "eyJ..."
-            val requestWithHeader = originalRequest.newBuilder()
+            val requestBuilder = originalRequest.newBuilder()
                 .header("Content-Type", "application/json")
                 .header("X-Sequence-No", UUID.randomUUID().toString().replace("-", ""))
                 .header("X-Timestamp", System.currentTimeMillis().toString())
-                .header("Authorization", "Bearer $accessToken")
-                .build()
-            chain.proceed(requestWithHeader)
+                .removeHeader("X-No-Auth")  // 移除标记Header，不发送到服务端
+            if (!isNoAuth) {
+                requestBuilder.header("Authorization", "Bearer $accessToken")
+            }
+            chain.proceed(requestBuilder.build())
```

## 为什么能修复
匿名接口用 `@Headers("X-No-Auth: true")` 声明豁免，拦截器检测到标记即不追加 `Authorization`，并在发出前 `removeHeader("X-No-Auth")` 清掉内部标记，服务端收到的是无认证头的纯匿名请求，401 消除。机制可复用到后续所有匿名接口。隐患：① 兜底 token 是硬编码 JWT 头片段，一旦走 `?: "eyJ..."` 分支就是无效凭证，属于应删除的危险默认值（本次未动）；② `X-No-Auth` 是自定义内部约定，若服务端未来校验未知头需注意已被移除，无泄漏风险。

## 复盘与经验
- **认证策略应声明在接口定义处**：哪个接口匿名、哪个要鉴权，用注解（`X-No-Auth` 或更规范的 `@Auth(optional=true)`）在 ApiService 上标明，拦截器只执行策略，而不是全量加头再打补丁。
- **全局拦截器改动影响面=所有接口**：给拦截器加逻辑时先枚举"是否有不该带凭证的调用方"，登录前接口是最常见的反例。
- **硬编码兜底凭证是隐患放大器**：本次 401 正是"未登录 + 兜底无效 token"组合暴露的；无 token 就不发认证头，比发一个必然无效的假 token 更符合语义。
