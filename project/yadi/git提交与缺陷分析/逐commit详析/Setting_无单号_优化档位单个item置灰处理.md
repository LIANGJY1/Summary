# 无单号 · 优化档位单个item置灰处理
- **提交**：`8d645edb` | 2026-08-25 | sgh | Setting | feature（微调型）
- **关联单**：无

## 需求/目标
统一 `ImageTextRadioGroup.setItemSelectable` 单项置灰的视觉规格：不可选透明度从 0.5 改为 0.3，与全组置灰（ALPHA_DISABLED=0.3f）保持一致，并清理逐条调试日志。

## 实现结构
仅改 `component/CommonTools/.../widgets/ImageTextRadioGroup.kt` 一个函数（+3/-7）：alpha 常量对齐、删除 4 处 LogUtils 调试输出与越界/空判日志。该 API 由 d27b53ee 引入（行车中外灯 OFF 档禁止选择），本提交是其在通用控件层的体验收口。

## 关键代码
```kotlin
// component/CommonTools/src/main/java/com/yadea/common/widgets/ImageTextRadioGroup.kt
         val radioButton = findViewById<RadioButton>(radioItems[position].id)
         radioButton?.let { rb ->
             rb.isEnabled = selectable
             if (!selectable) {
-                rb.alpha = 0.5f // 半透明表示不可选
+                rb.alpha = 0.3f
             } else {
-                rb.alpha = 1.0f // 正常显示
+                rb.alpha = 1.0f
             }
-        } ?: LogUtils.w(TAG, "位置 $position 未找到对应的 RadioButton")
+        }
```
单档位禁用（0.3）与整组禁用（setGrayState 0.3）现在使用同一透明度语言，用户不会因深浅不同误判"半禁用"状态。

## 复盘与要点
- 置灰透明度这类视觉常量应全局唯一定义（common 层常量或主题属性），逐处字面量 0.5/0.3 迟早再次漂移。
- 调试日志随功能定型移除是合理节奏，但"位置超出范围"这类边界告警日志建议保留，静默 return 会让调用方传错索引时无从察觉。
