# SIR-7226 · 极致续航标注文言错误（"和安安全相关的好点功能"）
- **提交**：`d6ce5ec8` | 2026-09-02 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
极致续航确认弹窗右侧标注文言显示为"……大量关闭除驾驶和安安全相关的耗电功能……"，存在错别字"安安全"（另一处元数据提到的"好点功能"为"耗电功能"的转述误差，diff 实际仅修"安安全"→"安全"）。

## 根因分析
字符串资源 `application/Setting/src/main/res/values/strings.xml` 中 `extreme_range_tip` 文案录入时打字重复，把"安全"写成"安安全"。纯文案录入错误，无逻辑问题。

## 关键代码修改
改动文件：`application/Setting/src/main/res/values/strings.xml`
```diff
--- application/Setting/src/main/res/values/strings.xml
-    <string name="extreme_range_tip">开启极致续航后，将会大量关闭除驾驶和安安全相关的耗电功能,是否开启极致续航</string>
+    <string name="extreme_range_tip">开启极致续航后，将会大量关闭除驾驶和安全相关的耗电功能,是否开启极致续航</string>
```

## 为什么能修复
删除重复的"安"字，文案恢复正确。单字修改，零风险。

## 复盘与经验
- 弹窗/标注类文言应走文案评审清单（设计稿比对 + 拼写校对），错别字属于低成本可完全拦截的 C 级缺陷。
- 文案 key（extreme_range_tip）在多个弹窗复用时，一处错字会同步放大到所有引用点，修正一个 key 即全量生效——这正是字符串资源集中管理的好处。
