# SIR-3225 · 联系人侧边栏拖动气泡指向字母错位

- **提交**：`98235c07` | 2026-07-22 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话（根因：气泡绘制位置错误；方案：修改气泡绘制位置）

## 问题
同步联系人后拖动联系人列表侧边字母索引条时，提示气泡（显示当前字母的箭头气泡）指向位置与手指所在字母不对齐。

## 根因分析
`ContactsFragment` 里拖动索引条时按 `targetY = letterIndex * letterHeight + letterHeight / 2` 计算气泡中心 Y 坐标，即"第 N 个字母格子的垂直中心"。但气泡实际锚定的列表区域相对索引条存在约 20px 的布局偏移（索引条顶部与列表可视区起点不重合），公式算出的位置系统性偏上，且 `updateBubblePosition(targetY)` 是按父布局坐标直接放置，没有再补偿，于是每个字母的气泡都错位同样距离。缺陷库根因"气泡绘制位置错误"即指此公式缺一个偏移补偿项。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/fragment/ContactsFragment.java`
```diff
// application/BTPhone/src/main/java/com/yadea/btphone/fragment/ContactsFragment.java
                         // 计算该字母对应的 Y 坐标
                         float letterHeight = binding.contactIndexBar.getLetterHeight();
-                        float targetY = letterIndex * letterHeight + letterHeight / 2;
+                        float targetY = letterIndex * letterHeight + letterHeight / 2 + 20;
                         // 动态设置气泡的位置（相对于父布局）
                         updateBubblePosition(targetY);
```

## 为什么能修复
给气泡 Y 坐标统一加 20px 偏移，把索引条坐标与父布局锚点之间的固定布局差补齐，气泡重新对准手指所在字母。属于典型"经验值补偿"修法：改动最小、立即生效，但 20 是硬编码像素值，若日后索引条内边距、气泡尺寸或屏幕密度变化，需要重新校准；更稳的做法是从 `getPaddingTop()`/视图相对坐标动态推导偏移。

## 复盘与经验
- 自绘悬浮指示物（气泡/锚点）时，坐标系要与目标视图对齐：要么统一转换成同一父布局坐标，要么显式补偿 padding/margin，靠目测调 magic number 迟早随布局改动复发。
- "错位量恒定"是布局偏移类 bug 的特征指纹——所有字母都偏同样距离，说明公式少了一项常量，而非逻辑错误。
- 一行补偿式修复适合兜底发版，但应在代码里注释 20px 的来源，否则下一位维护者只能再目测一次。
