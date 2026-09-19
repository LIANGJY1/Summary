# SIR-6608 · 能量中心充电上限弹窗文案分段错误

- **提交**：`ac52bfa8` | 2026-08-26 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 主交互

## 问题
充电上限说明弹窗的描述文案没有按 UI 稿分段，整段平铺显示，分段/换行与设计不符。

## 根因分析
文案资源 `application/EnergyManagement/src/main/res/values/strings.xml` 中的 `charge_limit_info_desc` 是一整句连续中文，没有任何 `\n`。该弹窗的 TextView 未开启自动换行按段显示（或设计稿本就要求固定三段式排版），控件按单段渲染导致"文言分段错误"。UI 稿的断句位置为：`……延长电池使用寿命；`之后与`若电量超过90%……`之前各需一行分隔。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/res/values/strings.xml`

```diff
--- a/application/EnergyManagement/src/main/res/values/strings.xml
@@ -95,7 +95,7 @@
     <string name="charge_limit_info_title" translatable="false">充电上限</string>
-    <string name="charge_limit_info_desc" translatable="false">日常充电建议充至90%及以内，该区间更利于延长电池使用寿命；若电量超过90%充电速度会明显变慢，建议长途使用。</string>
+    <string name="charge_limit_info_desc" translatable="false">日常充电建议充至90%及以内，该区间更利于延长电池\n使用寿命；\n若电量超过90%充电速度会明显变慢，建议长途使用。</string>
```

## 为什么能修复
在字符串资源的既定位置插入 `\n` 强制换行，弹窗文案按设计稿分段显示，且不需改动任何布局或代码。风险极低，但有两点可注意：硬编码 `\n` 与固定文案宽度耦合，若后续改弹窗宽度或字号，断行位置可能需要重新调整；多语言场景下（本资源 `translatable="false"`，暂为中文专用）不同语言译文长度不同，硬换行不宜推广到可翻译字符串。

## 复盘与经验
- 弹窗类短文案的"分段"常常是设计稿语义的一部分，提测对照设计稿时要把断行位置当作检查项，而不是只看文字内容。
- 固定排版文案用 `\n` 简单有效，但要在资源命名或注释上说明其与布局的耦合关系；可翻译文案则应交由布局自动换行，避免硬编码断行。
