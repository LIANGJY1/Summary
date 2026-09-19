# YD-392949 · 黑夜模式去电浮窗颜色与背景融为一体（应为 #1D1F23）

- **提交**：`b3eef752` | 2026-07-29 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联缺陷库记录（单号 YD-392949，defs 无条目）

## 问题
黑夜模式下蓝牙电话去电浮窗的背景色与底层页面背景几乎相同，浮窗轮廓"融"进背景里，设计要求浮窗底色为 #1D1F23 以形成层次差。

## 根因分析
浮窗背景 `drawable/bg_float_layout.xml` 的 solid 色引用了 `@color/bg_application`——这是"应用页面背景"令牌。黑夜模式下浮窗所覆盖的底层页面同样使用 `bg_application` 系背景，两者取值相同，浮窗失去对比度。浮窗/OSD 类悬浮层在主题体系里有专属令牌 `bg_osd`（规范值即 #1D1F23，定义在外部共享主题库，本仓库无该资源定义），语义上与页面背景区分出明度层次。

## 关键代码修改
改动文件：application/BTPhone/src/main/res/drawable/bg_float_layout.xml

```diff
--- application/BTPhone/src/main/res/drawable/bg_float_layout.xml
 <shape xmlns:android="http://schemas.android.com/apk/res/android"
     android:shape="rectangle">
 
-    <solid android:color="@color/bg_application" />
+    <solid android:color="@color/bg_osd" />
 
     <corners
         android:radius="24dp"
```

## 为什么能修复
浮窗底色由"页面背景令牌"换成"OSD 悬浮层令牌"（#1D1F23），黑夜模式下与底层背景形成亮度差，浮窗轮廓可辨识；白天模式同样取 `bg_osd` 的亮色变体，两种模式统一由主题库管控。无逻辑副作用；需注意同一 drawable 若还被其他非浮窗布局引用，所有引用处底色会一起变化（本文件名为 bg_float_layout，引用面预期仅浮窗）。

## 复盘与经验
- **"融为一体化"类 bug 的本质是令牌用错层级**：浮窗、弹窗、卡片与页面背景是不同层级，各有专属背景令牌；贪方便共用 `bg_application` 在黑夜模式（整体都暗）下最先暴露。
- **暗色主题对层次差更敏感**：白天模式下浅色系尚有容差，黑夜模式两个深色相同值即完全不可见，暗色适配必须逐层核对背景令牌。
- **悬浮窗背景应有独立设计令牌（如 bg_osd）并全项目统一引用**，避免每个模块自行挑选近似色。
