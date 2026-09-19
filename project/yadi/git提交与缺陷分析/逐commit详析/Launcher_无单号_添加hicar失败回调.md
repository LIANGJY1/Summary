# [SRS_BT_LinkSetting_012] · 添加 HiCar 失败回调（Launcher 侧消费）

- **提交**：`02f0e8db` | 2026-08-06 | dufan | Launcher | feature
- **关联单**：SRS_BT_LinkSetting_012

## 需求/目标
消费 `ff1b4452` 升级后的 HiCar PSDK 新增的 `SDK_INIT_FAILED` 融合 UI 类型：HiCar SDK 初始化失败时，连接页显示失败态视图。

## 实现结构
单文件 `application/Launcher/.../function/link/LinkActivity.kt`（+5）：
在 `onFusionUiTypeChanged`（融合 UI 类型回调）的 when 分支表中，为 `HiCarConstants.FusionUiType.SDK_INIT_FAILED` 新增分支，与既有失败/提示分支一致调用 `setViewVisibility(3)`（显示失败占位视图）。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/function/link/LinkActivity.kt
@@ -87,6 +88,10 @@
                         setViewVisibility(3)
                     }
 
+                    HiCarConstants.FusionUiType.SDK_INIT_FAILED -> {
+                        setViewVisibility(3)
+                    }
+
                     CarLinkConstants.FusionUiType.BLUETOOTH_OPENING -> {
                         setViewVisibility(0)
                     }
```
实现讲解：连接页用"融合 UI 类型"单回调驱动多状态视图（`setViewVisibility(index)`），新增失败类型只需补一个 when 分支，扩展成本低；`setViewVisibility(3)` 用数字索引指代视图态，语义靠上下文推断。

## 复盘与要点
- SDK 枚举驱动的状态机扩展点清晰，但 `setViewVisibility(0/3)` 魔法数字建议换成枚举或常量（VIEW_FAIL 之类）。
- 初始化失败与连接失败共用同一视图态（都映射 3），若产品后续要求区分文案（"初始化失败/连接失败"）需要再拆。
