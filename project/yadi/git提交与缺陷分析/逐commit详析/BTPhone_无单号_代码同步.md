# 无单号 · BTPhone 代码同步
- **提交**：`38fc44ca` | 2026-07-14 | hedeyuan | BTPhone | 分支快照（代码同步）
- **缺陷库**：未关联单号

## 类型说明
"代码同步"类分支快照提交：145 个文件，+6184/-9607，横跨 `application/BTPhone` 几乎全部 Java 类（MainActivity、ThreeWayCallingActivity、各 adapter、can/、cmdcontroller/、common/ 等）、Manifest、gradle 及大量 drawable/layout/xml 资源。提交消息 what/why/how 均为"代码同步"，无任何缺陷语义描述，无法对应到具体问题，不强行剖析。

## 改动概要
- 大量删减（-9607 明显大于 +6184）：含多个 float_window layout、status icon drawable 的删除，疑似裁剪悬浮窗/冗余资源或与其他分支对齐后的瘦身。
- `android/telecom/Call.java`、`TelecomManager.java`、`android/os/SystemProperties.java` 等 framework 桩类有改动。
- 逻辑层（Activity/Adapter/CAN 管理）大范围重排，无法从单一语义判断修了什么。

## 复盘与经验
- "代码同步"提交应注明来源分支/基线（如"同步自 release/xxx 至 2026-07-14"），否则二分定位（git bisect）时这类提交是断点，污染追溯。
- 万行级快照建议拆分或至少附变更摘要，当前形态下任何自动化缺陷归因都只能跳过。
