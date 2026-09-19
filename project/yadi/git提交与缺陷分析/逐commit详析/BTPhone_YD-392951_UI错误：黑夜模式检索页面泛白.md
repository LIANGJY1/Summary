# YD-392951 · 黑夜模式检索页面泛白（第二步：命中高亮色适配）

- **提交**：`367f3f19` | 2026-07-30 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联缺陷库记录（单号 YD-392951，defs 无条目）

## 问题
黑夜模式下蓝牙电话检索页仍泛白：检索命中文字的高亮颜色未随昼夜模式变化（本单第二步，前一日 `3faf14af` 已补占位图暗色版）。

## 根因分析
`HighlightUtil.getHighlightColor()` 直接返回硬编码色值 `0xFF000000`（不透明纯黑）。检索结果中命中的字符用该颜色强调显示，黑夜模式下暗色背景上再叠黑色高亮，文字与背景混作一团，页面观感"泛白/发糊"。这是与 `3faf14af` 互补的另一半根因：占位图修了，但**代码内硬编码的颜色工具类**仍是昼夜盲。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/utils/HighlightUtil.java；application/BTPhone/src/main/res/values/colors.xml；application/BTPhone/src/main/res/values-night/colors.xml（新增）

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/utils/HighlightUtil.java
     @ColorInt
     public static int getHighlightColor() {
-        return 0xFF000000;
+        return ContextCompat.getColor(BtPhoneApp.getInstance(), R.color.search_high_text);
     }
```

```diff
--- application/BTPhone/src/main/res/values/colors.xml
+    <color name="search_high_text">#000000</color>
--- application/BTPhone/src/main/res/values-night/colors.xml（新增）
+    <color name="search_high_text">#FFFFFF</color>
```

## 为什么能修复
高亮色从代码硬编码改为资源引用 `search_high_text`：values 白天取 #000000（保持原视觉），values-night 取 #FFFFFF，黑夜模式下命中文字变为白色高亮，与暗色背景对比清晰，"泛白"观感消除。BTPhone 模块由此首次引入 `values-night/colors.xml`，为后续颜色适配建立了机制载体。风险极小；注意 `BtPhoneApp.getInstance()` 依赖 Application 早期初始化，若工具类在 App 未就绪时被调用会 NPE。

## 复盘与经验
- **工具类中的硬编码颜色是昼夜适配的最后盲区**：布局/drawable 都引用了主题色，唯独 `HighlightUtil` 返回字面量 `0xFF000000`，整页适配就差这最后一环；排查"部分泛白"时应对 `0x...` 字面色值做专项 grep。
- **同类问题分两次提交修（图一次、色一次）说明缺乏整页验收清单**：昼夜模式适配应有 checklist——占位图、高亮/强调色、背景层次、按压态逐项过。
- **新增 values-night/colors.xml 是模块化适配的起点**：把第一个语义色（search_high_text）放进去后，后续颜色应持续归位而非继续硬编码。
