# YD-392844 · 充电剩余时间单位字号有数据/无数据时不一致
- **提交**：`657a13a1` | 2026-07-09 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：未关联单号（提交标题带 YD-392844，defs 为空）

## 问题
充电剩余时间显示：有数据时与无数据（占位"--"）时，时间单位（h/min）的字号大小不一致，UI 观感不统一。

## 根因分析
`MainActivity.formatRemainingTime(int totalMinutes)` 用 `SpannableString` 拼接数值与单位，单位字号写死在方法内的局部变量 `int unitSizeSp = 14`；而"无数据"态的单位字号走的是布局/其他样式路径（20sp 档），两条渲染路径字号不统一，出现单位忽大忽小。属于典型的"魔法数分散、多路径渲染同一控件但样式来源不同"问题。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java（+2/-1）
```diff
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ 常量定义
     private static final int SLOW_CHARGE_POWER_DEFAULT_TENTHS = 20;
+    private static final int REMAINING_TIME_UNIT_TEXT_SIZE_SP = 20;
@@ formatRemainingTime
     private SpannableString formatRemainingTime(int totalMinutes) {
         int hours = totalMinutes / 60;
         int minutes = totalMinutes % 60;
-        int unitSizeSp = 14;
+        int unitSizeSp = REMAINING_TIME_UNIT_TEXT_SIZE_SP;
```

## 为什么能修复
把格式化路径里的单位字号从 14sp 对齐到无数据态使用的 20sp，并提取为常量 `REMAINING_TIME_UNIT_TEXT_SIZE_SP`，两条渲染路径的单位字号从此一致。风险极低，但要留意：以后若设计再调整字号，需要同步改该常量与无数据态样式，仍是两处定义，未彻底收敛为单一来源。

## 复盘与经验
- **同一控件的多态渲染必须共享样式常量**：数值态/占位态若各自硬编码字号，迟早漂移；应提取常量甚至下沉到 style/dimen。
- **魔法数内联是 UI 不一致的温床**：`int unitSizeSp = 14` 这类局部魔法数，review 时应要求提为命名常量或资源引用。
- **单位换算后仍要盯 UI**：`totalMinutes` 拆 hour/minute 属于单位换算逻辑，换算正确不代表展示正确，字号/单位这类视觉规格同样需要验收。
