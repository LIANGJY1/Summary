# SIR-7246 · 黑夜模式下用户/隐私协议滚动条不可见

- **提交**：`4c6c5185` | 2026-09-03 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
用户协议和隐私协议弹窗：白天模式能看到滚动条，黑夜模式滚动条"消失"（与深色背景融为一体）。

## 根因分析
协议弹窗的滚动条 thumb 资源 `drawable/scrollbar_thumb.xml` 把颜色写死为 `#3320232B`——带 20% 透明度的深色。白天浅色背景下深色 thumb 可见；黑夜模式下弹窗背景为深色，深色半透明 thumb 与背景对比度趋近于零，视觉上等于没有滚动条（缺陷库根因"未使用颜色 token"）。这是 `3d3d7a15`/`660ca21e`/`fb8572d3` 同族的"硬编码色值不随主题"问题，只是发生在 shape drawable 里。

## 关键代码修改
改动文件：drawable/scrollbar_thumb.xml（仅 1 行）
```diff
// application/Setting/src/main/res/drawable/scrollbar_thumb.xml
-    <solid android:color="#3320232B"/>
+    <solid android:color="@color/text_default_disabled"/>
```

## 为什么能修复
改用主题化 token `text_default_disabled`，其日/夜两套取值在两种模式下都与背景保持足够对比度，滚动条白天黑夜都可见且风格统一。与 `9418a192` 新建的 `bg_rv_scrollbar_thumb`（用 `icon_default_disabled`）并行存在两份滚动条 drawable，token 口径还不一致，属轻微技术债。零逻辑风险。

## 复盘与经验
- drawable/shape 内的 `solid android:color="#"` 硬编码是夜间适配最常见的第三处盲区（前两处：布局 textColor、style），静态检查可全覆盖。
- "白天有、黑夜无"的视觉元素，优先怀疑对比度：把元素颜色和背景色都查一遍，而不是先怀疑控件逻辑。
- 滚动条样式应全仓库统一为一份 drawable + token（本仓库已出现 scrollbar_thumb 与 bg_rv_scrollbar_thumb 两份），避免同组件多套标准。
