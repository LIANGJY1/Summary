# 无单号 · 添加网络工具类（CommonTools NetworkUtils.isAvailable）

- **提交**：`3660a69c` | 2026-07-03 | dufan | CommonTools | feature
- **关联单**：无

## 需求/目标
在公共工具库新增网络可用性判断工具 `NetworkUtils`，供互联（HiCar 网络共享等）相关功能判断当前网络是否可用。

## 实现结构
新增 `component/CommonTools/src/main/java/com/yadea/common/utils/NetworkUtils.kt`（25 行）：companion object 提供 `isAvailable(context)`，通过 `ConnectivityManager.getActiveNetworkInfo().isAvailable()` 判断；context 为 null 或无 NetworkInfo 时返回 false。

无其他文件改动，属于纯工具沉淀。

## 关键代码
```diff
--- /dev/null
+++ b/component/CommonTools/src/main/java/com/yadea/common/utils/NetworkUtils.kt
@@ -0,0 +1,25 @@
+class NetworkUtils {
+    companion object {
+        @SuppressLint("MissingPermission")
+        fun isAvailable(context: Context?): Boolean {
+            if (context != null) {
+                val connectivityManager =
+                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
+                val mNetworkInfo = connectivityManager.getActiveNetworkInfo()
+                if (mNetworkInfo != null) {
+                    return mNetworkInfo.isAvailable()
+                }
+            }
+            return false
+        }
+    }
+}
```

实现讲解：单方法静态工具类，`@SuppressLint("MissingPermission")` 压掉 `ACCESS_NETWORK_STATE` 权限告警（依赖 Manifest 全局声明）。判空兜底到 `false` 的语义偏保守——网络异常时按"不可用"处理，适合互联连接前置校验场景。

## 复盘与要点
- `getActiveNetworkInfo()` 在 API 29 起已废弃，规范做法是 `NetworkCapabilities`；车机项目 targetSdk 持续升级时这类工具类要跟版迭代。
- 结合同日 HiCar 网络共享状态提交看，这是为"共享网络前先确认车机联网"铺路的公共依赖。
- 类内只用 companion object 却保留可实例化的 `class`，改成 `object` 单例更符合 Kotlin 惯例（与本批次 `b02aee28` 的整改方向一致）。
