# YD-392766 · 续航里程两位数显示位置偏右

- **提交**：`1b9b56c5` | 2026-06-29 | liqingqing | EnergyManagement | bugfix（UI 布局计算修复）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
能耗管理页续航里程数值在两位数（10~99 km）时整体偏右、未按设计稿居中，一位数与三位数正常。

## 根因分析
`MainActivity.updateRangeValueStart(int range)` 用"按位数设置 `marginStart`"的方式控制数值横向位置，旧逻辑只有两档：`range < 10 ? 320 : 395`——两位数与三位数共用 395dp。而目标 `TextView` 设置了 `ems=3`（固定 3 个字符宽）且 `gravity=end`，两位数文本只占 ems 框右侧约 2/3 宽度，左 1/3 是空白，再叠加 395dp 的 start 边距，视觉重心明显右偏。这是"位数分档不完整 + ems 定宽 + gravity=end"三者叠加的经典布局算术错误：开发者按三档位数各调一个 margin 即可让文本块落入设计稿锚点，但最初只写了两个分支。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java

```diff
--- application/EnergyManagement/.../view/ui/MainActivity.java
@@ updateRangeValueStart 位数分档补全为三档
-        int marginDp = range < 10 ? 320 : 395;
+        int marginDp;
+        if (range < 10) {
+            marginDp = 320;
+        } else if (range < 100) {
+            marginDp = 350;
+        } else {
+            marginDp = 360;
+        }
         ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) binding.someId.getLayoutParams();
         int marginPx = dpToPx(marginDp);
```
（提交消息中的 [how] 描述为"1/2/3 位数分别 320/350/360dp"，与代码一致。）

## 为什么能修复
两位数获得独立的 350dp 边距，补偿了 ems 定宽框内两位文本左侧的空白量，文本块回到设计稿锚点；三位数微调到 360dp 与三位文本宽度对齐。注意边界值：`range >= 1000`（四位数，若续航超过 999km 理论可现）会落入 else 档 360dp，四位文本在 ems=3 框内会溢出/截断，该分支未处理；且 320/350/360 三个魔法数只在本方法出现一次、靠注释外无语义，属于"按当前屏幕密度调好的值"，换屏或字体缩放需整组重调。代码里 `binding.someId` 命名也很可疑（疑似脱敏/占位命名残留），以 diff 为准照实记录。

## 复盘与经验
- **按位数/长度分档的布局逻辑，档位必须与"可能长度集合"一一对应**：文本位数有 1/2/3 档，margin 分档也得三档，少一档就是本 bug。
- **`ems` 定宽 + `gravity=end` 会把"短文本"问题转化为"偏移量"问题**：要么用真实内容宽（wrap_content + 参考线约束），要么按档位精确补偿空白。
- **布局魔法数集中管理**：320/350/360 应提为带注释的常量或 dimens 资源，并注明适用分辨率，避免下次位数变化再拍脑袋。
