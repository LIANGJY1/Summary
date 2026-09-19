# 无单号 · 添加语音操作热点逻辑

- **提交**：`24a53661` | 2026-07-10 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
在语音操作体系中补充"热点"指令：支持语音关闭热点弹窗（`close_connect_hot`）与语音打开热点设置（`open_connect_hot`），是对 `4448373d` 语音框架的能力增项。

## 实现结构
改动 2 个文件：
- `utils/VoiceOperationUtil.kt`：广播分支将 `close_connect_hot` 并入关弹窗指令组；`handleIntent` 中三个 `open_connect_*` 指令合并映射到 `ConnectFragment`，并新增 `when(operation)` 把指令翻译为 `wifi/bluetooth/hotspot` 类型值，非空才延迟弹窗。
- `ui/fragment/ConnectFragment.kt`：LiveData 观察处新增 `"close_connect_hot" -> mHotspotDialogFragment?.safeDismiss()`。

数据流：语音广播 → `VoiceOperationUtil` 指令表 → `ConnectFragment` 弹/关对应 DialogFragment（WLAN/蓝牙/热点三选一）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/VoiceOperationUtil.kt
@@ -111,21 +112,28 @@
-                "open_connect_wifi" -> ConnectFragment::class.java
-                "open_connect_bluetooth" -> ConnectFragment::class.java
+                "open_connect_wifi", "open_connect_bluetooth", "open_connect_hot" -> ConnectFragment::class.java
                 else -> null
             }
             if (clazz != null) {
                 val index = findFragmentPosition(navItemList, clazz)
                 setPage(if (index == -1) 0 else index)
-                if (operation == "open_connect_wifi" || operation == "open_connect_bluetooth") {
+                if (clazz == ConnectFragment::class.java) {
+                    val type = when (operation) {
+                        "open_connect_wifi" -> "wifi"
+                        "open_connect_bluetooth" -> "bluetooth"
+                        "open_connect_hot" -> "hotspot"
+                        else -> ""
+                    }
```
实现讲解：扩展方式是往既有指令表各分支"追加一个 case"，框架无需改动。同时把"哪些指令要弹窗"的判断从硬编码字符串比较升级为"类型翻译结果非空"，结构上更通用。

## 复盘与要点
- 本提交埋了一个后续才会暴露的缺陷：延迟弹窗里仍写死 `showConnectDialog(if (operation == "open_connect_wifi") "wifi" else "bluetooth")`，刚算出来的 `type` 变量只用于判空、没有真正传入——`open_connect_hot` 实际会去弹蓝牙弹窗。这是"翻译了参数却没用上"的典型疏漏，code review 时应对新增变量的实际消费链做核对。
- 可复用手法：指令字符串 → 类型枚举 → Fragment 方法参数 的三级映射，新增指令只需在两处 when 各加一行。
- 遗留风险：关闭类指令靠广播、打开类指令靠 Intent，两条通道的指令命名（`close_connect_hot` vs `open_connect_hot`）靠人肉保持对称，容易漏。
