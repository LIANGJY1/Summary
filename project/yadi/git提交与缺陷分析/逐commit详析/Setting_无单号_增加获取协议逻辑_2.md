# 无单号 · 增加获取协议逻辑（Setting 侧展示）

- **提交**：`fb2950c8` | 2026-07-17 | dufan | Setting | feature
- **关联单**：无（与 SystemUI 侧 `319843b1` 成对提交）

## 需求/目标
"关于系统"页面打开隐私政策/用户许可协议时，优先使用云端拉取的最新协议 URL（由 `319843b1` 写入 GSetting），无网络未拉到时回退到本地内置 HTML。

## 实现结构
单文件 5 行改动：`ui/fragment/SystemFragment.kt` 中两处弹 WebFragment 的入口（个人信息保护政策、用户许可协议），把写死的 `PAGE_URL` 改为先读 GSetting、为空则用本地文件名兜底。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt
-            bundle.putString("PAGE_URL", "personal_infomation_agreement.html")
+            val person = SettingsUtils.getGSetting("personal_infomation_agreement")
+            bundle.putString("PAGE_URL",if (TextUtils.isEmpty(person)) "personal_infomation_agreement.html" else person)
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt
-            bundle.putString("PAGE_URL", "user_service_agreement.html")
+            val user = SettingsUtils.getGSetting("user_service_agreement")
+            bundle.putString("PAGE_URL",if (TextUtils.isEmpty(user)) "user_service_agreement.html" else user)
```
实现讲解：Setting 侧只做"读 + 兜底"，拉取职责放在 SystemUI 的网络广播里，两端以两个 GSetting key 解耦，读端无需感知网络状态。

## 复盘与要点
- "云端值优先、本地值兜底"的最小实现：两行读取 + 三元表达式，适合这类低频只读配置。
- key 字符串在两个 App 各写一遍（写入端 SystemUI、读取端 Setting），无统一常量，改名时极易失配——建议共享 constants 或改由统一配置中心下发。
- 若云端返回的 contentUrl 指向外网，WebFragment 需保证可加载 http/https；本地 html 资源仍需随包保留作为首启兜底。
