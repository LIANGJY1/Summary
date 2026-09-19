# 无单号 · 迎宾模式新增副标题

- **提交**：`520ea8f8` | 2026-07-23 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
在场景模式（迎宾模式）弹窗界面新增一行副标题提示"迎宾模式仅在蓝牙钥匙靠近车辆时生效"，向用户解释该模式不生效的常见疑问。

## 实现结构
3 个文件（+65/-61）：中英文 `strings.xml` 各新增 `welcome_mode_tips`；`fragment_scene_mode.xml` 布局调整（124 行变动，重排/新增 TextView 并微调既有控件约束，使标题下方容纳副标题）。

## 关键代码
```diff
--- a/application/Setting/src/main/res/values/strings.xml
     <string name="welcome">迎宾</string>
+    <string name="welcome_mode_tips">迎宾模式仅在蓝牙钥匙靠近车辆时生效</string>
```
实现讲解：典型的纯 UI 文案需求，改动局限于布局与双语文案资源；中英文 key 同步新增避免缺译回退。

## 复盘与要点
- "解释生效条件"的提示文案是减少客诉的低成本手段；文案内容（蓝牙钥匙靠近才生效）本身也是一份产品行为依据。
- 布局 diff 达 124 行说明原 XML 约束较脆弱，新增一行文本引起大范围重排，可考虑 RelativeLayout/ConstraintLayout 归组减少连锁调整。
