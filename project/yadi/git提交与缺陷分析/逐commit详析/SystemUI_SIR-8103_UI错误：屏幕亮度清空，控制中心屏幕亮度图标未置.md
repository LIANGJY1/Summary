# SIR-8103 · 屏幕亮度清空时亮度图标未置灰

- **提交**：`0b714b60` | 2026-09-11 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
亮度调到最低（亮度条"清空"）后，控制中心的屏幕亮度图标没有切换为置灰/最低亮度图标，仍显示正常亮度图标。

## 根因分析
`BrightnessTile.updateBrightnessIcon(Integer value)` 用 `value == 0` 判断"最低亮度"来切换 `R.drawable.vector_display_0` 图标。但该车机系统亮度最小值是 1 而不是 0（提交信息明确写"最小亮度是1，不是0"），亮度条拉到最底时上报值永远是 1，永远不等于 0，分支不可达，图标保持 `vector_display`。这是典型的"边界值与底层取值范围不一致"问题：UI 判断用的魔法值没有对齐系统真实的亮度下限。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/BrightnessTile.java`
```diff
     private void updateBrightnessIcon(Integer value) {
-        if (value == null || value == 0) {
+        if (value == null || value == 1) {
             brightnessIconIv.setImageResource(R.drawable.vector_display_0);
         } else {
             brightnessIconIv.setImageResource(R.drawable.vector_display);
```

## 为什么能修复
把"最低亮度"判断值从 0 改为系统真实下限 1，亮度条清空（值为 1）时即可命中分支显示 `vector_display_0` 置灰图标。改动一行、无副作用；若后续系统亮度下限调整（例如允许 0），该判断需再次同步——本质上是把一个隐含约定（最小值=1）显式写进了判断，仍属于魔法数字用法。

## 复盘与经验
- UI 状态判断的边界值必须与底层/驱动真实取值范围对齐，"想当然的 0"是亮度、音量这类从 1 开始的系统的常见坑。
- 这类必现 UI 状态错误最适合用"边界值遍历"测试提前暴露：拉到最小/最大各看一次状态图。
- 可用常量（如 `MIN_BRIGHTNESS = 1`）替代裸数字，取值范围变更时只改一处。
