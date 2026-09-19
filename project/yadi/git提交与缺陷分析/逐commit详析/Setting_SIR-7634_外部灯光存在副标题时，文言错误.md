# SIR-7634 · 外部灯光副标题速度单位大小写与 UI 稿不一致
- **提交**：`15ce328b` | 2026-09-08 | sgh | Setting | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（rc：文本错误）

## 问题
外部灯光副标题"仅在车速低于10km/h时，可关闭所有灯光"中单位写作 `10km/h`，与 UI 设计稿的 `10Km/h` 大小写不一致，走查记为文言（文案）错误。

## 根因分析
`values/strings.xml` 与 `values-en/strings.xml` 的 `external_lighting_subtitle` 都写成了标准 SI 写法 `10km/h`，而 UI 稿按其规范用的是 `10Km/h`。这是代码文案与设计稿字面不一致的低级偏差——注意方向：**修复是向 UI 稿对齐**（km→Km），而不是纠正 UI 稿，说明该团队以设计稿字面为验收基准。

## 关键代码修改
改动文件：application/Setting/src/main/res/values/strings.xml、application/Setting/src/main/res/values-en/strings.xml（各 1 行）
```diff
--- application/Setting/src/main/res/values/strings.xml
@@ -53,7 +53,7 @@
-    <string name="external_lighting_subtitle">仅在车速低于10km/h时，可关闭所有灯光</string>
+    <string name="external_lighting_subtitle">仅在车速低于10Km/h时，可关闭所有灯光</string>
--- application/Setting/src/main/res/values-en/strings.xml
@@ -51,7 +51,7 @@
-    <string name="external_lighting_subtitle">仅在车速低于10km/h时，可关闭所有灯光</string>
+    <string name="external_lighting_subtitle">仅在车速低于10Km/h时，可关闭所有灯光</string>
```

## 为什么能修复
两套语言资源的副标题统一改为 `10Km/h`，与 UI 稿逐字一致，走查通过。纯字符串改动无逻辑风险；隐患是 `10Km/h` 并非标准计量写法（标准为 km/h），若后续引入自动化文案校验或国际化审校，可能又被改回去，需要设计侧先行统一规范。

## 复盘经验
- 文案走查的差异往往是大小写/全半角级别，对照工具要做字面 diff 而不是肉眼；验收基准（以 UI 稿为准还是以规范为准）要事先定。
- 多语言目录（values/values-en）的同一字符串必须同步修改，漏一个语言目录就会在切语言时复现"修了又没修"。
- 单位、标点这类高symbol敏感文案建议在设计系统里做成组件/资源常量，避免每个页面手写。
