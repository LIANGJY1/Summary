# 无单号 · 优化项目依赖（AccountCenter 摘除 Applib，切换公共 NetworkUtils）

- **提交**：`e16eaaa9` | 2026-07-03 | dufan | AccountCenter | feature
- **关联单**：无

## 需求/目标
让账号中心模块摆脱对旧 `component:Applib`（东软 neusoft 遗留库）的依赖：网络判断从 `com.neusoft.applib.utils.NetworkUtils` 切换到上一步刚沉淀的公共 `com.yadea.common.utils.NetworkUtils`，并规整 `build.gradle` 的 buildFeatures 配置。

## 实现结构
- 修改 `application/AccountCenter/build.gradle`：删除 `implementation project(':component:Applib')`；把散落在 defaultConfig/android 各处的 `dataBinding { enabled }` / `buildFeatures { viewBinding }` 合并为一个 `buildFeatures` 块（补 `aidl`、`buildConfig`）。
- 修改 `ui/login/LoginActivity.kt`、`ui/center/CenterActivity.kt`：import 从 neusoft NetworkUtils/无用项切换或清理为 `com.yadea.common.utils.NetworkUtils`，删除 `JWTUtils`、`SPUtils`、`LoginResponse` 等无用 import。

数据流不变：登录/中心页里"网络不可用"判断的底层实现从东软工具换成公共工具，调用点语义一致（返回 Boolean）。

## 关键代码
```diff
--- a/application/AccountCenter/build.gradle
@@ -57,15 +56,6 @@
-    dataBinding {
-        enabled true
-    }
-
-    buildFeatures {
-        viewBinding true
-        dataBinding true
-    }
+    （上移合并为 android{} 顶部的统一 buildFeatures 块）
@@ -106,5 +96,5 @@
     implementation project(':component:CommonTools')
-    implementation project(':component:Applib')
 }
```

```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginActivity.kt
@@ -2,18 +2,13 @@
-import com.neusoft.applib.utils.NetworkUtils
+import com.yadea.common.utils.NetworkUtils
-import com.yadea.common.utils.JWTUtils
-import com.yadea.common.utils.SPUtils
```

实现讲解：典型的"先沉淀替代品、再摘除旧依赖"两步走——5 分钟前的 `3660a69c` 在 CommonTools 新建 NetworkUtils，本提交立即切引用并断开 Applib，每一步都可独立编译验证。buildFeatures 合并是 AGP 8.x 的强制性要求（buildConfig/dataBinding 必须显式声明），顺带完成规整。

## 复盘与要点
- 历史多厂商代码（neusoft/skyworthauto）逐步替换为自研 `com.yadea.common` 体系的路径：新建对等工具 → 批量切 import → 删依赖 → 删组件（参见 `23957a5f` 删 ihuadapter），节奏清晰可复制。
- 一次性补齐 `aidl true`、`buildConfig true` 防止 AGP 升级后编译报错，属于升版伴生改动。
- 风险点：两个 NetworkUtils 若行为有细微差异（如 VPN/局域网判断），登录页网络校验结果可能变化，需在切换时做语义 diff。
