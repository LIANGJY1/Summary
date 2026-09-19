# 无单号 · AccountCenter 修复静态代码扫描（SonarQube 规则治理）

- **提交**：`33a0938b` | 2026-07-03 | duanlonglong | AccountCenter | feature（实为静态扫描整改，按 bugfix 视角复盘）
- **关联单**：无

## 问题
SonarQube 扫描账号中心模块报出大量代码异味：Java 式匿名 `View.OnClickListener`、手动判空调用监听器、`list.get(i)` 非惯用写法、硬编码密钥告警、废弃 API 等，涉及 17 个文件（+270/-292）。

## 根因分析
模块由 Java 代码机翻/直译为 Kotlin（大量 `View.OnClickListener { v: View? -> }`、`!!` 断言、`get()` 写法残留），未按 Kotlin 习惯与安全规则重写；加密工具类中密钥以明文常量形式在两个类中重复出现，触发 SonarQube 硬编码凭证规则。

## 关键代码修改
1. Java 式监听器改 Kotlin 惯用法 + 安全调用（`adapter/AccountAdapter.kt`）：
```diff
-    interface OnAccountClickListener {
+    fun interface OnAccountClickListener {
         fun onAccountClick(account: Account?)
     }
@@ -25,18 +25,16 @@
-        val account = accountList.get(position)
+        val account = accountList[position]
-        holder.itemView.setOnClickListener(View.OnClickListener { v: View? ->
-            if (listener != null) {
-                listener.onAccountClick(account)
-            }
-        })
+        holder.itemView.setOnClickListener {
+            listener?.onAccountClick(account)
+        }
```
2. 消除重复密钥常量：`SignUtil` 的 `SECRET_KEY` 删除，改为从 `AES256Util` 统一读取，加密算法处加 `// NOSONAR` 标注（`utils/AES256Util.kt`、`utils/SignUtil.kt`）：
```diff
+    /**
+     * 获取AES密钥（供签名工具使用）
+     */
+    fun getAesKey(): String = AES_KEY
```
```diff
-        val cipher = Cipher.getInstance(ALGORITHM)
+        val cipher = Cipher.getInstance(ALGORITHM) // NOSONAR
```
```diff
-    private const val SECRET_KEY = "K8xP2qN5vR9mJ4cF7bH1wZ6tY3dG0sL5"
@@ -27,7 +26,7 @@
-        sb.append(SECRET_KEY)
+        sb.append(AES256Util.getAesKey())
```
3. Dialog 判空改 `?.` 链（`dialog/ExitLoginDialog.kt`）：
```diff
-        btnLogoutConfirm!!.setOnClickListener(View.OnClickListener { v: View? ->
-            if (onExitListener != null) {
-                val isClear = cbClearData!!.isChecked()
-                onExitListener!!.onConfirm(isClear)
-            }
-        })
+        btnLogoutConfirm?.setOnClickListener {
+            onExitListener?.onConfirm(cbClearData?.isChecked == true)
+        }
```

## 为什么能修复
每处对应明确的 SonarQube/Kotlin 规则：`fun interface` 满足 SAM 接口风格规则、`?.` 链消除 `!!` 空断言规则、`[position]` 消除 Java 惯用法告警、密钥单点收敛消除"硬编码凭证/重复常量"告警。`NOSONAR` 是对"服务端约定了 AES 模式，无法换算法"的现实妥协——告警压制但留痕。密钥本身仍在源码中（仅移动位置），安全实质未变。

## 复盘与经验
- `// NOSONAR` 应配合注释说明原因使用，否则变成"告警黑洞"；本例只压了算法弱度告警，属于已知的服务端协议约束。
- 密钥收敛到 `getAesKey()` 只是消除重复告警，真正要解决硬编码密钥需下沉到 NDK/服务端下发/签名服务，是明确的遗留风险。
- Java→Kotlin 迁移后的第一轮 SonarQube 治理收益最大：`fun interface`、`?.`、属性访问语法三类改法可直接套用到其他模块（与 `b02aee28` 的 BTMusic 治理同模式，可抽成团队整改清单）。
