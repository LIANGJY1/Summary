# SIR-7186 · 能量中心续航里程数值偏大

- **提交**：`6981206d` | 2026-09-03 | liqingqing | EnergyManagement | bugfix（纯样式修正）
- **缺陷库**：等级 C（提交标注 B）· 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心主界面左上角续航里程数值显示比 UI 稿明显偏大。

## 根因与修复说明
缺陷库根因"总体大小不对"、提交 [why]"字体大小错误"：续航数值 TextView 与其样式 `some_id`（"左上角文字显示框样式"）都写成了 `88sp`，而 UI 稿为 `64sp`。改动仅两处样式值：

```diff
// application/EnergyManagement/src/main/res/layout/activity_main.xml
-            android:textSize="88sp"
+            android:textSize="64sp"
             ...
+            android:translationY="8dp"   （单位竖排容器微调基线对齐）
```
```diff
// application/EnergyManagement/src/main/res/values/themes.xml
     <style name="some_id">
-        <item name="android:textSize">88sp</item>
+        <item name="android:textSize">64sp</item>
```

字号按稿改为 64sp 后数值视觉尺寸恢复设计值；`translationY=8dp` 同步微调右侧单位栏的垂直对齐。零逻辑风险。隐患：字号在布局与 style 两处重复定义（布局值覆盖 style 值），双重维护点易再次不同步，建议只保留 style 一处。

## 复盘与经验
- 布局里 `android:textSize` 与主题 style 同时存在的"双写"是字号不一致的温床——同一属性一处改、一处漏就是 UI 缺陷。
- 数值+单位的组合排版，改字号后必须连带核对基线对齐（本例用 translationY 补偿），否则修完大小又冒出对齐问题。
- 命名为 `some_id` 的 style 属临时命名流入主干，可读性差且难检索，样式命名应规范。
