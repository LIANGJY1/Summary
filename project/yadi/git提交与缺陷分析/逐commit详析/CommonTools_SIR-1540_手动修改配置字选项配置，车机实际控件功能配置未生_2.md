# SIR-1540 · 新增哨兵模式配置字读取接口（CommonTools 侧）
- **提交**：`5f2e51de` | 2026-07-02 | dufan | CommonTools | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 系统需求
- **说明**：本提交与 `33f76f04`（Setting 侧）是同一单号 SIR-1540 的配套修改，本侧仅提供配置字读取能力。

## 问题
手动修改哨兵模式配置字后，车机设置界面不随配置变化，"哨兵模式"选项仍然展示/可配。

## 根因分析
配置字读取工具 `SysPropUtils`（`component/CommonTools/src/main/java/com/yadea/common/utils/SysPropUtils.kt`）此前已有 `getHandlebarHeated()` 等按 `SystemProperties.getInt(CONFIG_XXX, -1)` 封装的配置项，但没有哨兵模式配置字 `CONFIG_SENTRY_MODE` 的读取方法，上层 Setting 界面无从感知该配置字，UI 也就"未根据配置字修改"。

## 关键代码修改
改动文件：`component/CommonTools/src/main/java/com/yadea/common/utils/SysPropUtils.kt`
```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/utils/SysPropUtils.kt
@@ -134,4 +134,8 @@ object SysPropUtils {
     fun getHandlebarHeated(): Int {
         return SystemProperties.getInt(CONFIG_HANDLEBAR_HEATED, -1)
     }
+
+    fun getSentryMode(): Int {
+        return SystemProperties.getInt(CONFIG_SENTRY_MODE, -1)
+    }
 }
```

## 为什么能修复
为上层提供 `getSentryMode()` 标准入口（缺省 -1 表示未配置），使 UI 能按配置字分支渲染；真正消费它的是配套提交 `33f76f04`。约定沿用既有模式：`SystemProperties.getInt` + 默认值 -1，与其它配置项风格一致。风险极低。

## 复盘与经验
- 配置字能力要"读得到才能用得上"：新增配置字时同步在 SysPropUtils 补读取接口，避免上层各写各的。
- 读取接口统一默认值约定（-1 未配置），上层判断才有统一口径。
